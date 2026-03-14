package io.agentmesh.util

import io.agentmesh.db.Agents
import io.agentmesh.db.DatabaseFactory.query
import io.agentmesh.db.OrgApiKeys
import io.ktor.server.application.*
import io.ktor.server.request.*
import org.jetbrains.exposed.sql.select
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val random = SecureRandom()

fun generateApiKey(): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return "amk_live_${bytes.toHex()}"
}

fun hashApiKey(key: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray())
        .toHex()

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Constant-time comparison to prevent timing attacks */
fun secureEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var result = 0
    for (i in a.indices) result = result or (a[i].code xor b[i].code)
    return result == 0
}

/** Returns agentId if the Bearer token is valid, null otherwise */
suspend fun ApplicationCall.authenticatedAgentId(): String? {
    val auth = request.header("Authorization") ?: return null
    if (!auth.startsWith("Bearer ")) return null
    val key = auth.removePrefix("Bearer ").trim()
    if (!key.startsWith("amk_live_")) return null

    val hash = hashApiKey(key)

    return query {
        Agents
            .slice(Agents.id)
            .select { Agents.apiKeyHash eq hash }
            .singleOrNull()
            ?.get(Agents.id)
    }
}

/** Returns orgId if the Bearer token is an org API key, null otherwise */
suspend fun ApplicationCall.authenticatedOrgId(): String? {
    val auth = request.header("Authorization") ?: return null
    if (!auth.startsWith("Bearer ")) return null
    val key = auth.removePrefix("Bearer ").trim()
    if (!key.startsWith("oak_live_")) return null

    val hash = hashApiKey(key)

    return query {
        OrgApiKeys
            .slice(OrgApiKeys.orgId)
            .select { OrgApiKeys.apiKeyHash eq hash }
            .singleOrNull()
            ?.get(OrgApiKeys.orgId)
    }
}

/** HMAC-SHA256 webhook signature */
fun signWebhook(payload: String, secret: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    return "sha256=" + mac.doFinal(payload.toByteArray()).toHex()
}
