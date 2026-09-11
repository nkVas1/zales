// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import io.github.nkvas1.zales.tunnel.api.TunnelState

/**
 * The ongoing notification Android requires while a VPN runs.
 *
 * It is not a place for detail: it says what state the path is in, in the words
 * of docs/VOICE.md, and offers one action. Everything else lives in the app.
 */
internal class TunnelNotification(private val service: ZalesVpnService) {

    private val manager = service.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            service.getString(R.string.tunnel_channel_name),
            // Low: present and honest, never a sound or a heads-up banner.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = service.getString(R.string.tunnel_channel_description)
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(state: TunnelState): Notification {
        val builder = Notification.Builder(service, CHANNEL_ID)
            .setContentTitle(service.getString(titleOf(state)))
            // The app's own mark, not the system padlock: a notification that lives
            // in the shade for days should say which app it belongs to.
            .setSmallIcon(R.drawable.ic_zales_mark)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)

        openAppIntent()?.let(builder::setContentIntent)
        builder.addAction(
            Notification.Action.Builder(
                null,
                service.getString(R.string.tunnel_action_close),
                servicePendingIntent(ZalesVpnService.ACTION_CLOSE),
            ).build(),
        )
        return builder.build()
    }

    fun update(state: TunnelState) {
        manager.notify(NOTIFICATION_ID, build(state))
    }

    private fun titleOf(state: TunnelState): Int = when (state) {
        TunnelState.Idle, TunnelState.Preparing -> R.string.tunnel_state_preparing
        is TunnelState.Probing -> R.string.tunnel_state_probing
        is TunnelState.Connected -> R.string.tunnel_state_connected
        is TunnelState.Degraded -> R.string.tunnel_state_degraded
        is TunnelState.Reconnecting -> R.string.tunnel_state_reconnecting
        is TunnelState.Failed -> R.string.tunnel_state_failed
        TunnelState.Stopping -> R.string.tunnel_state_stopping
    }

    /** Opens whatever launcher activity the host app declares — the library stays decoupled from it. */
    private fun openAppIntent(): PendingIntent? =
        service.packageManager.getLaunchIntentForPackage(service.packageName)?.let { intent ->
            PendingIntent.getActivity(service, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }

    private fun servicePendingIntent(action: String): PendingIntent =
        PendingIntent.getService(
            service,
            action.hashCode(),
            Intent(service, ZalesVpnService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val NOTIFICATION_ID: Int = 0x7A15
        private const val CHANNEL_ID = "zales.tunnel"
    }
}
