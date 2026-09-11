// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.xray

import io.github.nkvas1.zales.model.AccessKey
import io.github.nkvas1.zales.model.Security
import io.github.nkvas1.zales.model.Transport
import io.github.nkvas1.zales.model.Transported
import io.github.nkvas1.zales.tunnel.api.EngineConfig
import io.github.nkvas1.zales.tunnel.api.RoutingPolicy
import io.github.nkvas1.zales.tunnel.api.Tactics
import io.github.nkvas1.zales.tunnel.api.TunSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds Xray-core configuration documents.
 *
 * A pure function of `(key, tactics, routing)`: no I/O, no clock, no platform.
 * That is what lets every combination the autopilot can produce be checked
 * without starting a core (docs/ARCHITECTURE.md §6).
 */
public object XrayConfigBuilder {

    public const val TAG_TUN_IN: String = "tun-in"
    public const val TAG_PROXY: String = "proxy"
    public const val TAG_DIRECT: String = "direct"
    public const val TAG_BLOCK: String = "block"
    public const val TAG_DNS_OUT: String = "dns-out"
    public const val TAG_FRAGMENT: String = "fragment"
    public const val TAG_DNS_INTERNAL: String = "dns-internal"

    /**
     * Resolver for domestic names when [RoutingPolicy.bypassDomestic] is on.
     * Reachable in Russia and returns domestic CDN addresses.
     */
    public const val DOMESTIC_RESOLVER: String = "77.88.8.8"

    /** Encrypted resolvers used through the tunnel, addressed by IP to avoid a bootstrap lookup. */
    public val TunnelResolvers: List<String> = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query")

    private val json = Json { prettyPrint = false }

    /** Full configuration for a running tunnel on a VpnService TUN descriptor. */
    public fun forTunnel(key: AccessKey, tactics: Tactics, routing: RoutingPolicy, tun: TunSettings): EngineConfig {
        val document = buildJsonObject {
            putJsonObject("log") { put("loglevel", "warning") }
            put("dns", dns(routing))
            putJsonArray("inbounds") { add(tunInbound(tun)) }
            putJsonArray("outbounds") {
                add(proxyOutbound(key, tactics))
                tactics.fragment?.let { add(fragmentOutbound(tactics)) }
                add(plainOutbound(TAG_DIRECT, "freedom"))
                add(plainOutbound(TAG_BLOCK, "blackhole"))
                add(plainOutbound(TAG_DNS_OUT, "dns"))
            }
            put("routing", routing(routing))
        }
        return EngineConfig(json.encodeToString(JsonObject.serializer(), document))
    }

    /**
     * Minimal configuration for latency probes: the proxy outbound (and its
     * fragment helper) only. No inbound, so nothing listens anywhere.
     */
    public fun forProbe(key: AccessKey, tactics: Tactics): EngineConfig {
        val document = buildJsonObject {
            putJsonObject("log") { put("loglevel", "none") }
            putJsonArray("outbounds") {
                add(proxyOutbound(key, tactics))
                tactics.fragment?.let { add(fragmentOutbound(tactics)) }
            }
        }
        return EngineConfig(json.encodeToString(JsonObject.serializer(), document))
    }

    private fun tunInbound(tun: TunSettings): JsonObject = buildJsonObject {
        put("tag", TAG_TUN_IN)
        put("protocol", "tun")
        put("port", 0)
        putJsonObject("settings") {
            put("name", "xray0")
            put("mtu", tun.mtu)
        }
        putJsonObject("sniffing") {
            put("enabled", true)
            putJsonArray("destOverride") {
                add("http")
                add("tls")
                add("quic")
            }
            // Route by the sniffed name but connect to the original IP.
            put("routeOnly", true)
        }
    }

    private fun plainOutbound(tag: String, protocol: String): JsonObject = buildJsonObject {
        put("tag", tag)
        put("protocol", protocol)
    }

    private fun proxyOutbound(key: AccessKey, tactics: Tactics): JsonObject = buildJsonObject {
        put("tag", TAG_PROXY)
        put("protocol", protocolName(key))
        put("settings", proxySettings(key, tactics))
        put("streamSettings", streamSettings(key, tactics))
        put("mux", mux(key, tactics))
    }

    private fun protocolName(key: AccessKey): String = when (key) {
        is AccessKey.Vless -> "vless"
        is AccessKey.Vmess -> "vmess"
        is AccessKey.Trojan -> "trojan"
        is AccessKey.Shadowsocks -> "shadowsocks"
    }

    private fun proxySettings(key: AccessKey, tactics: Tactics): JsonObject {
        val address = tactics.dialAddress ?: key.endpoint.host
        val port = tactics.portOverride ?: key.endpoint.port
        return when (key) {
            is AccessKey.Vless -> vnext(address, port) {
                put("id", key.uuid)
                put("encryption", key.encryption)
                key.flow?.let { put("flow", it) }
            }
            is AccessKey.Vmess -> vnext(address, port) {
                put("id", key.uuid)
                put("security", key.cipher)
            }
            is AccessKey.Trojan -> servers(address, port) {
                put("password", key.password)
            }
            // Outline's salt prefix is a client-side disguise the server never
            // checks; Xray has no equivalent, so the key connects without it.
            is AccessKey.Shadowsocks -> servers(address, port) {
                put("method", key.method)
                put("password", key.password)
            }
        }
    }

    private fun vnext(address: String, port: Int, user: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        putJsonArray("vnext") {
            addJsonObject {
                put("address", address)
                put("port", port)
                putJsonArray("users") { addJsonObject(user) }
            }
        }
    }

    private fun servers(address: String, port: Int, extra: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            putJsonArray("servers") {
                addJsonObject {
                    put("address", address)
                    put("port", port)
                    extra()
                }
            }
        }

    /** Multiplexing is refused where the protocol forbids it, whatever the tactics ask. */
    private fun mux(key: AccessKey, tactics: Tactics): JsonObject = buildJsonObject {
        val allowed = key !is AccessKey.Shadowsocks &&
            (key as? AccessKey.Vless)?.flow == null &&
            (key as? Transported)?.transport !is Transport.Xhttp
        val enabled = tactics.multiplex && allowed
        put("enabled", enabled)
        if (enabled) {
            put("concurrency", MUX_CONCURRENCY)
            put("xudpConcurrency", XUDP_CONCURRENCY)
            put("xudpProxyUDP443", "reject")
        }
    }

    private fun streamSettings(key: AccessKey, tactics: Tactics): JsonObject = buildJsonObject {
        val transported = key as? Transported
        val transport = transported?.transport ?: Transport.Tcp()
        val security = transported?.security ?: Security.None
        val dialsByIp = tactics.dialAddress != null
        val hostName = key.endpoint.host.takeUnless(::isIpLiteral)

        putTransport(transport, tactics, hostHeaderFallback = if (dialsByIp) hostName else null)
        putSecurity(security, tactics, sniFallback = if (dialsByIp) hostName else null)

        if (tactics.fragment != null) {
            putJsonObject("sockopt") { put("dialerProxy", TAG_FRAGMENT) }
        }
    }

    private fun JsonObjectBuilder.putTransport(transport: Transport, tactics: Tactics, hostHeaderFallback: String?) {
        when (transport) {
            is Transport.Tcp -> put("network", "raw")
            is Transport.WebSocket -> {
                put("network", "ws")
                putJsonObject("wsSettings") {
                    put("path", transport.path)
                    (transport.host ?: hostHeaderFallback)?.let { put("host", it) }
                }
            }
            is Transport.Grpc -> {
                put("network", "grpc")
                putJsonObject("grpcSettings") {
                    put("serviceName", transport.serviceName)
                    put("multiMode", transport.multiMode)
                }
            }
            is Transport.HttpUpgrade -> {
                put("network", "httpupgrade")
                putJsonObject("httpupgradeSettings") {
                    put("path", transport.path)
                    (transport.host ?: hostHeaderFallback)?.let { put("host", it) }
                }
            }
            is Transport.Xhttp -> {
                put("network", "xhttp")
                putJsonObject("xhttpSettings") {
                    put("path", transport.path)
                    (transport.host ?: hostHeaderFallback)?.let { put("host", it) }
                    (tactics.xhttpMode ?: transport.mode)?.let { put("mode", it) }
                    transport.extra?.let(::parseObjectOrNull)?.let { put("extra", it) }
                }
            }
        }
    }

    private fun JsonObjectBuilder.putSecurity(security: Security, tactics: Tactics, sniFallback: String?) {
        when (security) {
            Security.None -> put("security", "none")
            is Security.Tls -> {
                put("security", "tls")
                putJsonObject("tlsSettings") {
                    (security.serverName ?: sniFallback)?.let { put("serverName", it) }
                    if (security.alpn.isNotEmpty()) putJsonArray("alpn") { security.alpn.forEach(::add) }
                    put("fingerprint", tactics.fingerprint ?: security.fingerprint)
                    if (security.allowInsecure) put("allowInsecure", true)
                }
            }
            is Security.Reality -> {
                put("security", "reality")
                putJsonObject("realitySettings") {
                    put("serverName", security.serverName)
                    put("publicKey", security.publicKey)
                    put("shortId", security.shortId)
                    put("fingerprint", tactics.fingerprint ?: security.fingerprint)
                    security.spiderX?.let { put("spiderX", it) }
                    security.mldsa65Verify?.let { put("mldsa65Verify", it) }
                }
            }
        }
    }

    private fun fragmentOutbound(tactics: Tactics): JsonObject = buildJsonObject {
        val fragment = requireNotNull(tactics.fragment)
        put("tag", TAG_FRAGMENT)
        put("protocol", "freedom")
        putJsonObject("settings") {
            putJsonObject("fragment") {
                put("packets", fragment.packets)
                put("length", fragment.length)
                put("interval", fragment.interval)
            }
        }
        putJsonObject("streamSettings") {
            putJsonObject("sockopt") { put("tcpNoDelay", true) }
        }
    }

    private fun dns(routing: RoutingPolicy): JsonObject = buildJsonObject {
        put("tag", TAG_DNS_INTERNAL)
        put("queryStrategy", if (routing.allowIpv6) "UseIP" else "UseIPv4")
        putJsonArray("servers") {
            if (routing.bypassDomestic && routing.domesticDomainSuffixes.isNotEmpty()) {
                addJsonObject {
                    put("address", DOMESTIC_RESOLVER)
                    put("port", DNS_PORT)
                    putJsonArray("domains") { routing.domesticDomainSuffixes.forEach { add("domain:$it") } }
                    put("skipFallback", true)
                }
            }
            TunnelResolvers.forEach(::add)
        }
    }

    private fun routing(policy: RoutingPolicy): JsonObject = buildJsonObject {
        put("domainStrategy", "AsIs")
        putJsonArray("rules") {
            // 1. Every DNS query an app sends into the tunnel is answered by Xray.
            addJsonObject {
                putJsonArray("inboundTag") { add(TAG_TUN_IN) }
                put("port", "$DNS_PORT")
                put("outboundTag", TAG_DNS_OUT)
            }
            if (policy.bypassDomestic) {
                // 2. The domestic resolver is reached directly…
                addJsonObject {
                    putJsonArray("ip") { add(DOMESTIC_RESOLVER) }
                    put("port", "$DNS_PORT")
                    put("outboundTag", TAG_DIRECT)
                }
            }
            // 3. …and every other internal lookup travels encrypted through the tunnel.
            addJsonObject {
                putJsonArray("inboundTag") { add(TAG_DNS_INTERNAL) }
                put("outboundTag", TAG_PROXY)
            }
            // 4. Local networks never enter the tunnel.
            addJsonObject {
                put("ip", JsonArray(PrivateRanges.map(::jsonString)))
                put("outboundTag", TAG_DIRECT)
            }
            if (!policy.allowIpv6) {
                // 5. Stray IPv6 is refused inside the tunnel rather than leaked around it.
                addJsonObject {
                    putJsonArray("ip") { add("::/0") }
                    put("outboundTag", TAG_BLOCK)
                }
            }
            if (policy.bypassDomestic && policy.domesticDomainSuffixes.isNotEmpty()) {
                // 6. Domestic sites go direct.
                addJsonObject {
                    putJsonArray("domain") { policy.domesticDomainSuffixes.forEach { add("domain:$it") } }
                    put("outboundTag", TAG_DIRECT)
                }
            }
            // 7. Everything else: the first outbound, the proxy.
        }
    }

    private fun parseObjectOrNull(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun jsonString(value: String): JsonElement = buildJsonArray { add(value) }[0]

    private fun isIpLiteral(host: String): Boolean = ':' in host || ipv4.matches(host)

    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    private val PrivateRanges = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16",
        "100.64.0.0/10", "127.0.0.0/8", "224.0.0.0/4", "255.255.255.255/32",
        "fc00::/7", "fe80::/10", "ff00::/8", "::1/128",
    )

    private const val DNS_PORT = 53
    private const val MUX_CONCURRENCY = 8
    private const val XUDP_CONCURRENCY = 16
}
