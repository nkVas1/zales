// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.words

import androidx.annotation.StringRes
import io.github.nkvas1.zales.parsing.ParseFailure
import io.github.nkvas1.zales.tunnel.api.FailureCode
import io.github.nkvas1.zales.tunnel.api.TunnelState

/**
 * Turns machine states into the words a person actually reads.
 *
 * One place, so the failure taxonomy, the notification and the diagnostics
 * screen can never say three different things about the same event
 * (docs/DIAGNOSTICS.md §2).
 */
public object Words {

    /** The single large word above the switch. */
    @StringRes
    public fun stateWord(state: TunnelState): Int = when (state) {
        TunnelState.Idle -> R.string.state_closed
        TunnelState.Preparing, is TunnelState.Probing -> R.string.state_searching
        is TunnelState.Connected -> R.string.state_open
        is TunnelState.Degraded -> R.string.state_narrowed
        is TunnelState.Reconnecting -> R.string.state_detour
        is TunnelState.Failed -> R.string.state_failed
        TunnelState.Stopping -> R.string.state_closing
    }

    /** What happened, in one sentence, with no error code in sight. */
    @StringRes
    public fun sentence(code: FailureCode): Int = when (code) {
        FailureCode.NET_01 -> R.string.failure_net_01
        FailureCode.NET_02 -> R.string.failure_net_02
        FailureCode.NET_03 -> R.string.failure_net_03
        FailureCode.SYS_01 -> R.string.failure_sys_01
        FailureCode.SYS_02 -> R.string.failure_sys_02
        FailureCode.SYS_03 -> R.string.failure_sys_03
        FailureCode.SYS_04 -> R.string.failure_sys_04
        FailureCode.KEY_01 -> R.string.failure_key_01
        FailureCode.KEY_02 -> R.string.failure_key_02
        FailureCode.KEY_03 -> R.string.failure_key_03
        FailureCode.KEY_04 -> R.string.failure_key_04
        FailureCode.KEY_05 -> R.string.failure_key_05
        FailureCode.SRV_01 -> R.string.failure_srv_01
        FailureCode.SRV_02 -> R.string.failure_srv_02
        FailureCode.SRV_03 -> R.string.failure_srv_03
        FailureCode.SRV_04 -> R.string.failure_srv_04
        FailureCode.SRV_05 -> R.string.failure_srv_05
        FailureCode.SRV_06 -> R.string.failure_srv_06
        FailureCode.DPI_01 -> R.string.failure_dpi_01
        FailureCode.DPI_02 -> R.string.failure_dpi_02
        FailureCode.DPI_03 -> R.string.failure_dpi_03
        FailureCode.DPI_04 -> R.string.failure_dpi_04
        FailureCode.INT_01 -> R.string.failure_int_01
        FailureCode.INT_02 -> R.string.failure_int_02
        FailureCode.INT_03 -> R.string.failure_int_03
    }

    /**
     * The one thing to offer. `null` where the app heals itself and asking the
     * person to do anything would be noise.
     */
    public fun action(code: FailureCode): FailureAction? = when (code) {
        FailureCode.NET_01 -> FailureAction.OpenNetworkSettings
        FailureCode.NET_02 -> FailureAction.OpenCaptivePortal
        FailureCode.NET_03 -> FailureAction.OpenAirplaneSettings
        FailureCode.SYS_01 -> FailureAction.AskPermissionAgain
        FailureCode.SYS_02 -> FailureAction.ShowOtherVpn
        FailureCode.SYS_03, FailureCode.SYS_04 -> FailureAction.WalkThroughBatterySettings
        FailureCode.KEY_01, FailureCode.KEY_02 -> FailureAction.PasteAgain
        FailureCode.KEY_03 -> FailureAction.Retry
        FailureCode.KEY_04 -> FailureAction.ShowDetails
        FailureCode.KEY_05 -> FailureAction.PasteKey
        FailureCode.SRV_01 -> FailureAction.Retry
        FailureCode.SRV_02 -> FailureAction.SendReport
        FailureCode.SRV_03 -> FailureAction.Retry
        FailureCode.SRV_04, FailureCode.SRV_05 -> FailureAction.RequestNewKey
        FailureCode.SRV_06 -> FailureAction.ShowDetails
        FailureCode.DPI_01, FailureCode.DPI_04 -> FailureAction.SendReport
        // Self-healing: saying anything here would only invite interference.
        FailureCode.DPI_02, FailureCode.DPI_03 -> null
        FailureCode.INT_01 -> FailureAction.Retry
        FailureCode.INT_02 -> FailureAction.OpenDownloads
        FailureCode.INT_03 -> FailureAction.PasteAgain
    }

    /** Why a pasted key could not be read. */
    @StringRes
    public fun parseSentence(failure: ParseFailure): Int = when (failure) {
        ParseFailure.EMPTY -> R.string.parse_empty
        ParseFailure.UNRECOGNIZED -> R.string.parse_unrecognized
        ParseFailure.INCOMPLETE -> R.string.parse_incomplete
        ParseFailure.UNSUPPORTED_PROTOCOL -> R.string.parse_unsupported
    }
}

/** The single offered action, with the label the button carries. */
public enum class FailureAction(@param:StringRes public val label: Int) {
    OpenNetworkSettings(R.string.action_open_network_settings),
    OpenCaptivePortal(R.string.action_open_portal),
    OpenAirplaneSettings(R.string.action_disable_airplane),
    AskPermissionAgain(R.string.action_grant_permission),
    ShowOtherVpn(R.string.action_show_other_vpn),
    WalkThroughBatterySettings(R.string.action_walk_through),
    PasteAgain(R.string.action_paste_again),
    PasteKey(R.string.action_paste_key),
    Retry(R.string.action_try_again),
    RequestNewKey(R.string.action_request_new_key),
    SendReport(R.string.action_send_report),
    ShowDetails(R.string.action_show_details),
    OpenDownloads(R.string.action_open_downloads),
}
