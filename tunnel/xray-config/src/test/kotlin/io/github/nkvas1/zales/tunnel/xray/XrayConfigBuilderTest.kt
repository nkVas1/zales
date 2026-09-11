// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.xray

import io.github.nkvas1.zales.model.AccessKey
import io.github.nkvas1.zales.model.Endpoint
import io.github.nkvas1.zales.model.Security
import io.github.nkvas1.zales.model.Transport
import io.github.nkvas1.zales.tunnel.api.EngineConfig
import io.github.nkvas1.zales.tunnel.api.Fragment
import io.github.nkvas1.zales.tunnel.api.RoutingPolicy
import io.github.nkvas1.zales.tunnel.api.StrategyId
import io.github.nkvas1.zales.tunnel.api.Tactics
import io.github.nkvas1.zales.tunnel.api.TunSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XrayConfigBuilderTest {

    private val realityVision = AccessKey.Vless(
        label = "home",
        endpoint = Endpoint("vpn.example.com", 443),
        uuid = "11111111-2222-3333-4444-555555555555",
        flow = "xtls-rprx-vision",
        encryption = "none",
        transport = Transport.Tcp(),
        security = Security.Reality("vkvideo.ru", "PBK", "ab12", "chrome", "/", null),
    )

    private val wsTls = AccessKey.Trojan(
        label = "ws",
        endpoint = Endpoint("cdn.example.com", 443),
        password = "pw",
        transport = Transport.WebSocket("/ws", null),
        security = Security.Tls(null, emptyList(), "chrome", allowInsecure = false),
    )

    private val outline = AccessKey.Shadowsocks(
        "o",
        Endpoint("198.51.100.4", 8388),
        "chacha20-ietf-poly1305",
        "pw",
        "POST "
    )

    private fun EngineConfig.root(): JsonObject = Json.parseToJsonElement(document).jsonObject
    private fun JsonObject.outbound(tag: String): JsonObject? =
        getValue("outbounds").jsonArray.map { it.jsonObject }.firstOrNull { it["tag"]?.jsonPrimitive?.content == tag }
    private fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject
    private fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.content
    private fun JsonObject.rules(): List<JsonObject> = obj("routing").getValue("rules").jsonArray.map { it.jsonObject }

    private fun List<JsonObject>.routesDomain(domain: String): Boolean = any { rule ->
        (rule["domain"] as? JsonArray)?.any { it.jsonPrimitive.content == domain } == true
    }

    private fun tunnel(key: AccessKey, tactics: Tactics = Tactics.Baseline, routing: RoutingPolicy = RoutingPolicy()) =
        XrayConfigBuilder.forTunnel(key, tactics, routing, TunSettings()).root()

    @Test
    fun `reality vision produces a vless outbound without mux`() {
        val root = tunnel(realityVision, Tactics.Baseline.copy(multiplex = true))
        val proxy = root.outbound(XrayConfigBuilder.TAG_PROXY)!!

        assertEquals("vless", proxy.str("protocol"))
        val user = proxy.obj(
            "settings"
        ).getValue("vnext").jsonArray[0].jsonObject.getValue("users").jsonArray[0].jsonObject
        assertEquals("xtls-rprx-vision", user.str("flow"))

        val stream = proxy.obj("streamSettings")
        assertEquals("raw", stream.str("network"))
        assertEquals("reality", stream.str("security"))
        assertEquals("vkvideo.ru", stream.obj("realitySettings").str("serverName"))
        assertFalse(proxy.obj("mux").getValue("enabled").jsonPrimitive.boolean, "Vision forbids mux")
    }

    @Test
    fun `the proxy is the first outbound so it catches unmatched traffic`() {
        val first = tunnel(outline).getValue("outbounds").jsonArray[0].jsonObject
        assertEquals(XrayConfigBuilder.TAG_PROXY, first.str("tag"))
    }

    @Test
    fun `tun inbound carries the mtu and sniffs for routing only`() {
        val inbound = XrayConfigBuilder.forTunnel(outline, Tactics.Baseline, RoutingPolicy(), TunSettings(mtu = 1400))
            .root().getValue("inbounds").jsonArray.single().jsonObject
        assertEquals("tun", inbound.str("protocol"))
        assertEquals(1400, inbound.obj("settings").getValue("mtu").jsonPrimitive.int)
        assertTrue(inbound.obj("sniffing").getValue("routeOnly").jsonPrimitive.boolean)
    }

    @Test
    fun `outline shadowsocks connects without its prefix`() {
        val server = tunnel(outline).outbound(XrayConfigBuilder.TAG_PROXY)!!
            .obj("settings").getValue("servers").jsonArray[0].jsonObject
        assertEquals("chacha20-ietf-poly1305", server.str("method"))
        assertNull(server["prefix"])
    }

    @Test
    fun `fragment tactic chains the proxy through a freedom fragmenter`() {
        val root = tunnel(realityVision, Tactics(StrategyId("s1"), fragment = Fragment()))
        assertEquals(
            XrayConfigBuilder.TAG_FRAGMENT,
            root.outbound(XrayConfigBuilder.TAG_PROXY)!!.obj("streamSettings").obj("sockopt").str("dialerProxy"),
        )
        val fragment = root.outbound(XrayConfigBuilder.TAG_FRAGMENT)!!.obj("settings").obj("fragment")
        assertEquals("tlshello", fragment.str("packets"))
    }

    @Test
    fun `dialing by ip keeps the real name in SNI and Host`() {
        val proxy = tunnel(
            wsTls,
            Tactics(StrategyId("s5"), dialAddress = "203.0.113.9")
        ).outbound(XrayConfigBuilder.TAG_PROXY)!!
        val server = proxy.obj("settings").getValue("servers").jsonArray[0].jsonObject
        assertEquals("203.0.113.9", server.str("address"))
        val stream = proxy.obj("streamSettings")
        assertEquals("cdn.example.com", stream.obj("tlsSettings").str("serverName"))
        assertEquals("cdn.example.com", stream.obj("wsSettings").str("host"))
    }

    @Test
    fun `xhttp mode and fingerprint overrides apply`() {
        val key = realityVision.copy(
            flow = null,
            transport = Transport.Xhttp("/x", null, "auto", """{"xmux":{"maxConcurrency":"16-32"}}""")
        )
        val stream = tunnel(key, Tactics(StrategyId("s2+s3"), fingerprint = "firefox", xhttpMode = "packet-up"))
            .outbound(XrayConfigBuilder.TAG_PROXY)!!.obj("streamSettings")
        assertEquals("packet-up", stream.obj("xhttpSettings").str("mode"))
        assertEquals("16-32", stream.obj("xhttpSettings").obj("extra").obj("xmux").str("maxConcurrency"))
        assertEquals("firefox", stream.obj("realitySettings").str("fingerprint"))
    }

    @Test
    fun `dns queries are hijacked before anything else`() {
        val first = tunnel(outline).rules().first()
        assertEquals(XrayConfigBuilder.TAG_DNS_OUT, first.str("outboundTag"))
        assertEquals("53", first.str("port"))
    }

    @Test
    fun `ipv6 is blocked and answers are IPv4-only unless allowed`() {
        val closed = tunnel(outline)
        assertEquals("UseIPv4", closed.obj("dns").str("queryStrategy"))
        assertTrue(closed.rules().any { it.str("outboundTag") == XrayConfigBuilder.TAG_BLOCK })

        val open = tunnel(outline, routing = RoutingPolicy(allowIpv6 = true))
        assertEquals("UseIP", open.obj("dns").str("queryStrategy"))
        assertFalse(open.rules().any { it.str("outboundTag") == XrayConfigBuilder.TAG_BLOCK })
    }

    @Test
    fun `domestic bypass adds a direct resolver and a domain rule`() {
        val on = tunnel(outline)
        assertTrue(
            on.rules().routesDomain("domain:ru")
        )

        val off = tunnel(outline, routing = RoutingPolicy(bypassDomestic = false))
        assertFalse(off.rules().any { it["domain"] != null })
        assertFalse(off.obj("dns").getValue("servers").jsonArray.any { it is JsonObject })
    }

    @Test
    fun `probe config has an outbound and nothing that listens`() {
        val root = XrayConfigBuilder.forProbe(wsTls, Tactics.Baseline).root()
        assertNull(root["inbounds"])
        assertNull(root["routing"])
        assertEquals(XrayConfigBuilder.TAG_PROXY, root.getValue("outbounds").jsonArray[0].jsonObject.str("tag"))
    }

    @Test
    fun `config toString hides the document`() {
        val config = XrayConfigBuilder.forProbe(realityVision, Tactics.Baseline)
        assertFalse("11111111" in config.toString())
    }
}
