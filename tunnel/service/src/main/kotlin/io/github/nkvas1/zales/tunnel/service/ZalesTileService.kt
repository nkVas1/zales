// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.nkvas1.zales.common.ZalesLog
import io.github.nkvas1.zales.tunnel.api.TunnelState
import io.github.nkvas1.zales.tunnel.api.isEngaged

/**
 * The switch, reachable without opening anything.
 *
 * Pulled down from the top of the screen, one touch, no hunting for an icon —
 * which for the person this app is built for is the difference between using
 * the tunnel and forgetting it exists.
 */
public class ZalesTileService : TileService() {

    private var service: ITunnelService? = null
    private var lastState: TunnelState = TunnelState.Idle

    private val callback = object : ITunnelCallback.Stub() {
        override fun onStatus(status: TunnelStatus) {
            lastState = status.toState()
            draw()
        }

        override fun onDiagnosis(diagnosis: DiagnosisStatus) = Unit
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val remote = ITunnelService.Stub.asInterface(binder)
            service = remote
            runCatching { remote.register(callback) }
                .onSuccess { lastState = it.toState() }
            draw()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            lastState = TunnelState.Idle
            draw()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        draw()
        runCatching {
            bindService(Intent(this, ZalesVpnService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.onFailure { ZalesLog.warn(ZalesLog.TAG_TUNNEL, "tile could not bind", it) }
    }

    override fun onStopListening() {
        runCatching { service?.unregister(callback) }
        runCatching { unbindService(connection) }
        service = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // Consent is an Activity's business and cannot be asked for from here.
        // Opening the app is the honest response, and it only happens once.
        if (VpnService.prepare(this) != null) {
            openApp()
            return
        }

        val wish = Wish(this)
        if (lastState.isEngaged) {
            wish.wantsOpen = false
            runCatching { service?.close() }
                .onFailure { startService(command(ZalesVpnService.ACTION_CLOSE)) }
        } else {
            wish.wantsOpen = true
            runCatching { service?.open() }
                .onFailure { startForegroundService(command(ZalesVpnService.ACTION_OPEN)) }
        }
        draw()
    }

    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return
        val pending = PendingIntent.getActivity(this, 0, launch, PENDING_FLAGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(pending)
        } else {
            // The PendingIntent form arrived in API 34, and below it this is the
            // only call that both opens the app and closes the shade. Plain
            // startActivity from a tile is blocked as a background launch.
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(launch)
        }
    }

    /** The tile says one word, the same word the big screen says. */
    private fun draw() {
        val tile = qsTile ?: return
        tile.state = when {
            lastState is TunnelState.Failed -> Tile.STATE_INACTIVE
            lastState.isEngaged -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.tile_name)
        tile.contentDescription = getString(word(lastState))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(word(lastState))
        }
        tile.updateTile()
    }

    private fun word(state: TunnelState): Int = when (state) {
        TunnelState.Idle -> R.string.tile_closed
        TunnelState.Preparing, is TunnelState.Probing -> R.string.tile_searching
        is TunnelState.Connected -> R.string.tile_open
        is TunnelState.Degraded, is TunnelState.Reconnecting -> R.string.tile_detour
        is TunnelState.Failed -> R.string.tile_failed
        TunnelState.Stopping -> R.string.tile_closing
    }

    private fun command(action: String): Intent =
        Intent(this, ZalesVpnService::class.java).setAction(action)

    private companion object {
        const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
