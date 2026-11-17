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
}
