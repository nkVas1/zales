// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.service

import android.os.Parcel
import android.os.Parcelable
import io.github.nkvas1.zales.tunnel.api.DegradeReason
import io.github.nkvas1.zales.tunnel.api.FailureCode
import io.github.nkvas1.zales.tunnel.api.StrategyId
import io.github.nkvas1.zales.tunnel.api.Traffic
import io.github.nkvas1.zales.tunnel.api.TunnelFailure
import io.github.nkvas1.zales.tunnel.api.TunnelState

/**
 * [TunnelState] flattened for the trip across the AIDL boundary.
 *
 * Written by hand rather than generated: the wire format is small, and keeping
 * it explicit makes it obvious that **no secret ever crosses** — no key, no
 * server address, only a state, a strategy name and counters.
 */
public data class TunnelStatus(
    val phase: Phase,
    val strategy: String?,
    val sinceEpochMs: Long,
    val latencyMs: Int,
    val uploadedBytes: Long,
    val downloadedBytes: Long,
    val degradeReason: DegradeReason?,
    val failureCode: FailureCode?,
    val failureDetail: String?,
    val attempt: Int,
) : Parcelable {

    public enum class Phase { IDLE, PREPARING, PROBING, CONNECTED, DEGRADED, RECONNECTING, FAILED, STOPPING }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(phase.ordinal)
        dest.writeString(strategy)
        dest.writeLong(sinceEpochMs)
        dest.writeInt(latencyMs)
        dest.writeLong(uploadedBytes)
        dest.writeLong(downloadedBytes)
        dest.writeInt(degradeReason?.ordinal ?: ABSENT)
        dest.writeInt(failureCode?.ordinal ?: ABSENT)
        dest.writeString(failureDetail)
        dest.writeInt(attempt)
    }

    public fun toState(): TunnelState = when (phase) {
        Phase.IDLE -> TunnelState.Idle
        Phase.PREPARING -> TunnelState.Preparing
        Phase.STOPPING -> TunnelState.Stopping
        Phase.PROBING -> TunnelState.Probing(attempt, strategy?.let(::StrategyId))
        Phase.RECONNECTING -> TunnelState.Reconnecting(attempt)
        Phase.CONNECTED -> TunnelState.Connected(
            sinceEpochMs = sinceEpochMs,
            strategy = StrategyId(strategy ?: UNKNOWN_STRATEGY),
            latencyMs = latencyMs.takeIf { it >= 0 },
            traffic = Traffic(uploadedBytes, downloadedBytes),
        )
        Phase.DEGRADED -> TunnelState.Degraded(
            sinceEpochMs = sinceEpochMs,
            strategy = StrategyId(strategy ?: UNKNOWN_STRATEGY),
            reason = degradeReason ?: DegradeReason.PROBES_FAILING,
            traffic = Traffic(uploadedBytes, downloadedBytes),
        )
        Phase.FAILED -> TunnelState.Failed(
            TunnelFailure(failureCode ?: FailureCode.INT_01, failureDetail.orEmpty()),
        )
    }

    public companion object {
        private const val ABSENT = -1
        private const val UNKNOWN_STRATEGY = "s?"

        @JvmField
        public val CREATOR: Parcelable.Creator<TunnelStatus> = object : Parcelable.Creator<TunnelStatus> {
            override fun createFromParcel(source: Parcel): TunnelStatus = TunnelStatus(
                phase = Phase.entries[source.readInt()],
                strategy = source.readString(),
                sinceEpochMs = source.readLong(),
                latencyMs = source.readInt(),
                uploadedBytes = source.readLong(),
                downloadedBytes = source.readLong(),
                degradeReason = source.readInt().let { if (it == ABSENT) null else DegradeReason.entries[it] },
                failureCode = source.readInt().let { if (it == ABSENT) null else FailureCode.entries[it] },
                failureDetail = source.readString(),
                attempt = source.readInt(),
            )

            override fun newArray(size: Int): Array<TunnelStatus?> = arrayOfNulls(size)
        }

        public fun of(state: TunnelState): TunnelStatus {
            val idle = TunnelStatus(
                phase = Phase.IDLE,
                strategy = null,
                sinceEpochMs = 0,
                latencyMs = -1,
                uploadedBytes = 0,
                downloadedBytes = 0,
                degradeReason = null,
                failureCode = null,
                failureDetail = null,
                attempt = 0,
            )
            return when (state) {
                TunnelState.Idle -> idle
                TunnelState.Preparing -> idle.copy(phase = Phase.PREPARING)
                TunnelState.Stopping -> idle.copy(phase = Phase.STOPPING)
                is TunnelState.Probing -> idle.copy(
                    phase = Phase.PROBING,
                    attempt = state.attempt,
                    strategy = state.strategy?.value,
                )
                is TunnelState.Reconnecting -> idle.copy(phase = Phase.RECONNECTING, attempt = state.attempt)
                is TunnelState.Connected -> idle.copy(
                    phase = Phase.CONNECTED,
                    strategy = state.strategy.value,
                    sinceEpochMs = state.sinceEpochMs,
                    latencyMs = state.latencyMs ?: -1,
                    uploadedBytes = state.traffic.uploadedBytes,
                    downloadedBytes = state.traffic.downloadedBytes,
                )
                is TunnelState.Degraded -> idle.copy(
                    phase = Phase.DEGRADED,
                    strategy = state.strategy.value,
                    sinceEpochMs = state.sinceEpochMs,
                    degradeReason = state.reason,
                    uploadedBytes = state.traffic.uploadedBytes,
                    downloadedBytes = state.traffic.downloadedBytes,
                )
                is TunnelState.Failed -> idle.copy(
                    phase = Phase.FAILED,
                    failureCode = state.failure.code,
                    failureDetail = state.failure.detail,
                )
            }
        }
    }
}
