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
import io.github.nkvas1.zales.tunnel.api.DegradeReason
import io.github.nkvas1.zales.tunnel.api.EngineResult
import io.github.nkvas1.zales.tunnel.api.FailureCode
import io.github.nkvas1.zales.tunnel.api.RoutingPolicy
import io.github.nkvas1.zales.tunnel.api.StrategyId
import io.github.nkvas1.zales.tunnel.api.Traffic
import io.github.nkvas1.zales.tunnel.api.TunSettings
import io.github.nkvas1.zales.tunnel.api.TunnelFailure
import io.github.nkvas1.zales.tunnel.api.TunnelState
import io.github.nkvas1.zales.tunnel.autopilot.AddressResolver
import io.github.nkvas1.zales.tunnel.autopilot.Autopilot
import io.github.nkvas1.zales.tunnel.autopilot.Choice
import io.github.nkvas1.zales.tunnel.autopilot.NetworkFingerprint
import io.github.nkvas1.zales.tunnel.autopilot.NetworkFingerprinter
import io.github.nkvas1.zales.tunnel.autopilot.StoredNetworkProfiles
import io.github.nkvas1.zales.tunnel.autopilot.TrafficSample
import io.github.nkvas1.zales.tunnel.autopilot.Verdict
import io.github.nkvas1.zales.tunnel.autopilot.Watchdog
import io.github.nkvas1.zales.tunnel.xray.XrayConfigBuilder
import io.github.nkvas1.zales.tunnel.xray.engine.XrayEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress

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
    private val watchdog = Watchdog()

    private lateinit var notification: TunnelNotification
    private lateinit var keys: KeyRepository
    private lateinit var autopilot: Autopilot
    private lateinit var fingerprinter: NetworkFingerprinter
    private lateinit var profiles: StoredNetworkProfiles

    private var tun: ParcelFileDescriptor? = null
    private var trafficJob: Job? = null
    private var trafficBase = Traffic.Zero

    /** Held so the watchdog can start another race without waking the key store. */
    private var activeKey: AccessKey? = null
    private var activeNetwork: NetworkFingerprint = NetworkFingerprint.Unknown
    private var resolved: Pair<String, String>? = null
    private var attempt = 1

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
        fingerprinter = NetworkFingerprinter(this)
        profiles = StoredNetworkProfiles(this)
        autopilot = Autopilot(engine = engine, profiles = profiles, resolver = cachingResolver())
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
            activeKey = key
            activeNetwork = fingerprinter.current()
            attempt = 1
            resolved = null

            // Nothing touches the interface until a real request has come back
            // through the proxy: a tunnel that opens and silently goes nowhere
            // is the worst outcome of all.
            update(TunnelState.Probing(attempt = attempt, strategy = null))
            when (val choice = autopilot.choose(key, activeNetwork)) {
                is Choice.None -> fail(FailureCode.SRV_03, choice.detail)
                is Choice.Found -> engage(key, choice)
            }
        }
    }

    /**
     * Raises the interface and hands its descriptor to the core.
     *
     * The descriptor deliberately outlives a restart of the core: when the
     * watchdog swaps strategies underneath, the person's open connections and
     * the system's VPN indicator both survive the change.
     */
    private suspend fun engage(key: AccessKey, choice: Choice.Found) {
        val routing = RoutingPolicy()
        val descriptor = tun ?: establish(routing)
        if (descriptor == null) {
            fail(FailureCode.SYS_01, "VpnService.establish returned null")
            return
        }
        tun = descriptor

        engine.setProtector { fd -> protect(fd) }
        val config = XrayConfigBuilder.forTunnel(key, choice.tactics, routing, TunSettings())
        when (val started = engine.start(config, descriptor.fd)) {
            is EngineResult.Ok -> connected(choice.tactics.id, choice.latencyMs)
            is EngineResult.Error -> {
                closeDescriptor()
                val code = if (started.nativeUnavailable) FailureCode.INT_02 else FailureCode.INT_01
                fail(code, started.message)
            }
        }
    }

    /**
     * The path went quiet in the way Russian filtering makes a path go quiet.
     * Forget what used to work here, find another way in, and swap the core
     * over underneath the interface without ever dropping it.
     */
    private suspend fun relearn(reason: DegradeReason) {
        val key = activeKey ?: return
        val strategy = (state.value as? TunnelState.Connected)?.strategy ?: return
        update(TunnelState.Degraded(System.currentTimeMillis(), strategy, reason, currentDelta()))

        // A strategy is only discredited on the network it was chosen for. If
        // the phone has meanwhile moved to another network, that strategy may
        // still be the right one to come back to.
        val here = fingerprinter.current()
        if (here == activeNetwork) profiles.forget(activeNetwork) else activeNetwork = here

        attempt++
        update(TunnelState.Reconnecting(attempt))
        when (val choice = autopilot.choose(key, activeNetwork)) {
            is Choice.None -> fail(FailureCode.DPI_01, choice.detail)
            is Choice.Found -> {
                engine.stop()
                engage(key, choice)
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
        watchdog.reset()
        update(
            TunnelState.Connected(
                sinceEpochMs = System.currentTimeMillis(),
                strategy = strategy,
                latencyMs = latencyMs,
                traffic = Traffic.Zero,
            ),
        )
        trafficJob?.cancel()
        trafficJob = scope.launch { keepWatch() }
    }

    /** Publishes the counters and, more importantly, notices when they stop moving. */
    private suspend fun keepWatch() {
        while (currentCoroutineContext().isActive) {
            delay(TRAFFIC_POLL_MS)
            val delta = currentDelta()
            (state.value as? TunnelState.Connected)?.let { update(it.copy(traffic = delta)) }

            val sample = TrafficSample(delta.uploadedBytes, delta.downloadedBytes, System.currentTimeMillis())
            when (watchdog.observe(sample)) {
                // Suspicion alone is not worth telling anyone about: a slow page
                // looks exactly like this for a second or two.
                Verdict.HEALTHY, Verdict.SUSPICIOUS -> Unit
                Verdict.STALLED -> {
                    ZalesLog.warn(ZalesLog.TAG_TUNNEL, "traffic frozen, looking for another way through")
                    relearn(DegradeReason.STALLED)
                    return
                }
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

    private fun currentDelta(): Traffic {
        val now = currentTraffic()
        return Traffic(
            uploadedBytes = (now.uploadedBytes - trafficBase.uploadedBytes).coerceAtLeast(0),
            downloadedBytes = (now.downloadedBytes - trafficBase.downloadedBytes).coerceAtLeast(0),
        )
    }

    /**
     * Resolves the server's address once per session, outside the tunnel.
     *
     * Asking again while the path is frozen would simply hang, so the answer
     * from the first, healthy attempt is the one that is kept and reused by
     * every later race.
     */
    private fun cachingResolver(): AddressResolver = AddressResolver { host ->
        resolved?.takeIf { it.first == host }?.second ?: withContext(Dispatchers.IO) {
            runCatching { InetAddress.getAllByName(host).firstOrNull()?.hostAddress }
                .getOrNull()
                ?.also { resolved = host to it }
        }
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

        private const val TRAFFIC_POLL_MS = 2_000L
    }
}
