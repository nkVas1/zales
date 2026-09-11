// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.service

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteCallbackList
import io.github.nkvas1.zales.common.ZalesLog
import io.github.nkvas1.zales.model.AccessKey
import io.github.nkvas1.zales.storage.KeyRepository
import io.github.nkvas1.zales.storage.KeystoreBlobCipher
import io.github.nkvas1.zales.storage.StorageUnavailableException
import io.github.nkvas1.zales.tunnel.api.EngineResult
import io.github.nkvas1.zales.tunnel.api.FailureCode
import io.github.nkvas1.zales.tunnel.api.RoutingPolicy
import io.github.nkvas1.zales.tunnel.api.StrategyId
import io.github.nkvas1.zales.tunnel.api.Tactics
import io.github.nkvas1.zales.tunnel.api.Traffic
import io.github.nkvas1.zales.tunnel.api.TunSettings
import io.github.nkvas1.zales.tunnel.api.TunnelFailure
import io.github.nkvas1.zales.tunnel.api.TunnelState
import io.github.nkvas1.zales.tunnel.xray.XrayConfigBuilder
import io.github.nkvas1.zales.tunnel.xray.engine.XrayEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * The tunnel itself. Lives alone in the `:tunnel` process (ADR-0004), so a
 * fault in the native core cannot take the interface down with it.
 *
 * Work is serialised onto a single IO thread: the core is not thread-safe and
 * the state machine is much easier to trust when nothing races.
 */
public class ZalesVpnService : VpnService() {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val engine = XrayEngine()
    private val state = MutableStateFlow<TunnelState>(TunnelState.Idle)
    private val listeners = RemoteCallbackList<ITunnelCallback>()

    private lateinit var notification: TunnelNotification
    private lateinit var keys: KeyRepository

    private var tun: ParcelFileDescriptor? = null
    private var trafficJob: Job? = null
    private var trafficBase = Traffic.Zero

    private val binder = object : ITunnelService.Stub() {
        override fun register(callback: ITunnelCallback): TunnelStatus {
            listeners.register(callback)
            return TunnelStatus.of(state.value)
        }

        override fun unregister(callback: ITunnelCallback) {
            listeners.unregister(callback)
        }

        override fun open() = this@ZalesVpnService.open()
        override fun close() = this@ZalesVpnService.close()
        override fun retry() = this@ZalesVpnService.open()
    }

    override fun onCreate() {
        super.onCreate()
        notification = TunnelNotification(this)
        notification.createChannel()
        keys = KeyRepository(File(noBackupFilesDir, KEY_STORE_FILE), KeystoreBlobCipher())
        ZalesLog.info(ZalesLog.TAG_TUNNEL, "tunnel process started")
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android gives a service five seconds to show its notification.
        promoteToForeground()
        when (intent?.action) {
            ACTION_CLOSE -> close()
            else -> open()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        ZalesLog.warn(ZalesLog.TAG_TUNNEL, "VPN permission revoked, another app took the tunnel")
        scope.launch { shutdown(TunnelState.Failed(TunnelFailure(FailureCode.SYS_02, "vpn revoked by the system"))) }
    }

    override fun onDestroy() {
        listeners.kill()
        scope.cancel()
        super.onDestroy()
    }

    // ── The state machine ──────────────────────────────────────────────

    private fun open() {
        scope.launch {
            if (state.value.isBusy) return@launch
            update(TunnelState.Preparing)

            val key = loadKey() ?: return@launch
            val tactics = Tactics.Baseline
            val routing = RoutingPolicy()

            // Prove the server answers before touching the interface: a tunnel
            // that comes up and silently goes nowhere is the worst outcome.
            update(TunnelState.Probing(attempt = 1, strategy = tactics.id))
            val reach = engine.probe(listOf(XrayConfigBuilder.forProbe(key, tactics)), PROBE_URL, PROBE_TIMEOUT_MS)
                .firstOrNull()
            if (reach == null || !reach.success) {
                fail(FailureCode.SRV_03, reach?.error ?: "probe produced no result")
                return@launch
            }

            val descriptor = establish(routing)
            if (descriptor == null) {
                fail(FailureCode.SYS_01, "VpnService.establish returned null")
                return@launch
            }
            tun = descriptor

            engine.setProtector { fd -> protect(fd) }
            val config = XrayConfigBuilder.forTunnel(key, tactics, routing, TunSettings())
            when (val started = engine.start(config, descriptor.fd)) {
                is EngineResult.Ok -> connected(tactics.id, reach.delayMs.toInt())
                is EngineResult.Error -> {
                    closeDescriptor()
                    val code = if (started.nativeUnavailable) FailureCode.INT_02 else FailureCode.INT_01
                    fail(code, started.message)
                }
            }
        }
    }

    private fun close() {
        scope.launch { shutdown(TunnelState.Idle) }
    }

    private suspend fun shutdown(finalState: TunnelState) {
        if (state.value == TunnelState.Idle) return
        update(TunnelState.Stopping)
        trafficJob?.cancel()
        engine.stop()
        engine.setProtector(null)
        closeDescriptor()
        update(finalState)
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (finalState !is TunnelState.Failed) stopSelf()
    }

    private suspend fun loadKey(): AccessKey? = try {
        keys.activeKey() ?: run {
            fail(FailureCode.KEY_05, "no key stored")
            null
        }
    } catch (unavailable: StorageUnavailableException) {
        fail(FailureCode.INT_03, unavailable.message ?: "key store unavailable")
        null
    }

    private fun establish(routing: RoutingPolicy): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(SESSION_NAME)
            .setMtu(TunSettings.DEFAULT_MTU)
            .addAddress(TUN_IPV4, TUN_IPV4_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(TUN_DNS)
        // IPv6 is captured even when it is not routed onward, so that stray
        // traffic is contained by the engine instead of leaking around us.
        builder.addAddress(TUN_IPV6, TUN_IPV6_PREFIX).addRoute("::", 0)
        if (!routing.allowIpv6) {
            ZalesLog.debug(ZalesLog.TAG_TUNNEL) { "IPv6 captured and blocked inside the engine" }
        }
        builder.setBlocking(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        return runCatching { builder.establish() }.getOrElse { error ->
            ZalesLog.error(ZalesLog.TAG_TUNNEL, "could not establish the interface", error)
            null
        }
    }

    private fun connected(strategy: StrategyId, latencyMs: Int) {
        trafficBase = currentTraffic()
        update(
            TunnelState.Connected(
                sinceEpochMs = System.currentTimeMillis(),
                strategy = strategy,
                latencyMs = latencyMs,
                traffic = Traffic.Zero,
            ),
        )
        trafficJob?.cancel()
        trafficJob = scope.launch {
            while (isActive) {
                delay(TRAFFIC_POLL_MS)
                val connected = state.value as? TunnelState.Connected ?: continue
                val now = currentTraffic()
                update(
                    connected.copy(
                        traffic = Traffic(
                            uploadedBytes = (now.uploadedBytes - trafficBase.uploadedBytes).coerceAtLeast(0),
                            downloadedBytes = (now.downloadedBytes - trafficBase.downloadedBytes).coerceAtLeast(0),
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Everything this UID sends is the tunnel's own traffic: the app's sockets
     * are protected and therefore counted here, while app traffic inside the
     * tunnel is counted only once, as it leaves through the core.
     */
    private fun currentTraffic(): Traffic {
        val uid = Process.myUid()
        val up = TrafficStats.getUidTxBytes(uid).takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0
        val down = TrafficStats.getUidRxBytes(uid).takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0
        return Traffic(up, down)
    }

    private suspend fun fail(code: FailureCode, detail: String) {
        ZalesLog.warn(ZalesLog.TAG_TUNNEL, "tunnel failed: ${code.printable}")
        trafficJob?.cancel()
        engine.stop()
        engine.setProtector(null)
        closeDescriptor()
        update(TunnelState.Failed(TunnelFailure(code, detail)))
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun closeDescriptor() {
        runCatching { tun?.close() }
        tun = null
    }

    private fun update(next: TunnelState) {
        state.value = next
        notification.update(next)
        val status = TunnelStatus.of(next)
        val count = listeners.beginBroadcast()
        repeat(count) { index ->
            runCatching { listeners.getBroadcastItem(index).onStatus(status) }
        }
        listeners.finishBroadcast()
    }

    private fun promoteToForeground() {
        val current = notification.build(state.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                TunnelNotification.NOTIFICATION_ID,
                current,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(TunnelNotification.NOTIFICATION_ID, current)
        }
    }

    private val TunnelState.isBusy: Boolean
        get() = this !is TunnelState.Failed && this != TunnelState.Idle

    public companion object {
        public const val ACTION_OPEN: String = "io.github.nkvas1.zales.OPEN"
        public const val ACTION_CLOSE: String = "io.github.nkvas1.zales.CLOSE"

        private const val SESSION_NAME = "Zales"
        private const val KEY_STORE_FILE = "keys.bin"
        private const val TUN_IPV4 = "10.16.0.2"
        private const val TUN_IPV4_PREFIX = 30
        private const val TUN_IPV6 = "fd16:7a1e:5000::2"
        private const val TUN_IPV6_PREFIX = 126
        private const val TUN_DNS = "10.16.0.1"

        /** Answers 204 with an empty body, so a probe measures the path and nothing else. */
        private const val PROBE_URL = "https://cp.cloudflare.com/generate_204"
        private const val PROBE_TIMEOUT_MS = 8_000
        private const val TRAFFIC_POLL_MS = 2_000L
    }
}
