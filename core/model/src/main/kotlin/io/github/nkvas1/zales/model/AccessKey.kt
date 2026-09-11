// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.model

/**
 * An access key, normalised away from whatever link syntax it arrived in.
 *
 * `vless://…`, `ss://…` in three dialects, an Outline JSON document — all end
 * up here. Downstream code (the config builder, the strategy ladder) never sees
 * a URI again, which is what lets one key fan out into many connection
 * strategies.
 *
 * **This type holds secrets.** It must never be logged, never cross into the UI
 * process, and never be put in a crash report. The UI works with [KeyDescriptor].
 */
public sealed interface AccessKey {
    /** Human label: the link fragment if present, otherwise the host. */
    public val label: String

    /** Where the server lives. A domain or an IP literal, never bracketed. */
    public val endpoint: Endpoint

    /** Proxy protocol spoken to the server. */
    public val protocol: Protocol

    /** A view that is safe to show and to pass between processes. */
    public fun describe(): KeyDescriptor {
        val security = (this as? Transported)?.security
        return KeyDescriptor(
            label = label,
            protocol = protocol,
            host = endpoint.host,
            transport = (this as? Transported)?.transport?.kind,
            security = security?.kind,
            skipsCertificateCheck = (security as? Security.Tls)?.allowInsecure == true,
        )
    }

    /** VLESS — the protocol Russian censorship has not decisively beaten. */
    public data class Vless(
        override val label: String,
        override val endpoint: Endpoint,
        val uuid: String,
        /** `xtls-rprx-vision` for Vision, `null` otherwise. */
        val flow: String?,
        /** VLESS encryption; `none` unless the server enables post-quantum mode. */
        val encryption: String,
        override val transport: Transport,
        override val security: Security,
    ) : AccessKey, Transported {
        override val protocol: Protocol get() = Protocol.VLESS
        override fun toString(): String = "Vless(label=$label, host=${endpoint.host}, <secrets hidden>)"
    }

    /** Shadowsocks — what an Outline key is underneath. */
    public data class Shadowsocks(
        override val label: String,
        override val endpoint: Endpoint,
        /** AEAD cipher, e.g. `chacha20-ietf-poly1305` or `2022-blake3-aes-256-gcm`. */
        val method: String,
        val password: String,
        /**
         * Outline salt prefix, percent-decoded to raw bytes and stored as an
         * ISO-8859-1 string so every byte value round-trips unchanged.
         */
        val prefix: String?,
    ) : AccessKey {
        override val protocol: Protocol get() = Protocol.SHADOWSOCKS
        override fun toString(): String = "Shadowsocks(label=$label, host=${endpoint.host}, <secrets hidden>)"
    }

    public data class Trojan(
        override val label: String,
        override val endpoint: Endpoint,
        val password: String,
        override val transport: Transport,
        override val security: Security,
    ) : AccessKey, Transported {
        override val protocol: Protocol get() = Protocol.TROJAN
        override fun toString(): String = "Trojan(label=$label, host=${endpoint.host}, <secrets hidden>)"
    }

    public data class Vmess(
        override val label: String,
        override val endpoint: Endpoint,
        val uuid: String,
        /** `auto` in practice; the server decides the real cipher. */
        val cipher: String,
        override val transport: Transport,
        override val security: Security,
    ) : AccessKey, Transported {
        override val protocol: Protocol get() = Protocol.VMESS
        override fun toString(): String = "Vmess(label=$label, host=${endpoint.host}, <secrets hidden>)"
    }
}

/** Keys whose protocol runs over a pluggable transport with optional TLS/Reality. */
public interface Transported {
    public val transport: Transport
    public val security: Security
}

public data class Endpoint(val host: String, val port: Int) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..MAX_PORT) { "port $port is out of range" }
    }

    private companion object {
        const val MAX_PORT = 65_535
    }
}

public enum class Protocol { VLESS, SHADOWSOCKS, TROJAN, VMESS }

/** How bytes travel between client and server, below the proxy protocol. */
public sealed interface Transport {
    public val kind: TransportKind

    public data class Tcp(val headerType: String? = null) : Transport {
        override val kind: TransportKind get() = TransportKind.TCP
    }

    public data class WebSocket(val path: String, val host: String?) : Transport {
        override val kind: TransportKind get() = TransportKind.WEBSOCKET
    }

    public data class Grpc(val serviceName: String, val multiMode: Boolean) : Transport {
        override val kind: TransportKind get() = TransportKind.GRPC
    }

    public data class HttpUpgrade(val path: String, val host: String?) : Transport {
        override val kind: TransportKind get() = TransportKind.HTTP_UPGRADE
    }

    /**
     * XHTTP (formerly SplitHTTP). [mode] matters in Russia: `packet-up` sends
     * uploads as separate HTTP requests, so no single long TCP session exists
     * for the censor to freeze.
     */
    public data class Xhttp(val path: String, val host: String?, val mode: String?, val extra: String?) : Transport {
        override val kind: TransportKind get() = TransportKind.XHTTP
    }
}

public enum class TransportKind { TCP, WEBSOCKET, GRPC, HTTP_UPGRADE, XHTTP }

public sealed interface Security {
    public val kind: SecurityKind

    public data object None : Security {
        override val kind: SecurityKind get() = SecurityKind.NONE
    }

    public data class Tls(
        val serverName: String?,
        val alpn: List<String>,
        /** uTLS fingerprint; `chrome` unless the key says otherwise. */
        val fingerprint: String,
        /** Never honoured silently — see docs/SECURITY.md. */
        val allowInsecure: Boolean,
    ) : Security {
        override val kind: SecurityKind get() = SecurityKind.TLS
    }

    public data class Reality(
        val serverName: String,
        val publicKey: String,
        val shortId: String,
        val fingerprint: String,
        val spiderX: String?,
        /** ML-DSA-65 verification key for post-quantum Reality; usually absent. */
        val mldsa65Verify: String?,
    ) : Security {
        override val kind: SecurityKind get() = SecurityKind.REALITY
        override fun toString(): String = "Reality(serverName=$serverName, <keys hidden>)"
    }
}

public enum class SecurityKind { NONE, TLS, REALITY }

/**
 * Everything about a key that is safe to display, persist unencrypted and send
 * across the AIDL boundary. No UUIDs, passwords or Reality keys.
 */
public data class KeyDescriptor(
    val label: String,
    val protocol: Protocol,
    val host: String,
    val transport: TransportKind?,
    val security: SecurityKind?,
    /**
     * The key disables certificate verification. It still connects, but the UI
     * must say that the server's identity is not being checked.
     */
    val skipsCertificateCheck: Boolean,
)
