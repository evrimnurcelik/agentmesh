package io.agentmesh.services

import io.agentmesh.models.Delegation
import io.agentmesh.models.DelegationError
import io.agentmesh.models.WebhookPayload
import io.agentmesh.util.nowIso
import io.agentmesh.util.signWebhook
import io.agentmesh.util.toJsonString
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

object WebhookService {

    private val log = LoggerFactory.getLogger(WebhookService::class.java)

    private val client = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
    }

    private val retryDelaysMs = listOf(1_000L, 5_000L, 15_000L)

    suspend fun deliver(
        delegation: Delegation,
        event: String,
        webhookSecret: String,
        output: Map<String, Any?>? = null,
        error: DelegationError? = null
    ): Boolean {
        val payload = WebhookPayload(
            event        = event,
            delegationId = delegation.id,
            status       = delegation.status,
            output       = output,
            error        = error,
            durationMs   = delegation.durationMs ?: 0,
            metadata     = delegation.metadata,
            timestamp    = nowIso()
        )

        val body      = payload.toJsonString()
        val signature = signWebhook(body, webhookSecret)

        for ((attempt, delayMs) in (listOf(0L) + retryDelaysMs).withIndex()) {
            if (attempt > 0) {
                log.warn("[webhook] Retrying attempt $attempt for ${delegation.id}")
                delay(delayMs)
            }
            try {
                val response = client.post(delegation.callbackUrl) {
                    contentType(ContentType.Application.Json)
                    headers {
                        append("X-AgentMesh-Signature",     signature)
                        append("X-AgentMesh-Delegation-Id", delegation.id)
                        append("X-AgentMesh-Attempt",       attempt.toString())
                    }
                    setBody(body)
                }
                if (response.status.isSuccess()) {
                    log.info("[webhook] Delivered ${delegation.id} on attempt $attempt")
                    return true
                }
                log.warn("[webhook] Attempt $attempt got ${response.status} for ${delegation.id}")
            } catch (e: Exception) {
                log.error("[webhook] Attempt $attempt threw exception for ${delegation.id}: ${e.message}")
            }
        }

        log.error("[webhook] All attempts failed for delegation ${delegation.id}")
        return false
    }
}
