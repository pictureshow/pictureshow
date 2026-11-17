/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A viewer's PIN on this device (AUTH-16). It is a household convenience in front of a saved sign-in, the way Plex
 * profile PINs work: Jellyfin keeps the real limits (ratings, tags, libraries, schedules) and enforces them on every
 * request. The PIN only stops someone on this device opening a viewer they shouldn't, so a child can't step from
 * their own restricted viewer into a parent's. Nothing about it is sent to the server.
 */
interface ProfilePinHasher {
    fun create(pin: String): String
    fun matches(pin: String, encoded: String): Boolean
}

@Serializable
data class ProfileLock(
    val hash: String,
    /** Also ask when Pictureshow opens on this viewer, not only when someone switches to it. */
    val askOnStart: Boolean = true,
    /** Wrong guesses since the last right one. A wait ending doesn't reset it; only the right PIN or a new PIN does. */
    val failures: Int = 0,
    /** Monotonic clock (ms since boot, never the wall clock) at the last wrong guess. */
    val failedAt: Long = 0
)

const val PROFILE_PIN_MIN = 4
const val PROFILE_PIN_MAX = 8

/** Wrong guesses allowed before the first wait. */
const val PROFILE_PIN_FREE_TRIES = 4

/** At this many wrong guesses in a row the PIN stops working; only the server password opens the viewer. */
const val PROFILE_PIN_HARD_LIMIT = 10

fun validProfilePin(pin: String): Boolean = pin.length in PROFILE_PIN_MIN..PROFILE_PIN_MAX && pin.all { it in '0'..'9' }

enum class ProfileUnlockReason { START, SWITCH }

/**
 * Whether opening [key] needs its PIN. Switching to a viewer always asks; opening the app asks unless the viewer turned
 * that off or was already unlocked in this run. [unlocked] is process memory only, never saved.
 */
fun profilePinNeeded(lock: ProfileLock?, reason: ProfileUnlockReason, unlocked: Boolean): Boolean = when {
    lock == null -> false
    reason == ProfileUnlockReason.SWITCH -> true
    else -> lock.askOnStart && !unlocked
}

/**
 * The wait after [failures] wrong guesses in a row: none for the first four, then 30 s, 1 min, 5 min, 15 min and 1 h.
 * From [PROFILE_PIN_HARD_LIMIT] on there is no wait to sit out: the PIN is off ([profilePinLockedOut]).
 */
fun profilePinWait(failures: Int): Long = when {
    failures <= PROFILE_PIN_FREE_TRIES -> 0
    failures == 5 -> 30_000
    failures == 6 -> 60_000
    failures == 7 -> 300_000
    failures == 8 -> 900_000
    else -> 3_600_000
}

fun profilePinLockedOut(lock: ProfileLock): Boolean = lock.failures >= PROFILE_PIN_HARD_LIMIT

/**
 * Milliseconds left before another guess is taken, on a monotonic clock that only moves forward while the device runs
 * (Android's elapsedRealtime), so changing the date or time can't shorten it. A [now] below [ProfileLock.failedAt]
 * means the device restarted and the clock began again from zero; then only time since that start counts, so a restart
 * begins the whole wait again rather than ending it.
 */
fun profilePinCooldown(lock: ProfileLock, now: Long): Long {
    if (profilePinLockedOut(lock)) return Long.MAX_VALUE
    val wait = profilePinWait(lock.failures)
    if (wait == 0L) return 0
    val served = if (now >= lock.failedAt) now - lock.failedAt else now.coerceAtLeast(0)
    return (wait - served).coerceIn(0, wait)
}

/** A right PIN clears the count. A guess during a wait, or after the hard limit, isn't checked or counted. */
fun profilePinAttempt(lock: ProfileLock, now: Long, correct: Boolean): ProfileLock = when {
    profilePinCooldown(lock, now) > 0 -> lock
    correct -> lock.copy(failures = 0, failedAt = 0)
    else -> lock.copy(failures = lock.failures + 1, failedAt = now)
}

/** Time away after which coming back to Pictureshow counts as opening it again (AUTH-18). */
const val PROFILE_PIN_RELOCK_AFTER_MS = 5L * 60_000

/**
 * Whether returning to the foreground locks again (AUTH-18). [backgroundedAt] is the monotonic clock when Pictureshow
 * left the screen, null if it never did. Five minutes away counts as opening it again; a clock below the saved time
 * means the device restarted in between, which is always long enough.
 */
fun profilePinRelock(backgroundedAt: Long?, now: Long): Boolean = when {
    backgroundedAt == null -> false
    now < backgroundedAt -> true
    else -> now - backgroundedAt >= PROFILE_PIN_RELOCK_AFTER_MS
}

/**
 * Whether Settings may change, turn off or stop asking for a viewer's PIN (AUTH-19). Setting the first PIN is free.
 * Once there is one, the current PIN has to be typed first (counted like any other try), or the viewer has just
 * proven itself with the server password (the Forgot PIN path).
 */
fun profilePinChangeAllowed(lock: ProfileLock?, pinConfirmed: Boolean, passwordProven: Boolean): Boolean =
    lock == null || pinConfirmed || passwordProven

/** What can happen to a password proof (AUTH-19) after a password or Quick Connect sign-in. */
enum class ProfilePinProofEvent {
    /** A PIN was set, changed, turned off, or its opening question changed. */
    CHANGE_MADE,

    /** Another viewer became current after the proven one had been. */
    VIEWER_CHANGED,

    /** Five minutes away (AUTH-18). */
    RELOCKED,

    /** The PIN settings were left, so the next visit is a new one. */
    SETTINGS_LEFT,

    /** The proven viewer became current: the sign-in landing. */
    SIGN_IN_LANDED,

    /** The new-PIN screen was cancelled without a change. */
    SETUP_CANCELLED
}

/**
 * Whether a password proof survives [event] (AUTH-19). It is one-time, like a typed current PIN: the change it was
 * for uses it up, and so does leaving that viewer or locking again, so a parent's sign-in can't leave a viewer whose
 * PIN anyone may then turn off.
 */
fun profilePinProofKept(event: ProfilePinProofEvent): Boolean = when (event) {
    ProfilePinProofEvent.CHANGE_MADE,
    ProfilePinProofEvent.VIEWER_CHANGED,
    ProfilePinProofEvent.RELOCKED,
    ProfilePinProofEvent.SETTINGS_LEFT -> false
    ProfilePinProofEvent.SIGN_IN_LANDED, ProfilePinProofEvent.SETUP_CANCELLED -> true
}

/**
 * AUTH-18: whether the time away runs. It runs while no Pictureshow screen is visible, except while a picture-in-picture
 * window plays: picture-in-picture counts as in use only while it plays. (The Android app's relock observer doesn't
 * consult this yet; the shared vectors pin the rule.)
 */
fun profilePinAwayRuns(sceneVisible: Boolean, pictureInPicture: Boolean, playing: Boolean): Boolean =
    !sceneVisible && !(pictureInPicture && playing)

/** LOCKED_OUT: the hard limit was reached and only the server password opens this viewer now. */
enum class ProfilePinResult { UNLOCKED, WRONG, WAIT, LOCKED_OUT }

/**
 * Saved PINs, keyed by [Account.key]. Which viewer is unlocked lives only in this object, so a restored process or a
 * saved navigation state can never carry an unlock with it.
 */
class ProfileLockStore(private val persistence: TextPersistence, private val hasher: ProfilePinHasher) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow<Map<String, ProfileLock>>(emptyMap())
    private val unlockedKey = MutableStateFlow<String?>(null)
    private var loaded = false

    val locks: StateFlow<Map<String, ProfileLock>> = mutable.asStateFlow()
    val unlocked: StateFlow<String?> = unlockedKey.asStateFlow()

    suspend fun load() = mutex.withLock { loadLocked() }

    fun lock(key: String): ProfileLock? = mutable.value[key]

    fun needed(key: String, reason: ProfileUnlockReason): Boolean = profilePinNeeded(lock(key), reason, unlockedKey.value == key)

    private var opened: String? = null
    private var openedSeen = false

    /** The viewer the gate last let in. Unlocking someone else doesn't lock it again; only leaving it does. */
    private var admitted: String? = null

    /** A right current PIN typed in Settings, good for one change (AUTH-19). Process memory only. */
    private var changeGrant: String? = null

    /** The viewer that just signed in with its server password, which may make one PIN change without the old PIN. */
    private var passwordKey: String? = null

    /** Whether the proven viewer has become current yet (the sign-in lands a moment after the proof). */
    private var passwordLanded = false

    private fun proofEvent(event: ProfilePinProofEvent) {
        if (!profilePinProofKept(event)) {
            passwordKey = null
            passwordLanded = false
        }
    }

    /** Tracks the current viewer for the proof: landing on it keeps it, any other viewer after that ends it. */
    private fun proofSees(current: String?) {
        val proven = passwordKey ?: return
        if (current == proven) {
            passwordLanded = true
        } else if (passwordLanded) {
            proofEvent(ProfilePinProofEvent.VIEWER_CHANGED)
        }
    }

    /** Monotonic time Pictureshow left the screen, while it is away (AUTH-18). */
    private var backgroundedAt: Long? = null

    /** Pictureshow left the screen (the process stopped being visible). [now] is the monotonic clock. */
    fun backgrounded(now: Long) {
        backgroundedAt = now
    }

    /**
     * Pictureshow is back on screen. After [PROFILE_PIN_RELOCK_AFTER_MS] away it counts as opening it again: every
     * unlock, change grant and password proof is dropped, and the viewer on screen is asked like at a fresh start
     * (following its "ask when Pictureshow opens" choice). Returns whether it locked.
     */
    fun foregrounded(now: Long): Boolean {
        val relock = profilePinRelock(backgroundedAt, now)
        backgroundedAt = null
        if (relock) {
            admitted = null
            changeGrant = null
            proofEvent(ProfilePinProofEvent.RELOCKED)
            openedSeen = false
            opened = null
            unlockedKey.value = null
        }
        return relock
    }

    /**
     * Whether [key] is past the lock (AUTH-18): unlocked, or a viewer opening wouldn't ask (no PIN, or "Ask when
     * Pictureshow opens" off). What background work (remote commands, music controls, a relock's stops) asks before it
     * acts for a viewer. Nothing is admitted before the saved PINs are read. Unlike [gateNeeded] it changes nothing.
     */
    fun admits(key: String?): Boolean = key != null &&
        (unlockedKey.value == key || loaded && !profilePinNeeded(lock(key), ProfileUnlockReason.START, false))

    /** Ends a one-change permission unused: the new-PIN screen was cancelled or left (AUTH-19). */
    fun dropChangeGrant() {
        changeGrant = null
    }

    /** Leaving the PIN settings ends both permissions: a typed PIN and a password proof (AUTH-19). */
    fun leftPinSettings() {
        changeGrant = null
        proofEvent(ProfilePinProofEvent.SETTINGS_LEFT)
    }

    /** A password or Quick Connect sign-in: the viewer is open and may set a new PIN without the old one. */
    fun provenByPassword(key: String) {
        passwordKey = key
        passwordLanded = false
        unlockedKey.value = key
    }

    /** Whether Settings has to ask for the current PIN before changing [key]'s PIN or its opening choice. */
    fun changeNeedsPin(key: String): Boolean = !profilePinChangeAllowed(lock(key), changeGrant == key, passwordKey == key)

    /**
     * The app-wide check, run whenever the current viewer changes. The viewer Pictureshow opened on follows its
     * "ask when Pictureshow opens" choice; any other viewer that becomes current, by whatever route (the chooser,
     * a removal that moves to someone else), asks like a switch unless it was unlocked on the way in.
     */
    fun gateNeeded(key: String?): Boolean {
        if (!openedSeen) {
            opened = key
            openedSeen = true
        }
        // A change permission belongs to the viewer it was typed for; becoming anyone else ends it.
        if (changeGrant != null && changeGrant != key) changeGrant = null
        proofSees(key)
        if (key == null || unlockedKey.value == key) {
            admitted = key
            return false
        }
        // Unlocking another viewer happens just before it becomes current (the chooser, a sign-in), so the open one
        // stays open through that moment instead of flashing its own lock screen.
        if (key == admitted) return false
        admitted = null
        val reason = if (key == opened) ProfileUnlockReason.START else ProfileUnlockReason.SWITCH
        return profilePinNeeded(lock(key), reason, false)
    }

    /** A viewer with no PIN, or one proven another way (its password at sign-in), is simply open. */
    fun markUnlocked(key: String?) {
        if (changeGrant != key) changeGrant = null
        if (key != null) proofSees(key)
        unlockedKey.value = key
    }

    /** [now] is the monotonic clock (elapsedRealtime on Android), never the wall clock. */
    suspend fun verify(key: String, pin: String, now: Long): ProfilePinResult = mutex.withLock { attemptLocked(key, pin, now) }

    /**
     * The current PIN typed in Settings before a change (AUTH-19). Same count, waits and hard limit as unlocking; a
     * right one allows one change.
     */
    suspend fun confirmForChange(key: String, pin: String, now: Long): ProfilePinResult = mutex.withLock {
        attemptLocked(key, pin, now).also { if (it == ProfilePinResult.UNLOCKED) changeGrant = key }
    }

    private suspend fun attemptLocked(key: String, pin: String, now: Long): ProfilePinResult = run {
        loadLocked()
        val old = mutable.value[key] ?: return@run ProfilePinResult.UNLOCKED.also { unlockedKey.value = key }
        if (profilePinLockedOut(old)) return@run ProfilePinResult.LOCKED_OUT
        if (profilePinCooldown(old, now) > 0) return@run ProfilePinResult.WAIT
        val correct = validProfilePin(pin) && hasher.matches(pin, old.hash)
        val next = profilePinAttempt(old, now, correct)
        if (next != old) saveLocked(mutable.value + (key to next))
        if (correct) {
            unlockedKey.value = key
            ProfilePinResult.UNLOCKED
        } else if (profilePinLockedOut(next)) {
            ProfilePinResult.LOCKED_OUT
        } else if (profilePinCooldown(next, now) > 0) {
            ProfilePinResult.WAIT
        } else {
            ProfilePinResult.WRONG
        }
    }

    /** Only the unlocked viewer can set, change or turn off its own PIN. */
    suspend fun set(key: String, pin: String, askOnStart: Boolean) = mutex.withLock {
        loadLocked()
        check(unlockedKey.value == key || mutable.value[key] == null) { "Unlock this viewer first." }
        checkChangeAllowed(key)
        require(validProfilePin(pin)) { "A PIN is 4 to 8 digits." }
        saveLocked(mutable.value + (key to ProfileLock(hasher.create(pin), askOnStart)))
        unlockedKey.value = key
    }

    suspend fun setAskOnStart(key: String, askOnStart: Boolean) = mutex.withLock {
        loadLocked()
        check(unlockedKey.value == key) { "Unlock this viewer first." }
        val old = mutable.value[key] ?: return@withLock
        checkChangeAllowed(key)
        saveLocked(mutable.value + (key to old.copy(askOnStart = askOnStart)))
    }

    suspend fun remove(key: String) = mutex.withLock {
        loadLocked()
        check(unlockedKey.value == key) { "Unlock this viewer first." }
        checkChangeAllowed(key)
        saveLocked(mutable.value - key)
    }

    /** Drops a viewer's PIN without the unlock check, for a viewer removed from this device. */
    suspend fun forget(key: String) = mutex.withLock {
        loadLocked()
        if (key in mutable.value) saveLocked(mutable.value - key)
    }

    /** Keeps only PINs for viewers still saved on this device. */
    suspend fun retain(keys: Set<String>) = mutex.withLock {
        loadLocked()
        val kept = mutable.value.filterKeys { it in keys }
        if (kept.size != mutable.value.size) saveLocked(kept)
        if (unlockedKey.value != null && unlockedKey.value !in keys) unlockedKey.value = null
    }

    /** A change uses up whatever allowed it: the PIN typed for it, or the password proof. */
    private fun checkChangeAllowed(key: String) {
        check(profilePinChangeAllowed(mutable.value[key], changeGrant == key, passwordKey == key)) { "Type the current PIN first." }
        if (changeGrant == key) changeGrant = null
        if (passwordKey == key) proofEvent(ProfilePinProofEvent.CHANGE_MADE)
    }

    private suspend fun loadLocked() {
        if (loaded) return
        mutable.value = persistence.read()?.let {
            runCatching { json.decodeFromString<Map<String, ProfileLock>>(it) }.getOrNull()
        } ?: emptyMap()
        loaded = true
    }

    /**
     * Memory first, then disk: a write that fails (storage full, a broken file) still leaves the wrong try counted for
     * as long as the app runs, so a failing disk can't hand out unlimited guesses.
     */
    private suspend fun saveLocked(next: Map<String, ProfileLock>) {
        mutable.value = next
        persistence.write(json.encodeToString(next))
    }
}
