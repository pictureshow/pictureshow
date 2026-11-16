/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ProfileLockStoreTest {
    private class Memory(var text: String? = null) : TextPersistence {
        override suspend fun read() = text
        override suspend fun write(value: String) {
            text = value
        }
    }

    private val hasher = JvmProfilePinHasher(rounds = 1_000)

    @Test
    fun hashIsSaltedAndNeverThePin() {
        val first = hasher.create("2468")
        val second = hasher.create("2468")
        assertTrue(first != second, "fresh salt each time")
        assertFalse("2468" in first)
        assertTrue(hasher.matches("2468", first))
        assertFalse(hasher.matches("2469", first))
        assertFalse(hasher.matches("2468", "pbkdf2-sha256:oops"))
    }

    @Test
    fun fiveWrongGuessesWaitThirtySecondsAndARightOneDuringTheWaitIsRefused() = runTest {
        val memory = Memory()
        val store = ProfileLockStore(memory, hasher)
        store.set("parent", "2468", askOnStart = true)
        store.markUnlocked(null)
        repeat(4) { assertEquals(ProfilePinResult.WRONG, store.verify("parent", "0000", 1_000)) }
        assertEquals(ProfilePinResult.WAIT, store.verify("parent", "0000", 1_000))
        assertEquals(ProfilePinResult.WAIT, store.verify("parent", "2468", 20_000))
        assertNull(store.unlocked.value)
        assertEquals(ProfilePinResult.UNLOCKED, store.verify("parent", "2468", 31_000))
        assertEquals("parent", store.unlocked.value)
        assertEquals(0, store.lock("parent")?.failures)
    }

    @Test
    fun theCountSurvivesEachWaitAndTheWaitsGrowUntilOnlyThePasswordWorks() = runTest {
        val store = ProfileLockStore(Memory(), hasher)
        store.set("parent", "2468", askOnStart = true)
        store.markUnlocked(null)
        var now = 1_000L
        repeat(4) { assertEquals(ProfilePinResult.WRONG, store.verify("parent", "0000", now)) }
        val waits = listOf(30_000L, 60_000L, 300_000L, 900_000L, 3_600_000L)
        for (wait in waits) {
            assertEquals(ProfilePinResult.WAIT, store.verify("parent", "0000", now))
            assertEquals(wait, profilePinCooldown(store.lock("parent")!!, now), "wait after ${store.lock("parent")!!.failures} tries")
            now += wait
        }
        // Ten wrong in a row: the PIN is off, even the right one, however long anyone waits.
        assertEquals(ProfilePinResult.LOCKED_OUT, store.verify("parent", "0000", now))
        assertEquals(PROFILE_PIN_HARD_LIMIT, store.lock("parent")!!.failures)
        assertEquals(ProfilePinResult.LOCKED_OUT, store.verify("parent", "2468", now + 365L * 24 * 3_600_000))
        assertNull(store.unlocked.value)
        // The password sign-in unlocks the viewer, and a new PIN starts the count again.
        store.markUnlocked("parent")
        store.set("parent", "1357", askOnStart = true)
        assertEquals(0, store.lock("parent")!!.failures)
    }

    @Test
    fun aRestartBeginsTheWaitAgainInsteadOfEndingIt() = runTest {
        val memory = Memory()
        val store = ProfileLockStore(memory, hasher)
        store.set("parent", "2468", askOnStart = true)
        repeat(5) { store.verify("parent", "1111", 5_000_000) }
        // Rebooted: the monotonic clock starts again near zero, far below the saved failure time.
        val restarted = ProfileLockStore(memory, hasher)
        restarted.load()
        assertNull(restarted.unlocked.value)
        assertTrue(restarted.needed("parent", ProfileUnlockReason.START))
        assertEquals(ProfilePinResult.WAIT, restarted.verify("parent", "2468", 10_000))
        assertEquals(ProfilePinResult.UNLOCKED, restarted.verify("parent", "2468", 30_000))
        assertFalse("2468" in memory.text.orEmpty())
    }

    @Test
    fun onlyTheUnlockedViewerChangesOrRemovesItsPin() = runTest {
        val store = ProfileLockStore(Memory(), hasher)
        store.set("parent", "2468", askOnStart = true)
        store.markUnlocked("child")
        assertFailsWith<IllegalStateException> { store.remove("parent") }
        assertFailsWith<IllegalStateException> { store.set("parent", "1357", askOnStart = true) }
        assertFailsWith<IllegalStateException> { store.setAskOnStart("parent", false) }
        store.verify("parent", "2468", 0)
        store.setAskOnStart("parent", false)
        assertFalse(store.needed("parent", ProfileUnlockReason.START))
        assertTrue(store.needed("parent", ProfileUnlockReason.SWITCH))
        store.remove("parent")
        assertNull(store.lock("parent"))
    }

    @Test
    fun aPasswordSignInForgetsThePinAndRemovedViewersLoseTheirs() = runTest {
        val store = ProfileLockStore(Memory(), hasher)
        store.set("parent", "2468", askOnStart = true)
        store.set("teen", "1357", askOnStart = true)
        store.forget("parent")
        assertNull(store.lock("parent"))
        store.retain(setOf("parent"))
        assertNull(store.lock("teen"))
        assertNull(store.unlocked.value, "the removed viewer can't stay unlocked")
    }

    @Test
    fun badPinsAreRefusedBeforeHashing() = runTest {
        val store = ProfileLockStore(Memory(), hasher)
        assertFailsWith<IllegalArgumentException> { store.set("parent", "12", askOnStart = true) }
        assertFailsWith<IllegalArgumentException> { store.set("parent", "١٢٣٤", askOnStart = true) }
    }

    @Test
    fun theGateFollowsTheOpeningChoiceOnlyForTheViewerTheAppOpenedOn() = runTest {
        val memory = Memory()
        ProfileLockStore(memory, hasher).apply {
            set("parent", "2468", askOnStart = false)
            set("teen", "1357", askOnStart = false)
        }
        val store = ProfileLockStore(memory, hasher)
        store.load()
        assertFalse(store.gateNeeded("parent"), "opened on parent, who turned the opening question off")
        assertTrue(store.gateNeeded("teen"), "becoming teen later is a switch")
        assertFalse(store.gateNeeded(null))
        store.verify("teen", "1357", 0)
        assertFalse(store.gateNeeded("teen"))
        assertTrue(store.gateNeeded("parent").not(), "parent still follows its opening choice")
    }
}
