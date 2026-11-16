/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Execute AUTH-10 against real AccountStore persistence, including reload and explicit Remove. */
class AccountSignOutVectorsTest {
    @Test fun signOutRetainsCardsAndOnlyRemoveForgetsThem() = runTest {
        val source = assertNotNull(javaClass.classLoader.getResourceAsStream("parity-vectors/account-sign-out.json"))
            .bufferedReader().use { it.readText() }
        val server = Server("demo", "Example", "https://example.invalid", "12")
        for (entry in Json.parseToJsonElement(source).jsonObject.getValue("cases").jsonArray) {
            val c = entry.jsonObject
            val input = c.getValue("input").jsonObject
            val accounts = input.getValue("accounts").jsonArray.map {
                val id = it.jsonPrimitive.content
                Account(server, User(id, id), "token-$id")
            }
            val initial = AccountSnapshot(
                accounts = accounts,
                currentKey = input["current"]?.jsonPrimitive?.contentOrNull?.let { "demo:$it" },
                recentSearches = accounts.associate { it.key to listOf("search-${it.user.id}") },
                homePreferences = accounts.associate { it.key to HomePreferences() },
                pendingHomePreferences = accounts.map { it.key }.toSet(),
                retiredDeviceAccounts = accounts.map { it.copy(accessToken = "retired-${it.user.id}") }
            )
            var persisted = Json.encodeToString(initial)
            val persistence = object : TextPersistence {
                override suspend fun read() = persisted
                override suspend fun write(value: String) {
                    persisted = value
                }
            }
            var store = AccountStore(persistence)
            store.load()
            for (step in input.getValue("steps").jsonArray) {
                val s = step.jsonObject
                val key = s["account"]?.jsonPrimitive?.content?.let { "demo:$it" }
                when (s.getValue("action").jsonPrimitive.content) {
                    "sign-out" -> store.signOutKeepingAccount(assertNotNull(key))
                    "remove" -> store.removeAccount(assertNotNull(key))
                    "reload" -> {
                        store = AccountStore(persistence)
                        store.load()
                    }
                    "sign-in" -> {
                        val id = s.getValue("account").jsonPrimitive.content
                        store.remember(Account(server, User(id, id), "fresh-$id"))
                    }
                    else -> error("Unknown account operation")
                }
            }
            val result = AccountStore(persistence).load()
            fun ids(keys: Collection<String>) = JsonArray(keys.map { JsonPrimitive(it.substringAfter(':')) })
            val actual = buildJsonObject {
                put(
                    "accounts",
                    JsonArray(
                        result.accounts.map { a ->
                            buildJsonObject {
                                put("id", a.user.id)
                                put("token", a.accessToken)
                                put("expired", a.expired)
                                put("signedOut", a.signedOut)
                            }
                        }
                    )
                )
                put("current", result.currentKey?.substringAfter(':')?.let(::JsonPrimitive) ?: JsonNull)
                put("searches", ids(result.recentSearches.keys))
                put("preferences", ids(result.homePreferences.keys))
                put("pending", ids(result.pendingHomePreferences))
                put("retired", ids(result.retiredDeviceAccounts.map { it.key }))
            }
            assertEquals(c.getValue("expected"), actual, c.getValue("id").jsonPrimitive.content)
        }
    }
}
