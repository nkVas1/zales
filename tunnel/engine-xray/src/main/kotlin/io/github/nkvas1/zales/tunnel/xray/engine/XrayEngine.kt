// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.xray.engine

import io.github.nkvas1.zales.common.ZalesLog
import io.github.nkvas1.zales.tunnel.api.EngineConfig
import io.github.nkvas1.zales.tunnel.api.EngineResult
import io.github.nkvas1.zales.tunnel.api.ProbeResult
import io.github.nkvas1.zales.tunnel.api.SocketProtector
import io.github.nkvas1.zales.tunnel.api.TunnelEngine
import io.github.nkvas1.zales.tunnel.xray.XrayConfigBuilder
import io.github.nkvas1.zales.zalescore.Protector
import io.github.nkvas1.zales.zalescore.Zalescore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.ceil

/**
 * [TunnelEngine] backed by Xray-core, reached through `zales-core.aar`.
 *
 * Every call blocks on native code; the tunnel service runs them off the main
 * thread and serialises them. All traffic with the core goes through libXray's
 * JSON protocol, so this class never depends on gomobile-generated types beyond
 * the four entry points in [Zalescore].
 */
public class XrayEngine : TunnelEngine {

    override val version: String by lazy {
        when (val result = call(METHOD_VERSION, payload = null)) {
            is EngineResult.Ok -> "Xray " + (result.value.field("version") ?: "unknown")
            is EngineResult.Error -> "Xray (unavailable)"
        }
    }

    override fun setProtector(protector: SocketProtector?): EngineResult<Unit> = guard("setProtector") {
        val bridge = protector?.let { target -> Protector { fd -> target.protect(fd.toInt()) } }
        Zalescore.setProtector(bridge, FALLBACK_RESOLVER)
    }

    override fun validate(config: EngineConfig): EngineResult<Unit> =
        call(METHOD_TEST, configPayload(config)).discardValue()

    override fun start(config: EngineConfig, tunFd: Int): EngineResult<Unit> {
        val handedOver = guard("setTunFd") { Zalescore.setTunFd(tunFd.toLong()) }
        if (handedOver is EngineResult.Error) return handedOver
        return call(METHOD_RUN, configPayload(config)).discardValue()
    }

    override fun stop(): EngineResult<Unit> {
        val stopped = call(METHOD_STOP, payload = null).discardValue()
        // Always forget the descriptor, even if stopping reported a problem.
        guard("clearTunFd") { Zalescore.clearTunFd() }
        return stopped
    }

    override fun isRunning(): Boolean {
        val state = call(METHOD_STATE, payload = null) as? EngineResult.Ok ?: return false
        return (state.value as? JsonObject)?.get("running")?.jsonPrimitive?.booleanOrNull == true
    }

    override fun probe(configs: List<EngineConfig>, url: String, timeoutMs: Int): List<ProbeResult> {
        if (configs.isEmpty()) return emptyList()
        val payload = buildJsonObject {
            putJsonArray("configs") {
                configs.forEach { config ->
                    addJsonObject {
                        put("xrayJson", config.document)
                        put("outboundTag", XrayConfigBuilder.TAG_PROXY)
                    }
                }
            }
            // libXray takes whole seconds; never round a short budget down to zero.
            put("timeout", ceil(timeoutMs / MILLIS_PER_SECOND).toInt().coerceAtLeast(1))
            put("url", url)
        }
        return when (val result = call(METHOD_PING, payload)) {
            is EngineResult.Error -> configs.map { ProbeResult(success = false, delayMs = 0, error = result.message) }
            is EngineResult.Ok -> {
                val items = (result.value as? JsonObject)?.get("results")?.jsonArray.orEmpty()
                configs.indices.map { index -> items.getOrNull(index)?.toProbeResult() ?: missingResult() }
            }
        }
    }

    private fun JsonElement.toProbeResult(): ProbeResult {
        val item = jsonObject
        return ProbeResult(
            success = item["success"]?.jsonPrimitive?.booleanOrNull == true,
            delayMs = item["delay"]?.jsonPrimitive?.longOrNull ?: 0,
            error = item["error"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
        )
    }

    private fun missingResult() = ProbeResult(success = false, delayMs = 0, error = "core returned no result")

    private fun configPayload(config: EngineConfig): JsonObject = buildJsonObject {
        put("xrayJson", config.document)
    }

    private fun call(method: String, payload: JsonObject?): EngineResult<JsonElement?> {
        val request = buildJsonObject {
            put("apiVersion", API_VERSION)
            put("method", method)
            payload?.let { put("payload", it) }
        }
        val raw = try {
            Zalescore.invoke(request.toString())
        } catch (unloadable: LinkageError) {
            return unavailable(unloadable)
        }
        val envelope = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return EngineResult.Error("$method: core returned a malformed response")

        return if (envelope["success"]?.jsonPrimitive?.booleanOrNull == true) {
            EngineResult.Ok(envelope["data"]?.takeUnless { it is JsonNull })
        } else {
            val message = envelope["error"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "failed"
            ZalesLog.warn(ZalesLog.TAG_TUNNEL, "core $method failed")
            EngineResult.Error("$method: $message")
        }
    }

    private inline fun guard(operation: String, block: () -> Unit): EngineResult<Unit> = try {
        block()
        EngineResult.Ok(Unit)
    } catch (unloadable: LinkageError) {
        unavailable(unloadable)
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        // gomobile surfaces every Go error as a plain java.lang.Exception.
        EngineResult.Error("$operation: ${failure.message ?: failure::class.simpleName}")
    }

    private fun unavailable(error: LinkageError): EngineResult.Error {
        ZalesLog.error(ZalesLog.TAG_TUNNEL, "native core cannot be loaded on this device", error)
        return EngineResult.Error("native core cannot be loaded: ${error::class.simpleName}", nativeUnavailable = true)
    }

    private fun EngineResult<JsonElement?>.discardValue(): EngineResult<Unit> = when (this) {
        is EngineResult.Ok -> EngineResult.Ok(Unit)
        is EngineResult.Error -> this
    }

    private fun JsonElement?.field(name: String): String? =
        (this as? JsonObject)?.get(name)?.jsonPrimitive?.contentOrNull

    private companion object {
        const val API_VERSION = 3
        const val METHOD_VERSION = "xrayVersion"
        const val METHOD_TEST = "testXray"
        const val METHOD_RUN = "runXray"
        const val METHOD_STOP = "stopXray"
        const val METHOD_STATE = "getXrayState"
        const val METHOD_PING = "pingBatch"
        const val MILLIS_PER_SECOND = 1000.0

        /**
         * Resolver for Go's own lookups, with protected sockets. Server names are
         * normally resolved before this is ever needed.
         */
        const val FALLBACK_RESOLVER = "1.1.1.1:53"
    }
}
