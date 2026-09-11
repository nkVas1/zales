// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import io.github.nkvas1.zales.common.ZalesLog
import io.github.nkvas1.zales.tunnel.api.TunnelState
import io.github.nkvas1.zales.tunnel.api.isEngaged

/**
 * A plate on the home screen: one word, one mark, one touch.
 *
 * For someone who does not go looking through app drawers, this is the whole
 * product. It has to be readable from arm's length without glasses, and it has
 * to be truthful the instant it is glanced at, which is why the tunnel pushes
 * to it rather than the widget polling.
 */
public class ZalesWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Nothing is bound here, so the last word the tunnel published is the
        // best answer available; the service corrects it on its next change.
        draw(context, TunnelState.Idle, manager, ids)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            toggle(context)
            return
        }
        super.onReceive(context, intent)
    }

    private fun toggle(context: Context) {
        if (VpnService.prepare(context) != null) {
            // Consent needs a window. Opening the app is the only honest move,
            // and it is asked for exactly once in the life of the install.
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: return
            runCatching { context.startActivity(launch) }
            return
        }

        val wish = Wish(context)
        val open = !wish.wantsOpen
        wish.wantsOpen = open
        runCatching {
            val command = Intent(context, ZalesVpnService::class.java)
                .setAction(if (open) ZalesVpnService.ACTION_OPEN else ZalesVpnService.ACTION_CLOSE)
            if (open) context.startForegroundService(command) else context.startService(command)
        }.onFailure { ZalesLog.warn(ZalesLog.TAG_TUNNEL, "widget could not reach the tunnel", it) }
    }

    public companion object {
        private const val ACTION_TOGGLE = "io.github.nkvas1.zales.WIDGET_TOGGLE"
        private const val REQUEST = 41

        /** Called by the tunnel on every state change, so the plate is never stale. */
        public fun refresh(context: Context, state: TunnelState) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, ZalesWidget::class.java))
            }.getOrNull() ?: return
            if (ids.isEmpty()) return
            draw(context, state, manager, ids)
        }

        private fun draw(context: Context, state: TunnelState, manager: AppWidgetManager, ids: IntArray) {
            val views = RemoteViews(context.packageName, R.layout.widget_plate).apply {
                setTextViewText(R.id.widget_word, context.getString(word(state)))
                setInt(R.id.widget_word, "setTextColor", colour(state))
                setInt(R.id.widget_mark, "setColorFilter", colour(state))
                setOnClickPendingIntent(R.id.widget_root, toggleIntent(context))
                setContentDescription(R.id.widget_root, context.getString(word(state)))
            }
            runCatching { manager.updateAppWidget(ids, views) }
                .onFailure { ZalesLog.warn(ZalesLog.TAG_TUNNEL, "could not draw the widget", it) }
        }

        private fun toggleIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST,
            Intent(context, ZalesWidget::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun word(state: TunnelState): Int = when (state) {
            TunnelState.Idle -> R.string.tile_closed
            TunnelState.Preparing, is TunnelState.Probing -> R.string.tile_searching
            is TunnelState.Connected -> R.string.tile_open
            is TunnelState.Degraded, is TunnelState.Reconnecting -> R.string.tile_detour
            is TunnelState.Failed -> R.string.tile_failed
            TunnelState.Stopping -> R.string.tile_closing
        }

        /** Lamp when the current flows, rust when it did not, bone the rest of the time. */
        private fun colour(state: TunnelState): Int = when {
            state is TunnelState.Connected -> LAMP
            state is TunnelState.Failed -> RUST
            state.isEngaged -> BONE
            else -> RIME
        }

        private const val LAMP = 0xFFF0D9A8.toInt()
        private const val BONE = 0xFFE6DCC6.toInt()
        private const val RIME = 0xFF849EA2.toInt()
        private const val RUST = 0xFF93331E.toInt()
    }
}
