// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import io.github.nkvas1.zales.common.ZalesLog

/**
 * Puts the tunnel back up after a restart, if that is what was wanted.
 *
 * A phone reboots for reasons that have nothing to do with the person holding
 * it — an update at four in the morning, a flat battery. Coming back to a
 * closed switch they never touched is the app forgetting, and this is the one
 * place where forgetting would be felt every time.
 */
public class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in WAKING_ACTIONS) return
        if (!Wish(context).wantsOpen) return

        // Consent does not survive every kind of restart, and a service started
        // without it would only fail. Better to stay closed and let the person
        // throw the switch, which takes one touch.
        if (VpnService.prepare(context) != null) {
            ZalesLog.info(ZalesLog.TAG_TUNNEL, "not reopening after boot: consent is gone")
            return
        }

        ZalesLog.info(ZalesLog.TAG_TUNNEL, "reopening after boot")
        runCatching {
            context.startForegroundService(
                Intent(context, ZalesVpnService::class.java).setAction(ZalesVpnService.ACTION_OPEN),
            )
        }.onFailure { ZalesLog.warn(ZalesLog.TAG_TUNNEL, "could not reopen after boot", it) }
    }

    private companion object {
        val WAKING_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            // Sent after the app is updated in place, where the same reasoning
            // applies: nothing the person did should have closed the switch.
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
