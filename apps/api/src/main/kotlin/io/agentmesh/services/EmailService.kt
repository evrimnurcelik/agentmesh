package io.agentmesh.services

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

object EmailService {

    private val log = LoggerFactory.getLogger(EmailService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = HttpClient(CIO)
    private val apiKey = System.getenv("RESEND_API_KEY") ?: ""
    private val fromEmail = "AgentMesh <notifications@agentmesh.io>"

    private fun isConfigured() = apiKey.isNotBlank()

    fun sendAgentOffline(toEmail: String, offlineAgentName: String, matchedAgentName: String) {
        if (!isConfigured()) {
            log.debug("Email skipped (RESEND_API_KEY not set): agent_offline to $toEmail")
            return
        }
        scope.launch {
            sendEmail(
                to = toEmail,
                subject = "[$offlineAgentName] went offline — AgentMesh",
                html = """
                    <p>Hi,</p>
                    <p><strong>$offlineAgentName</strong> (matched with <strong>$matchedAgentName</strong>) just went offline.</p>
                    <p>Delegations to this agent may fail until it comes back online.</p>
                    <p>— AgentMesh</p>
                """.trimIndent()
            )
        }
    }

    fun sendDelegationFailed(toEmail: String, delegationId: String, agentName: String, error: String) {
        if (!isConfigured()) {
            log.debug("Email skipped (RESEND_API_KEY not set): delegation_failed to $toEmail")
            return
        }
        scope.launch {
            sendEmail(
                to = toEmail,
                subject = "Delegation $delegationId to $agentName failed — AgentMesh",
                html = """
                    <p>Hi,</p>
                    <p>Delegation <code>$delegationId</code> to <strong>$agentName</strong> failed:</p>
                    <p><code>$error</code></p>
                    <p>— AgentMesh</p>
                """.trimIndent()
            )
        }
    }

    fun sendSlaViolation(toEmail: String, agentName: String, violationType: String, measured: String, threshold: String) {
        if (!isConfigured()) return
        scope.launch {
            sendEmail(
                to = toEmail,
                subject = "SLA violation: $agentName — AgentMesh",
                html = """
                    <p>Hi,</p>
                    <p><strong>$agentName</strong> breached its SLA for <strong>$violationType</strong>.</p>
                    <p>Measured: $measured | Threshold: $threshold</p>
                    <p>— AgentMesh</p>
                """.trimIndent()
            )
        }
    }

    private suspend fun sendEmail(to: String, subject: String, html: String) {
        try {
            client.post("https://api.resend.com/emails") {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody("""{"from":"$fromEmail","to":["$to"],"subject":"$subject","html":"${html.replace("\"", "\\\"")}"}""")
            }
            log.info("Email sent to $to: $subject")
        } catch (e: Exception) {
            log.error("Failed to send email to $to", e)
        }
    }
}
