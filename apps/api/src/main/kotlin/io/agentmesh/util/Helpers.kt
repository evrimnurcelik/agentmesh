package io.agentmesh.util

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

val json = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    coerceInputValues = true
}

fun newId(prefix: String): String =
    "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"

fun nowIso(): String = java.time.Instant.now().toString()

inline fun <reified T> T.toJsonString(): String = json.encodeToString(this)

inline fun <reified T> String.fromJson(): T = json.decodeFromString(this)
