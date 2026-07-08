package com.apkagent.apktools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** JsonObject 参数读取助手 */
fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.strOrDefault(key: String, default: String): String =
    str(key) ?: default

fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.elt(key: String): JsonElement? = this[key]
