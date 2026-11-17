/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        store.provenByPassword("parent")
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
        store.confirmForChange("parent", "2468", 0)
        store.setAskOnStart("parent", false)
        assertFalse(store.needed("parent", ProfileUnlockReason.START))
        assertTrue(store.needed("parent", ProfileUnlockReason.SWITCH))
        store.confirmForChange("parent", "2468", 0)
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

    @Test
    fun unlockingTheNextViewerDoesNotLockTheOpenOneBeforeTheSwitchLands() = runTest {
        val store = ProfileLockStore(Memory(), hasher)
        store.set("parent", "2468", askOnStart = true)
        store.set("teen", "1357", askOnStart = true)
        store.markUnlocked("parent")
        assertFalse(store.gateNeeded("parent"))
        // The chooser checks teen's PIN; parent is still current for a moment.
        assertEquals(ProfilePinResult.UNLOCKED, store.verify("teen", "1357", 0))
        assertFalse(store.gateNeeded("parent"), "parent stays open until the switch lands")
        assertFalse(store.gateNeeded("teen"))
        // Back on parent by any route but the chooser: it asks.
        assertTrue(store.gateNeeded("parent"))
    }

    @Test
    fun aFailingDiskStillCountsEveryWrongTry() = runTest {
        val memory = Memory()
        val store = ProfileLockStore(memory, hasher)
        store.set("parent", "2468", askOnStart = true)
        store.markUnlocked(null)
        val broken = object : TextPersistence {
            override suspend fun read() = memory.text
            override suspend fun write(value: String) = throw java.io.IOException("disk full")
        }
        val failing = ProfileLockStore(broken, hasher)
        var now = 1_000L
        repeat(PROFILE_PIN_HARD_LIMIT) {
            assertFailsWith<java.io.IOException> { failing.verify("parent", "0000", now) }
            now += 3_600_000
        }
        assertEquals(PROFILE_PIN_HARD_LIMIT, failing.lock("parent")!!.failures)
        assertEquals(ProfilePinResult.LOCKED_OUT, failing.verify("parent", "2468", now))
        assertNull(failing.unlocked.value)
    }

    @Test
    fun fiveMinutesAwayLocksAgainAndShorterDoesNot() = runTest {
        val store = ProfileLockStore(Memory(), hasher)
        store.set("parent", "2468", askOnStart = true)
        assertFalse(store.gateNeeded("parent"))
        // Four minutes in another app, or a screensaver over Pictureshow: still open.
        store.backgrounded(1_000_000)
        assertFalse(store.foregrounded(1_000_000 + 4 * 60_000))
        assertFalse(store.gateNeeded("parent"))
        // Five minutes away is opening it again.
        store.backgrounded(2_000_000)
        assertTrue(store.foregrounded(2_000_000 + PROFILE_PIN_RELOCK_AFTER_MS))
        assertNull(store.unlocked.value)
        assertTrue(store.gateNeeded("parent"))
        // Coming back without having left (no ON_STOP first) never locks.
        store.verify("parent", "2468", 3_000_000)
        assertFalse(store.foregrounded(9_000_000))
        assertFalse(store.gateNeeded("parent"))
    }

    @Test
    fun relockFollowsTheOpeningChoiceAndDropsAPasswordProof() = runTest {
        val store = ProfileLockStore(Memory(), hasher)
        store.set("parent", "2468", askOnStart = false)
        store.set("teen", "1357", askOnStart = false)
        store.provenByPassword("teen")
        assertFalse(store.gateNeeded("teen"))
        assertFalse(store.changeNeedsPin("teen"))
        store.backgrounded(0)
        assertTrue(store.foregrounded(PROFILE_PIN_RELOCK_AFTER_MS))
        // Teen turned the opening question off, so coming back is like opening: no PIN, but no password proof either.
        assertFalse(store.gateNeeded("teen"))
        assertTrue(store.changeNeedsPin("teen"))
    }

    @Test
    fun afterARelockOnlyAViewerTheGateAsksForIsKeptFromPlaying() = runTest {
        val store = ProfileLockStore(Memory(), hasher)
        assertFalse(store.admits("none"), "nothing is admitted before the saved PINs are read")
        store.load()
        store.set("parent", "2468", askOnStart = true)
        assertTrue(store.admits("parent"))
        store.set("teen", "1357", askOnStart = false)
        store.backgrounded(0)
        assertTrue(store.foregrounded(PROFILE_PIN_RELOCK_AFTER_MS))
        // Locked again: the parent's PIN is asked, so its music and remote control stop; the others carry on.
        assertFalse(store.admits("parent"))
        assertTrue(store.admits("teen"))
        assertTrue(store.admits("none"))
        assertFalse(store.admits(null))
        store.verify("parent", "2468", 0)
        assertTrue(store.admits("parent"))
    }

    @Test
    fun aPasswordProofAllowsOneChangeAndEndsWhenTheViewerChanges() = runTest {
        val store = ProfileLockStore(Memory(), hasher)
        store.set("parent", "2468", askOnStart = true)
        store.set("kid", "1357", askOnStart = true)
        // The parent signs in with the password; the sign-in lands on the parent a moment later, and an earlier
        // look at the old viewer doesn't end the proof.
        store.markUnlocked("kid")
        store.provenByPassword("parent")
        store.gateNeeded("kid")
        store.markUnlocked("parent")
        assertFalse(store.gateNeeded("parent"))
        assertFalse(store.changeNeedsPin("parent"))
        // One change uses it up, whichever it is: the next one asks for the PIN.
        store.setAskOnStart("parent", false)
        assertTrue(store.changeNeedsPin("parent"))
        assertFailsWith<IllegalStateException> { store.remove("parent") }
        // A fresh sign-in, then the kid takes over and comes back to the parent: the proof is gone.
        store.provenByPassword("parent")
        assertFalse(store.gateNeeded("parent"))
        store.verify("kid", "1357", 0)
        assertFalse(store.gateNeeded("kid"))
        store.verify("parent", "2468", 0)
        assertFalse(store.gateNeeded("parent"))
        assertTrue(store.changeNeedsPin("parent"))
        assertFailsWith<IllegalStateException> { store.remove("parent") }
        // A fresh sign-in, then switching through the chooser's markUnlocked to another viewer also ends it.
        store.provenByPassword("parent")
        store.markUnlocked("parent")
        store.markUnlocked("kid")
        store.markUnlocked("parent")
        assertTrue(store.changeNeedsPin("parent"))
        // A fresh sign-in survives a cancelled new-PIN screen but not leaving the PIN settings.
        store.provenByPassword("parent")
        store.markUnlocked("parent")
        store.dropChangeGrant()
        assertFalse(store.changeNeedsPin("parent"))
        store.leftPinSettings()
        assertTrue(store.changeNeedsPin("parent"))
        assertNotNull(store.lock("parent"))
    }

    @Test
    fun changingOrTurningOffAPinAsksForTheCurrentOneWithTheSameCount() = runTest {
        val store = ProfileLockStore(Memory(), hasher)
        store.set("parent", "2468", askOnStart = false)
        // Opened without the PIN ("ask when opening" off): open, but not allowed to change it.
        assertFalse(store.gateNeeded("parent"))
        store.markUnlocked("parent")
        assertTrue(store.changeNeedsPin("parent"))
        assertFailsWith<IllegalStateException> { store.remove("parent") }
        assertFailsWith<IllegalStateException> { store.set("parent", "1357", askOnStart = true) }
        assertFailsWith<IllegalStateException> { store.setAskOnStart("parent", true) }
        // Wrong current PINs count and wait exactly like unlocking.
        repeat(4) { assertEquals(ProfilePinResult.WRONG, store.confirmForChange("parent", "0000", 1_000)) }
        assertEquals(ProfilePinResult.WAIT, store.confirmForChange("parent", "0000", 1_000))
        assertEquals(ProfilePinResult.WAIT, store.confirmForChange("parent", "2468", 2_000))
        assertTrue(store.changeNeedsPin("parent"))
        assertEquals(ProfilePinResult.UNLOCKED, store.confirmForChange("parent", "2468", 31_000))
        assertFalse(store.changeNeedsPin("parent"))
        store.set("parent", "1357", askOnStart = false)
        // One right PIN, one change.
        assertTrue(store.changeNeedsPin("parent"))
        assertFailsWith<IllegalStateException> { store.remove("parent") }
        assertNotNull(store.lock("parent"))
    }

    @Test
    fun anUnusedChangePermissionEndsOnCancelOrWhenTheViewerChanges() = runTest {
        val store = ProfileLockStore(Memory(), hasher)
        store.set("parent", "2468", askOnStart = true)
        store.set("teen", "1357", askOnStart = true)
        store.markUnlocked("parent")
        assertFalse(store.gateNeeded("parent"))
        // Typed the current PIN, then cancelled the new-PIN screen.
        store.confirmForChange("parent", "2468", 0)
        store.dropChangeGrant()
        assertTrue(store.changeNeedsPin("parent"))
        assertFailsWith<IllegalStateException> { store.remove("parent") }
        // Typed it, then the viewer changed to teen and back.
        store.confirmForChange("parent", "2468", 0)
        store.verify("teen", "1357", 0)
        assertFalse(store.gateNeeded("teen"))
        store.verify("parent", "2468", 0)
        assertFalse(store.gateNeeded("parent"))
        assertTrue(store.changeNeedsPin("parent"))
        assertFailsWith<IllegalStateException> { store.remove("parent") }
    }
}
