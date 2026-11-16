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
    val failures: Int = 0,
    val blockedUntil: Long = 0
)

const val PROFILE_PIN_MIN = 4
const val PROFILE_PIN_MAX = 8
const val PROFILE_PIN_TRIES = 5
const val PROFILE_PIN_COOLDOWN_MS = 30_000L

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

/** Milliseconds left before another guess is taken. A clock set backwards can't stretch it past the full wait. */
fun profilePinCooldown(lock: ProfileLock, now: Long): Long = (lock.blockedUntil - now).coerceIn(0, PROFILE_PIN_COOLDOWN_MS)

/** Five wrong guesses, then a 30 s wait; a right one clears the count. A guess during the wait isn't checked or counted. */
fun profilePinAttempt(lock: ProfileLock, now: Long, correct: Boolean): ProfileLock = when {
    profilePinCooldown(lock, now) > 0 -> lock
    correct -> lock.copy(failures = 0, blockedUntil = 0)
    lock.failures + 1 >= PROFILE_PIN_TRIES -> lock.copy(failures = 0, blockedUntil = now + PROFILE_PIN_COOLDOWN_MS)
    else -> lock.copy(failures = lock.failures + 1, blockedUntil = 0)
}

enum class ProfilePinResult { UNLOCKED, WRONG, WAIT }

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

    fun needed(key: String, reason: ProfileUnlockReason): Boolean =
        profilePinNeeded(lock(key), reason, unlockedKey.value == key)

    private var opened: String? = null
    private var openedSeen = false

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
        if (key == null || unlockedKey.value == key) return false
        val reason = if (key == opened) ProfileUnlockReason.START else ProfileUnlockReason.SWITCH
        return profilePinNeeded(lock(key), reason, false)
    }

    /** A viewer with no PIN, or one proven another way (its password at sign-in), is simply open. */
    fun markUnlocked(key: String?) {
        unlockedKey.value = key
    }

    suspend fun verify(key: String, pin: String, now: Long): ProfilePinResult = mutex.withLock {
        loadLocked()
        val old = mutable.value[key] ?: return@withLock ProfilePinResult.UNLOCKED.also { unlockedKey.value = key }
        if (profilePinCooldown(old, now) > 0) return@withLock ProfilePinResult.WAIT
        val correct = validProfilePin(pin) && hasher.matches(pin, old.hash)
        val next = profilePinAttempt(old, now, correct)
        if (next != old) saveLocked(mutable.value + (key to next))
        if (correct) {
            unlockedKey.value = key
            ProfilePinResult.UNLOCKED
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
        require(validProfilePin(pin)) { "A PIN is 4 to 8 digits." }
        saveLocked(mutable.value + (key to ProfileLock(hasher.create(pin), askOnStart)))
        unlockedKey.value = key
    }

    suspend fun setAskOnStart(key: String, askOnStart: Boolean) = mutex.withLock {
        loadLocked()
        check(unlockedKey.value == key) { "Unlock this viewer first." }
        val old = mutable.value[key] ?: return@withLock
        saveLocked(mutable.value + (key to old.copy(askOnStart = askOnStart)))
    }

    suspend fun remove(key: String) = mutex.withLock {
        loadLocked()
        check(unlockedKey.value == key) { "Unlock this viewer first." }
        saveLocked(mutable.value - key)
    }

    /**
     * Signing in with the server password proves who this is, so it clears a forgotten PIN (the Forgot PIN path)
     * and unlocks. Removing a viewer forgets its PIN too, so a re-added viewer starts open.
     */
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

    private suspend fun loadLocked() {
        if (loaded) return
        mutable.value = persistence.read()?.let {
            runCatching { json.decodeFromString<Map<String, ProfileLock>>(it) }.getOrNull()
        } ?: emptyMap()
        loaded = true
    }

    private suspend fun saveLocked(next: Map<String, ProfileLock>) {
        persistence.write(json.encodeToString(next))
        mutable.value = next
    }
}
