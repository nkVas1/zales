// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.tunnel.diagnostics

import io.github.nkvas1.zales.model.AccessKey
import io.github.nkvas1.zales.model.Security
import io.github.nkvas1.zales.model.Transport
import io.github.nkvas1.zales.model.Transported
import io.github.nkvas1.zales.tunnel.api.FailureCode

/** Everything about the phone that belongs in a report and nothing that identifies it. */
public data class Environment(
    val appVersion: String,
    val androidVersion: String,
    val deviceModel: String,
    val coreVersion: String,
    val network: String,
)

/**
 * The block headed "for whoever gave you the key" (docs/DIAGNOSTICS.md §5).
 *
 * Written in English and in fixed columns, because it is not read here — it is
 * pasted into a message to the person who runs the server, who needs to see at
 * a glance which rung broke and what was tried.
 *
 * Nothing in it can identify the person or unlock anything: no UUID, no
 * password, no full host name. What survives is the shape of the key and the
 * behaviour of the network, which is exactly what a server admin can act on.
 */
public object Report {

    public fun render(
        key: AccessKey,
        environment: Environment,
        steps: List<StepResult>,
        attempts: List<StrategyAttempt>,
        verdict: FailureCode?,
        detail: String,
        resolvedAddress: String?,
    ): String = buildString {
        appendLine(
            "Zales ${environment.appVersion} · Android ${environment.androidVersion} · ${environment.deviceModel}",
        )
        appendLine("Xray-core ${environment.coreVersion}")
        appendLine()
        appendLine("Key      ${shape(key)}")
        appendLine("Server   ${maskHost(key.endpoint.host)}:${key.endpoint.port}${resolved(resolvedAddress)}")
        appendLine("Network  ${environment.network}")
        appendLine()
        appendLine("Probe results")
        steps.forEach { appendLine("  ${line(it)}") }
        if (attempts.isNotEmpty()) {
            appendLine()
            appendLine("Strategies attempted")
            attempts.forEach { attempt ->
                val status = if (attempt.ok) "OK   (${attempt.millis} ms)" else "FAIL  ${attempt.error.orEmpty()}"
                appendLine("  ${attempt.id.padEnd(STRATEGY_WIDTH)}$status")
            }
        }
        appendLine()
        val because = detail.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
        appendLine("Verdict  ${verdict?.printable ?: "no fault found"}$because")
        advice(verdict)?.let {
            appendLine("Hint     $it")
        }
    }.trimEnd()

    /** The shape of the key: what a server admin needs, without a single secret. */
    private fun shape(key: AccessKey): String = when (key) {
        is AccessKey.Shadowsocks -> listOfNotNull(
            "shadowsocks",
            key.method,
            "prefix".takeIf { key.prefix != null },
        ).joinToString(" / ")

        is AccessKey.Vless -> layered(key, key, key.flow)
        is AccessKey.Trojan -> layered(key, key, flow = null)
        is AccessKey.Vmess -> layered(key, key, flow = null)
    }

    private fun layered(key: AccessKey, shell: Transported, flow: String?): String = listOfNotNull(
        key.protocol.name.lowercase(),
        securityName(shell.security),
        transportName(shell.transport),
        flow?.takeIf { it.isNotBlank() },
    ).joinToString(" / ")

    private fun securityName(security: Security): String = security.kind.name.lowercase()

    private fun transportName(transport: Transport): String = transport.kind.name.lowercase()

    /**
     * Keeps the registrable domain and hides the rest.
     *
     * A server name is not a password, but it is the one field that, pasted
     * into the wrong chat, gets a working server blocked. The report is meant
     * to travel, so the label goes.
     */
    private fun maskHost(host: String): String {
        if (host.none { it.isLetter() }) return host
        val parts = host.split('.')
        if (parts.size <= TAIL_PARTS) return host
        return "***." + parts.takeLast(TAIL_PARTS).joinToString(".")
    }

    private fun resolved(address: String?): String = address?.let { "  (resolved $it)" }.orEmpty()

    private fun line(result: StepResult): String {
        val name = result.step.label.padEnd(STEP_WIDTH)
        return name + when (val outcome = result.outcome) {
            is StepOutcome.Ok -> "OK    (${outcome.millis} ms)" + outcome.note?.let { ", $it" }.orEmpty()
            is StepOutcome.Failed -> "FAIL  ${outcome.detail}"
            StepOutcome.Skipped -> "SKIPPED"
            StepOutcome.Pending, StepOutcome.Running -> "…"
        }
    }

    /** What the person on the far end can actually change. */
    private fun advice(verdict: FailureCode?): String? = when (verdict) {
        FailureCode.DPI_01 ->
            "Server-side change required: enable XHTTP (packet-up), or move the " +
                "listener to 8443/2053, or point serverName at a domain reachable from RU."

        FailureCode.SRV_04 -> "Client and server settings have drifted apart. Re-issue the key."
        FailureCode.SRV_05 -> "The account behind this key is gone, expired, or over quota."
        FailureCode.SRV_06 -> "Certificate does not match. Do not reconnect until this is explained."
        FailureCode.SRV_01 -> "Name does not resolve on this network. An IP in the key would bypass this."
        FailureCode.SYS_04 -> "The phone is cutting the tunnel off in the background, not the server."
        else -> null
    }

    private val ProbeStep.label: String
        get() = when (this) {
            ProbeStep.INTERFACE -> "P1 interface"
            ProbeStep.DNS -> "P2 dns"
            ProbeStep.TCP -> "P3 tcp connect"
            ProbeStep.TRANSPORT -> "P4 transport"
            ProbeStep.AUTH -> "P5 auth"
            ProbeStep.TUNNEL_HTTP -> "P6 tunnel http"
            ProbeStep.BACKGROUND -> "P7 background"
        }

    private const val STEP_WIDTH = 20
    private const val STRATEGY_WIDTH = 32
    private const val TAIL_PARTS = 2
}
