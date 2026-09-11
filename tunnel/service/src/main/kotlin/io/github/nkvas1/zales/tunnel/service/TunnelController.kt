// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import io.github.nkvas1.zales.common.ZalesLog
import io.github.nkvas1.zales.tunnel.api.TunnelState
import io.github.nkvas1.zales.tunnel.diagnostics.Diagnosis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/**
 * The tunnel as the interface sees it: a state flow and three verbs.
 *
 * AIDL stops here. Nothing above this class knows there is a second process,
 * and nothing below it knows there is a screen.
 */
public class TunnelController(private val context: Context) {

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Idle)
    public val state: StateFlow<TunnelState> = _state.asStateFlow()

    private val _diagnosis = MutableStateFlow<Diagnosis?>(null)

    /** The path check as it runs, or null when none has been asked for. */
    public val diagnosis: StateFlow<Diagnosis?> = _diagnosis.asStateFlow()

    private var service: ITunnelService? = null

    /** A check asked for before the binding arrived; binding is asynchronous. */
    private var checkWanted = false

    private val callback = object : ITunnelCallback.Stub() {
        override fun onStatus(status: TunnelStatus) {
            _state.value = status.toState()
        }

        override fun onDiagnosis(diagnosis: DiagnosisStatus) {
            _diagnosis.value = diagnosis.toDiagnosis()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val remote = ITunnelService.Stub.asInterface(binder)
            service = remote
            runCatching { remote.register(callback) }
                .onSuccess { _state.value = it.toState() }
                .onFailure { ZalesLog.warn(ZalesLog.TAG_UI, "could not register for tunnel updates", it) }
            if (checkWanted) {
                checkWanted = false
                runCatching { remote.diagnose() }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The tunnel process died. Say so rather than freezing on a stale state.
            service = null
            _state.value = TunnelState.Idle
            ZalesLog.warn(ZalesLog.TAG_UI, "tunnel process disconnected")
        }
    }

    /**
     * Emits tunnel state for as long as it is collected, binding on the way in
     * and unbinding on the way out.
     *
     * Registration happens when the binding actually arrives, not here: binding
     * is asynchronous, so anything registered synchronously would miss.
     */
    public fun observe(): Flow<TunnelState> = _state
        .onStart { bind() }
        .onCompletion { unbind() }

    /**
     * The system consent dialog, or `null` when consent is already granted.
     * Must be shown from an Activity before [open] will do anything.
     */
    public fun consentIntent(): Intent? = VpnService.prepare(context)

    public fun open() {
        if (service != null) {
            runCatching { service?.open() }.onSuccess { return }
        }
        context.startForegroundService(intent(ZalesVpnService.ACTION_OPEN))
        bind()
    }

    public fun close() {
        runCatching { service?.close() }
            .onFailure { context.startService(intent(ZalesVpnService.ACTION_CLOSE)) }
    }

    public fun retry() {
        runCatching { service?.retry() }.onFailure { open() }
    }

    /**
     * Asks the tunnel process to walk the probe ladder.
     *
     * Binding first, because a check is most often wanted exactly when nothing
     * is running and there is therefore nothing bound.
     */
    public fun diagnose() {
        _diagnosis.value = Diagnosis.starting()
        val remote = service
        if (remote == null) {
            checkWanted = true
            bind()
            return
        }
        runCatching { remote.diagnose() }
            .onFailure { ZalesLog.warn(ZalesLog.TAG_UI, "could not start the path check", it) }
    }

    public fun forgetDiagnosis() {
        checkWanted = false
        runCatching { service?.cancelDiagnosis() }
        _diagnosis.value = null
    }

    public fun bind() {
        runCatching {
            context.bindService(intent(action = null), connection, Context.BIND_AUTO_CREATE)
        }.onFailure { ZalesLog.warn(ZalesLog.TAG_UI, "could not bind to the tunnel", it) }
    }

    public fun unbind() {
        runCatching { context.unbindService(connection) }
        service = null
    }

    private fun intent(action: String?): Intent =
        Intent(context, ZalesVpnService::class.java).apply { action?.let(::setAction) }
}
