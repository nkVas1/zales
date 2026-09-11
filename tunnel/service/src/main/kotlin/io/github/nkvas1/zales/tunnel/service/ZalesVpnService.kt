// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.service

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
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
import io.github.nkvas1.zales.tunnel.autopilot.Backoff
import io.github.nkvas1.zales.tunnel.autopilot.Choice
import io.github.nkvas1.zales.tunnel.autopilot.NetworkFingerprint
import io.github.nkvas1.zales.tunnel.autopilot.NetworkFingerprinter
import io.github.nkvas1.zales.tunnel.autopilot.StoredNetworkProfiles
import io.github.nkvas1.zales.tunnel.autopilot.TrafficSample
import io.github.nkvas1.zales.tunnel.autopilot.Uplink
import io.github.nkvas1.zales.tunnel.autopilot.UplinkProbe
import io.github.nkvas1.zales.tunnel.autopilot.Verdict
import io.github.nkvas1.zales.tunnel.autopilot.Watchdog
import io.github.nkvas1.zales.tunnel.diagnostics.Environment
import io.github.nkvas1.zales.tunnel.diagnostics.PathCheck
import io.github.nkvas1.zales.tunnel.diagnostics.Survival
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
import kotlinx.coroutines.flow.collect
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
    private val backoff = Backoff()

    private lateinit var notification: TunnelNotification
    private lateinit var keys: KeyRepository
    private lateinit var autopilot: Autopilot
    private lateinit var fingerprinter: NetworkFingerprinter
    private lateinit var uplink: UplinkProbe
    private lateinit var profiles: StoredNetworkProfiles
    private lateinit var survival: Survival
    private lateinit var pathCheck: PathCheck

    private var tun: ParcelFileDescriptor? = null
    private var trafficJob: Job? = null
    private var trafficBase = Traffic.Zero
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var checkJob: Job? = null

    /** Held so a recovery can start another race without waking the key store. */
    private var activeKey: AccessKey? = null
    private var activeNetwork: NetworkFingerprint = NetworkFingerprint.Unknown
    private var resolved: Pair<String, String>? = null
    private var attempt = 1

    /** Races since the tunnel last made progress; the give-up budget counts these. */
    private var races = 0

    /**
     * Bumped whenever the tunnel changes hands. Recovery can sit in a backoff
     * for two minutes, and when it wakes the person may have closed the tunnel
     * and opened it again; this is how that work knows it is stale.
     */
    private var session = 0

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
        override fun diagnose() = this@ZalesVpnService.diagnose()
        override fun cancelDiagnosis() {
            checkJob?.cancel()
        }
    }

    override fun onCreate() {
        super.onCreate()
        notification = TunnelNotification(this)
        notification.createChannel()
        keys = KeyRepository(File(noBackupFilesDir, KEY_STORE_FILE), KeystoreBlobCipher())
        fingerprinter = NetworkFingerprinter(this)
        uplink = UplinkProbe(this)
        profiles = StoredNetworkProfiles(this)
        autopilot = Autopilot(engine = engine, profiles = profiles, resolver = cachingResolver())
        survival = Survival(this)
        survival.openLedger()
        pathCheck = PathCheck(
            engine = engine,
            uplink = { uplink.current() },
            background = { survival.verdict() },
            environment = { environment() },
        )
        watchNetwork()
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
        session++
        scope.launch { shutdown(TunnelState.Failed(TunnelFailure(FailureCode.SYS_02, "vpn revoked by the system"))) }
    }

    override fun onDestroy() {
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        }
        listeners.kill()
        scope.cancel()
        super.onDestroy()
    }

    // ── The state machine ──────────────────────────────────────────────

    private fun open() {
        val mine = ++session
        scope.launch {
            if (state.value.isBusy) return@launch
            update(TunnelState.Preparing)

            val key = loadKey() ?: return@launch
            activeKey = key
            activeNetwork = fingerprinter.current()
            attempt = 1
            races = 0
            resolved = null
            backoff.reset()

            if (!uplinkReady()) return@launch

            // Nothing touches the interface until a real request has come back
            // through the proxy: a tunnel that opens and silently goes nowhere
            // is the worst outcome of all.
            update(TunnelState.Probing(attempt = attempt, strategy = null))
            when (val choice = autopilot.choose(key, activeNetwork)) {
                is Choice.None -> if (session == mine) fail(FailureCode.SRV_03, choice.detail)
                is Choice.Found -> if (session == mine) engage(key, choice)
            }
        }
    }

    /**
     * Rules out the ordinary explanations before the tunnel is blamed.
     *
     * Racing six strategies against a phone in aeroplane mode produces six
     * timeouts and one useless sentence; naming the aeroplane takes a moment
     * and the person fixes it themselves.
     */
    private suspend fun uplinkReady(): Boolean = when (uplink.current()) {
        Uplink.AIRPLANE -> {
            fail(FailureCode.NET_03, "airplane mode is on")
            false
        }

        Uplink.OFFLINE -> {
            fail(FailureCode.NET_01, "no active network")
            false
        }

        Uplink.CAPTIVE -> {
            fail(FailureCode.NET_02, "network is behind a captive portal")
            false
        }

        Uplink.READY -> true
    }

    /**
     * Raises the interface and hands its descriptor to the core.
     *
     * The descriptor deliberately outlives a restart of the core: when a
     * recovery swaps strategies underneath, the person's open connections and
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
     * What worked here is discredited, and another way in is found.
     */
    private suspend fun relearn(reason: DegradeReason) {
        val strategy = (state.value as? TunnelState.Connected)?.strategy ?: return
        update(TunnelState.Degraded(System.currentTimeMillis(), strategy, reason, currentDelta()))
        profiles.forget(activeNetwork)
        reroute()
    }

    /**
     * Finds another way through without ever taking the interface down.
     *
     * This is where the tunnel earns its keep: a failed race is not a failure
     * but a wait, and the waits grow. The loop gives up only when the person
     * would rather be told than kept waiting — and even then, coming back onto
     * a network starts it again.
     */
    private suspend fun reroute() {
        val key = activeKey ?: return
        val mine = session
        engine.stop()
        while (currentCoroutineContext().isActive && session == mine) {
            attempt++
            update(TunnelState.Reconnecting(attempt))

            // Off the network entirely: sleep instead of thrashing the radio.
            // The connectivity callback wakes this up the moment that changes.
            if (uplink.current() == Uplink.OFFLINE) return

            activeNetwork = fingerprinter.current()
            val choice = autopilot.choose(key, activeNetwork)
            if (session != mine) return
            if (choice is Choice.Found) {
                engage(key, choice)
                return
            }

            races++
            if (races >= GIVE_UP_AFTER) {
                fail(FailureCode.DPI_01, (choice as Choice.None).detail)
                return
            }
            delay(backoff.nextDelayMs())
        }
    }

    private fun close() {
        session++
        scope.launch { shutdown(TunnelState.Idle) }
    }

    private suspend fun shutdown(finalState: TunnelState) {
        if (state.value == TunnelState.Idle) return
        update(TunnelState.Stopping)
        trafficJob?.cancel()
        engine.stop()
        engine.setProtector(null)
        closeDescriptor()
        survival.markStopped()
        activeKey = null
        update(finalState)
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (finalState !is TunnelState.Failed) stopSelf()
    }

    // ── The network underneath ─────────────────────────────────────────

    /**
     * A phone walks out of Wi-Fi range several times a day. That is not a
     * failure, it is a pause — and the far side of it is usually a different
     * network, where a different way through may be needed.
     */
    private fun watchNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { networkArrived() }
            }

            override fun onLost(network: Network) {
                scope.launch { networkLeft() }
            }
        }
        if (runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess) {
            networkCallback = callback
        }
    }

    private suspend fun networkArrived() {
        if (activeKey == null) return
        when (val current = state.value) {
            // Waiting it out, or asleep in a backoff that is now pointless.
            is TunnelState.Reconnecting -> startOver()

            // Still nominally up, but on a different network than the strategy
            // was chosen for. That strategy is not discredited — it simply may
            // not be the right one here.
            is TunnelState.Connected -> if (fingerprinter.current() != activeNetwork) {
                ZalesLog.info(ZalesLog.TAG_TUNNEL, "network changed underneath a live tunnel")
                trafficJob?.cancel()
                val degraded = TunnelState.Degraded(
                    sinceEpochMs = current.sinceEpochMs,
                    strategy = current.strategy,
                    reason = DegradeReason.STALLED,
                    traffic = Traffic.Zero,
                )
                update(degraded)
                startOver()
            }

            // The person was told there was no network. There is one now.
            is TunnelState.Failed -> if (current.failure.code in NETWORK_FAILURES) {
                ZalesLog.info(ZalesLog.TAG_TUNNEL, "network came back, opening again")
                open()
            }

            else -> Unit
        }
    }

    /** A new network deserves a clean slate: fresh address, fresh budget, fresh waits. */
    private suspend fun startOver() {
        resolved = null
        races = 0
        backoff.reset()
        session++
        reroute()
    }

    private suspend fun networkLeft() {
        if (uplink.current() != Uplink.OFFLINE) return
        if (state.value !is TunnelState.Connected && state.value !is TunnelState.Degraded) return
        ZalesLog.info(ZalesLog.TAG_TUNNEL, "network gone, holding the interface open")
        trafficJob?.cancel()
        attempt++
        update(TunnelState.Reconnecting(attempt))
    }

    // ── Keeping it alive ───────────────────────────────────────────────

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
        survival.markRunning()
        trafficBase = currentTraffic()
        watchdog.reset()
        backoff.reset()
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
        survival.markStopped()
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

    // ── The path check ─────────────────────────────────────────────────

    /**
     * Runs the ladder here rather than in the interface process.
     *
     * Two reasons, both hard: the key never leaves this process, and only a
     * `VpnService` can keep its own sockets out of a tunnel that may be up
     * while the check is running.
     */
    private fun diagnose() {
        checkJob?.cancel()
        val key = activeKey
        checkJob = scope.launch {
            val subject = key ?: runCatching { keys.activeKey() }.getOrNull() ?: return@launch
            pathCheck.run(subject).collect { diagnosis ->
                broadcast(DiagnosisStatus.of(diagnosis))
                // A check that ends in a diagnosis about this phone has told
                // the person something they can act on; the tally starts again.
                if (diagnosis.finished && diagnosis.verdict == null) survival.forgive()
            }
        }
    }

    private fun environment(): Environment = Environment(
        appVersion = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty(),
        androidVersion = Build.VERSION.RELEASE.orEmpty(),
        deviceModel = Build.MODEL.orEmpty(),
        coreVersion = engine.version,
        network = activeNetwork.id,
    )

    private fun broadcast(diagnosis: DiagnosisStatus) {
        val count = listeners.beginBroadcast()
        repeat(count) { index ->
            runCatching { listeners.getBroadcastItem(index).onDiagnosis(diagnosis) }
        }
        listeners.finishBroadcast()
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

        /**
         * After this many races the person is told rather than kept waiting.
         * With the backoff between them that is around two minutes of trying.
         */
        private const val GIVE_UP_AFTER = 6

        /** Failures that a returning network can simply undo. */
        private val NETWORK_FAILURES = setOf(FailureCode.NET_01, FailureCode.NET_02, FailureCode.NET_03)
    }
}
