/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.pictureshow.core.JvmProfilePinHasher
import app.pictureshow.core.PROFILE_PIN_RELOCK_AFTER_MS
import app.pictureshow.core.ProfileLockStore
import app.pictureshow.core.TextPersistence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** AUTH-18: the process lifecycle's stop and start drive the relock, measured on the clock the observer is given. */
class ProfileRelockObserverTest {
    private class Memory(var text: String? = null) : TextPersistence {
        override suspend fun read() = text
        override suspend fun write(value: String) {
            text = value
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun stoppingFiveMinutesLocksAndAShortStopDoesNot() {
        val store = ProfileLockStore(Memory(), JvmProfilePinHasher(rounds = 1_000))
        runBlocking { store.set("parent", "2468", askOnStart = true) }
        var now = 10_000L
        val owner = Owner()
        var relocks = 0
        owner.registry.addObserver(ProfileRelockObserver(store, clock = { now }, onRelock = { relocks++ }))
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals("parent", store.unlocked.value)
        // Away two minutes: still open.
        owner.registry.currentState = Lifecycle.State.CREATED
        now += 120_000
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals("parent", store.unlocked.value)
        assertEquals(0, relocks)
        // Paused only (picture-in-picture, a dialog over us) for an hour: never stopped, so still open.
        owner.registry.currentState = Lifecycle.State.STARTED
        now += 3_600_000
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals("parent", store.unlocked.value)
        // Away five minutes: locked again.
        owner.registry.currentState = Lifecycle.State.CREATED
        now += PROFILE_PIN_RELOCK_AFTER_MS
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertNull(store.unlocked.value)
        // The relock also stops what kept playing for the viewer, once.
        assertEquals(1, relocks)
    }

    @Test
    fun aPausedPictureInPictureWindowCountsAsAwayAndAPlayingOneDoesNot() {
        val store = ProfileLockStore(Memory(), JvmProfilePinHasher(rounds = 1_000))
        runBlocking { store.set("parent", "2468", askOnStart = true) }
        var now = 10_000L
        var relocks = 0
        val observer = ProfileRelockObserver(store, clock = { now }, onRelock = { relocks++ })
        // Playing in the window for an hour: in use.
        observer.pictureInPicture(active = true, playing = true, started = true)
        now += 3_600_000
        observer.pictureInPicture(active = true, playing = true, started = true)
        assertEquals("parent", store.unlocked.value)
        // Paused two minutes, then played again: still open.
        observer.pictureInPicture(active = true, playing = false, started = true)
        now += 120_000
        observer.pictureInPicture(active = true, playing = true, started = true)
        assertEquals("parent", store.unlocked.value)
        // Paused five minutes, then Play in the window: locked again, and what played stops.
        observer.pictureInPicture(active = true, playing = false, started = true)
        now += PROFILE_PIN_RELOCK_AFTER_MS
        observer.pictureInPicture(active = true, playing = true, started = true)
        assertNull(store.unlocked.value)
        assertEquals(1, relocks)
    }

    @Test
    fun closingAPausedWindowKeepsTheEarlierStartAndFullScreenEndsIt() {
        val store = ProfileLockStore(Memory(), JvmProfilePinHasher(rounds = 1_000))
        runBlocking { store.set("parent", "2468", askOnStart = true) }
        var now = 10_000L
        val owner = Owner()
        val observer = ProfileRelockObserver(store, clock = { now })
        owner.registry.addObserver(observer)
        owner.registry.currentState = Lifecycle.State.RESUMED
        // Paused in the window three minutes, then expanded back to full screen: in use again, the clock ends.
        observer.pictureInPicture(active = true, playing = false, started = true)
        now += 180_000
        observer.pictureInPicture(active = false, playing = false, started = true)
        now += 180_000
        owner.registry.currentState = Lifecycle.State.CREATED
        now += 60_000
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals("parent", store.unlocked.value)
        // Paused four minutes, then the window closed (stopped): the away time started at the pause, not the close.
        observer.pictureInPicture(active = true, playing = false, started = true)
        now += 240_000
        observer.pictureInPicture(active = false, playing = false, started = false)
        owner.registry.currentState = Lifecycle.State.CREATED
        now += 60_000
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertNull(store.unlocked.value)
    }

    // The shared profile-pin-away vectors run through the observer's own decision, so they test the rule Android runs.
    @Test
    fun theSharedAwayVectorsDecideWhenTheObserverStartsTheClock() {
        val roots = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
        val vectors = roots.map { java.io.File(it, "spec/vectors/application.json") }.first { it.isFile }
        val cases = kotlinx.serialization.json.Json.parseToJsonElement(vectors.readText()).jsonObject.getValue("cases").jsonArray
            .map { it.jsonObject }.filter { it["operation"]?.jsonPrimitive?.content == "profile-pin-away" }
        assertEquals(5, cases.size)
        cases.forEach { case ->
            val input = case.getValue("input").jsonObject
            val store = ProfileLockStore(Memory(), JvmProfilePinHasher(rounds = 1_000))
            runBlocking { store.set("parent", "2468", askOnStart = true) }
            var now = 10_000L
            val observer = ProfileRelockObserver(store, clock = { now })
            observer.away(
                input.getValue("sceneVisible").jsonPrimitive.boolean,
                input.getValue("pictureInPicture").jsonPrimitive.boolean,
                input.getValue("playing").jsonPrimitive.boolean
            )
            now += PROFILE_PIN_RELOCK_AFTER_MS
            observer.away(sceneVisible = true, pictureInPicture = false, playing = false)
            val ran = store.unlocked.value == null
            assertEquals(case.getValue("expected").jsonPrimitive.boolean, ran, case.getValue("id").jsonPrimitive.content)
        }
    }
}
