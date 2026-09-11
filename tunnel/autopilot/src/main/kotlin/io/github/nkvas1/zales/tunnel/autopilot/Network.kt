// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.autopilot

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.telephony.TelephonyManager
import io.github.nkvas1.zales.tunnel.api.StrategyId
import java.security.MessageDigest

/**
 * A stable name for "this network", so that what worked here can be tried here
 * first next time.
 *
 * Only a hash is ever stored. Nothing that identifies a place — no network name,
 * no router address — is written to disk (docs/CONNECTIVITY.md §4).
 */
@JvmInline
public value class NetworkFingerprint(public val id: String) {
    public companion object {
        public val Unknown: NetworkFingerprint = NetworkFingerprint("unknown")
    }
}

/** What worked where. */
public interface NetworkProfiles {
    public fun remembered(network: NetworkFingerprint): StrategyId?
    public fun remember(network: NetworkFingerprint, strategy: StrategyId)
    public fun forget(network: NetworkFingerprint)
}

/**
 * Works out which network the phone is on without asking for a single extra
 * permission.
 *
 * Reading a Wi-Fi network's name would require location access, which is far
 * too much to ask for a convenience. The default gateway and resolvers identify
 * a network just as well for this purpose, and need nothing beyond the network
 * state permission the tunnel already holds.
 */
public class NetworkFingerprinter(context: Context) {

    private val application = context.applicationContext

    public fun current(): NetworkFingerprint {
        val manager = application.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkFingerprint.Unknown
        val network = manager.activeNetwork ?: return NetworkFingerprint("offline")
        val capabilities = manager.getNetworkCapabilities(network) ?: return NetworkFingerprint.Unknown
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifi(manager, network)
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellular()
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkFingerprint("ethernet")
            else -> NetworkFingerprint.Unknown
        }
    }

    private fun wifi(manager: ConnectivityManager, network: android.net.Network): NetworkFingerprint {
        val properties = manager.getLinkProperties(network)
        val gateway = properties?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress.orEmpty()
        val resolvers = properties?.dnsServers?.joinToString(",") { it.hostAddress.orEmpty() }.orEmpty()
        return NetworkFingerprint("wifi:" + digest("$gateway|$resolvers"))
    }

    private fun cellular(): NetworkFingerprint {
        // MCC and MNC together: the operator and country, and nothing narrower.
        val operator = runCatching {
            application.getSystemService(TelephonyManager::class.java)?.networkOperator
        }.getOrNull().orEmpty()
        return NetworkFingerprint("cell:$operator")
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .take(DIGEST_BYTES)
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val DIGEST_BYTES = 6
    }
}

/**
 * What the phone's own connection is like, before the tunnel is blamed for it.
 *
 * Half of all "the VPN is broken" is the phone being off the network, in
 * aeroplane mode, or behind a hotel's sign-in page. Each of those has its own
 * sentence and its own thing to do, and none of them is worth a race.
 */
public enum class Uplink {
    /** Aeroplane mode: a single switch explains everything. */
    AIRPLANE,

    /** No network at all. */
    OFFLINE,

    /** Connected, but the system says the internet behind it is not real yet. */
    CAPTIVE,

    /** Nothing in the way. */
    READY,
}

/** Reads the state of the phone's own connection. */
public class UplinkProbe(context: Context) {

    private val application = context.applicationContext

    public fun current(): Uplink {
        if (airplaneMode()) return Uplink.AIRPLANE
        val manager = application.getSystemService(ConnectivityManager::class.java) ?: return Uplink.READY
        val network = manager.activeNetwork ?: return Uplink.OFFLINE
        val capabilities = manager.getNetworkCapabilities(network) ?: return Uplink.OFFLINE
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return Uplink.OFFLINE
        // Only Android's own verdict counts here. A network that has merely not
        // been validated *yet* is the normal state for the first second after
        // joining one, and telling someone to open a sign-in page that does not
        // exist is worse than one wasted race.
        val portal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        return if (portal) Uplink.CAPTIVE else Uplink.READY
    }

    private fun airplaneMode(): Boolean = runCatching {
        Settings.Global.getInt(application.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    }.getOrDefault(false)
}

/**
 * Remembers the winning strategy per network, and forgets it when it goes stale:
 * what worked six months ago says nothing about a network that has since been
 * re-filtered.
 */
public class StoredNetworkProfiles(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val staleAfterMs: Long = STALE_AFTER_MS,
) : NetworkProfiles {

    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun remembered(network: NetworkFingerprint): StrategyId? {
        val stored = preferences.getString(network.id, null) ?: return null
        val separator = stored.lastIndexOf(SEPARATOR)
        if (separator <= 0) return null
        val rememberedAt = stored.substring(separator + 1).toLongOrNull() ?: return null
        if (clock() - rememberedAt > staleAfterMs) {
            forget(network)
            return null
        }
        return runCatching { StrategyId(stored.substring(0, separator)) }.getOrNull()
    }

    override fun remember(network: NetworkFingerprint, strategy: StrategyId) {
        preferences.edit().putString(network.id, "${strategy.value}$SEPARATOR${clock()}").apply()
    }

    override fun forget(network: NetworkFingerprint) {
        preferences.edit().remove(network.id).apply()
    }

    private companion object {
        const val FILE = "zales.networks"
        const val SEPARATOR = '|'

        /** Long enough to cover a holiday, short enough to re-learn a changed network. */
        const val STALE_AFTER_MS = 30L * 24 * 60 * 60 * 1000
    }
}
