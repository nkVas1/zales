// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.parsing

import io.github.nkvas1.zales.model.AccessKey
import io.github.nkvas1.zales.model.Endpoint
import io.github.nkvas1.zales.model.Security
import io.github.nkvas1.zales.model.Transport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** `vless://id@host:port?type=…&security=…#label` */
internal object VlessLinkParser {
    fun parse(uri: LinkUri): AccessKey.Vless {
        val id = uri.userInfo?.let(Percent::decodeUtf8)?.trim()?.ifBlank { null }
            ?: incomplete("vless link has no user id")
        val endpoint = uri.endpoint()
        val query = uri.query
        return AccessKey.Vless(
            label = uri.label(endpoint),
            endpoint = endpoint,
            uuid = id,
            flow = query.nonBlank("flow")?.takeUnless { it == "none" },
            encryption = query.nonBlank("encryption") ?: "none",
            transport = TransportParams.parse(query),
            security = SecurityParams.parse(query, defaultMode = "none"),
        )
    }
}

/** `trojan://password@host:port?security=tls&sni=…#label` — TLS unless stated otherwise. */
internal object TrojanLinkParser {
    fun parse(uri: LinkUri): AccessKey.Trojan {
        val password = uri.userInfo?.let(Percent::decodeUtf8)?.ifBlank { null }
            ?: incomplete("trojan link has no password")
        val endpoint = uri.endpoint()
        return AccessKey.Trojan(
            label = uri.label(endpoint),
            endpoint = endpoint,
            password = password,
            transport = TransportParams.parse(uri.query),
            security = SecurityParams.parse(uri.query, defaultMode = "tls"),
        )
    }
}

/**
 * Shadowsocks in all three dialects Outline and friends produce:
 *
 * 1. SIP002, base64 userinfo: `ss://BASE64(method:password)@host:port/?outline=1#label`
 * 2. SIP002, percent-encoded plain userinfo — required for 2022 ciphers:
 *    `ss://2022-blake3-aes-256-gcm:KEY%3D@host:port#label`
 * 3. Legacy: `ss://BASE64(method:password@host:port)#label`
 */
internal object ShadowsocksLinkParser {

    private val supportedMethods = setOf(
        "aes-128-gcm", "aes-256-gcm",
        "chacha20-poly1305", "chacha20-ietf-poly1305",
        "xchacha20-poly1305", "xchacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
        "none", "plain",
    )

    fun parse(uri: LinkUri): AccessKey.Shadowsocks {
        if (uri.query.nonBlank("plugin") != null) {
            unsupported("shadowsocks plugin '${uri.query["plugin"]}'")
        }
        val (credentials, endpoint) = if (uri.userInfo != null) {
            decodeUserInfo(uri.userInfo) to uri.endpoint()
        } else {
            decodeLegacy(uri)
        }
        return build(
            label = uri.label(endpoint),
            endpoint = endpoint,
            credentials = credentials,
            prefix = uri.query["prefix"]?.let(Percent::decodeLatin1)?.ifEmpty { null },
        )
    }

    fun build(label: String, endpoint: Endpoint, credentials: Credentials, prefix: String?): AccessKey.Shadowsocks {
        val method = credentials.method.lowercase()
        if (method !in supportedMethods) {
            unsupported("shadowsocks cipher '$method'")
        }
        if (credentials.password.isEmpty()) incomplete("shadowsocks key has no password")
        return AccessKey.Shadowsocks(label, endpoint, method, credentials.password, prefix)
    }

    private fun decodeUserInfo(raw: String): Credentials {
        val plain = Percent.decodeUtf8(raw)
        // Plain form always contains the method separator; base64 never contains ':'.
        val text = if (':' in plain) {
            plain
        } else {
            Base64Text.decodeOrNull(raw) ?: incomplete("shadowsocks credentials are not readable")
        }
        return splitCredentials(text)
    }

    private fun decodeLegacy(uri: LinkUri): Pair<Credentials, Endpoint> {
        val encoded = uri.link.substringAfter("://").substringBefore('#').substringBefore('?').trimEnd('/')
        val decoded = Base64Text.decodeOrNull(encoded) ?: incomplete("legacy shadowsocks link is not valid base64")
        val at = decoded.lastIndexOf('@')
        if (at < 0) incomplete("legacy shadowsocks link has no server")
        val nested = LinkUri.parse("ss://x@" + decoded.substring(at + 1))
        return splitCredentials(decoded.substring(0, at)) to nested.endpoint()
    }

    private fun splitCredentials(text: String): Credentials {
        val colon = text.indexOf(':')
        if (colon <= 0) incomplete("shadowsocks credentials have no cipher")
        return Credentials(method = text.substring(0, colon), password = text.substring(colon + 1))
    }

    data class Credentials(val method: String, val password: String) {
        override fun toString(): String = "Credentials(method=$method, <password hidden>)"
    }
}

/** `vmess://BASE64(v2rayN JSON)` */
internal object VmessLinkParser {

    /** Fields whose v2rayN name matches the query name the shared parsers expect. */
    private val copiedFields = listOf("host", "path", "sni", "alpn", "fp", "pbk", "sid")

    fun parse(link: String): AccessKey.Vmess {
        val obj = decode(link)
        val alterId = obj.text("aid")?.toIntOrNull() ?: 0
        if (alterId != 0) unsupported("vmess alterId $alterId (only AEAD, alterId 0, is supported)")

        val endpoint = endpointOf(obj)
        val query = transportQuery(obj)
        return AccessKey.Vmess(
            label = obj.text("ps") ?: endpoint.host,
            endpoint = endpoint,
            uuid = obj.text("id") ?: incomplete("vmess key has no id"),
            cipher = obj.text("scy") ?: "auto",
            transport = TransportParams.parse(query),
            security = SecurityParams.parse(query, defaultMode = "none"),
        )
    }

    private fun decode(link: String): JsonObject {
        val body = link.substringAfter("://").substringBefore('#')
        val json = Base64Text.decodeOrNull(body) ?: incomplete("vmess link is not valid base64")
        return runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: incomplete("vmess link does not contain a JSON object")
    }

    private fun endpointOf(obj: JsonObject): Endpoint {
        val host = obj.text("add") ?: incomplete("vmess key has no address")
        val port = obj.text("port")?.toIntOrNull() ?: incomplete("vmess key has no port")
        return Endpoint(host.removeSurrounding("[", "]"), port)
    }

    /** Flattens the v2rayN object into the query shape the link parsers share. */
    private fun transportQuery(obj: JsonObject): Map<String, String> {
        val network = obj.text("net") ?: "tcp"
        // In v2rayN "type" means the TCP header for raw, and the mode elsewhere.
        val headerOrMode = obj.text("type")
        return buildMap {
            put("type", network)
            put("security", obj.text("tls") ?: "none")
            copiedFields.forEach { name -> obj.text(name)?.let { put(name, it) } }
            when (network) {
                "grpc" -> {
                    obj.text("path")?.let { put("serviceName", it) }
                    headerOrMode?.let { put("mode", it) }
                }
                "xhttp", "splithttp" -> headerOrMode?.let { put("mode", it) }
                else -> headerOrMode?.let { put("headerType", it) }
            }
        }
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
}

/** The legacy JSON struct an Outline dynamic key may return. */
internal object OutlineDocumentParser {

    fun parse(text: String): ParseResult? {
        if (!text.startsWith('{')) return null
        val obj = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null

        obj["error"]?.let { error ->
            val message = (error as? JsonObject)?.get("message")?.primitiveText() ?: "server returned an error"
            return ParseResult.Failure(ParseFailure.INCOMPLETE, "outline config error: $message")
        }
        if ("transport" in obj) {
            return ParseResult.Failure(ParseFailure.UNSUPPORTED_PROTOCOL, "outline transport config documents")
        }

        val server = obj["server"]?.primitiveText() ?: return null
        return try {
            val endpoint = Endpoint(
                server,
                obj["server_port"]?.primitiveText()?.toIntOrNull() ?: incomplete("outline config has no port")
            )
            val key = ShadowsocksLinkParser.build(
                label = obj["name"]?.primitiveText() ?: endpoint.host,
                endpoint = endpoint,
                credentials = ShadowsocksLinkParser.Credentials(
                    method = obj["method"]?.primitiveText() ?: incomplete("outline config has no cipher"),
                    password = obj["password"]?.primitiveText() ?: incomplete("outline config has no password"),
                ),
                prefix = obj["prefix"]?.primitiveText()?.ifEmpty { null },
            )
            ParseResult.Keys(listOf(key))
        } catch (rejection: KeyRejection) {
            ParseResult.Failure(rejection.failure, rejection.detail)
        }
    }

    private fun JsonElement.primitiveText(): String? = (this as? JsonPrimitive)?.contentOrNull
}

internal object TransportParams {
    fun parse(query: Map<String, String>): Transport {
        val path = query.nonBlank("path") ?: "/"
        val host = query.nonBlank("host")
        return when (val type = query.nonBlank("type")?.lowercase() ?: "tcp") {
            "tcp", "raw" -> {
                val header = query.nonBlank("headerType")
                if (header != null && header != "none") unsupported("tcp header obfuscation '$header'")
                Transport.Tcp()
            }
            "ws", "websocket" -> Transport.WebSocket(path = path, host = host)
            "grpc", "gun" -> Transport.Grpc(
                serviceName = query.nonBlank("serviceName") ?: query.nonBlank("path")?.trim('/').orEmpty(),
                multiMode = query["mode"] == "multi",
            )
            "httpupgrade" -> Transport.HttpUpgrade(path = path, host = host)
            "xhttp", "splithttp" -> Transport.Xhttp(
                path = path,
                host = host,
                mode = query.nonBlank("mode"),
                extra = query.nonBlank("extra"),
            )
            else -> unsupported("transport '$type'")
        }
    }
}

internal object SecurityParams {
    const val DEFAULT_FINGERPRINT: String = "chrome"

    fun parse(query: Map<String, String>, defaultMode: String): Security =
        when (val mode = query.nonBlank("security")?.lowercase() ?: defaultMode) {
            "none" -> Security.None
            "tls", "xtls" -> Security.Tls(
                serverName = query.nonBlank("sni") ?: query.nonBlank("peer"),
                alpn = query["alpn"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
                fingerprint = query.nonBlank("fp") ?: DEFAULT_FINGERPRINT,
                allowInsecure = query["allowInsecure"].isTruthy() || query["insecure"].isTruthy(),
            )
            "reality" -> Security.Reality(
                serverName = query.nonBlank("sni") ?: incomplete("reality key has no server name"),
                publicKey = query.nonBlank("pbk") ?: incomplete("reality key has no public key"),
                shortId = query["sid"].orEmpty().trim(),
                fingerprint = query.nonBlank("fp") ?: DEFAULT_FINGERPRINT,
                spiderX = query.nonBlank("spx"),
                mldsa65Verify = query.nonBlank("pqv"),
            )
            else -> unsupported("security '$mode'")
        }

    private fun String?.isTruthy(): Boolean = this?.lowercase() in setOf("1", "true", "yes")
}

internal fun Map<String, String>.nonBlank(key: String): String? = this[key]?.trim()?.ifBlank { null }
