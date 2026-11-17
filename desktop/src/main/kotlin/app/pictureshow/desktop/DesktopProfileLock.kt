/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** What a PIN pad is asking for. GATE is the whole-window lock; the rest are dialogs over a page. */
enum class DesktopPinPurpose { GATE, SWITCH, CONFIRM_CHANGE, CONFIRM_REMOVE, CONFIRM_ASK_ON, CONFIRM_ASK_OFF }

enum class DesktopPinNote { NONE, WRONG, WAIT, TOO_SHORT, MISMATCH }

data class DesktopPinPrompt(
    val key: String,
    val name: String,
    val purpose: DesktopPinPurpose,
    val note: DesktopPinNote = DesktopPinNote.NONE,
    val waitSeconds: Int = 0,
    val lockedOut: Boolean = false,
    val busy: Boolean = false,
    /** Counts submissions, so the pad can clear itself after each one. */
    val tries: Int = 0
)

/** The new-PIN screen: [first] is the PIN typed once, waiting for the same PIN again. */
data class DesktopPinSetup(val key: String, val name: String, val first: String? = null, val note: DesktopPinNote = DesktopPinNote.NONE, val tries: Int = 0)

data class DesktopPinState(
    val ready: Boolean = false,
    val locks: Map<String, ProfileLock> = emptyMap(),
    val unlocked: String? = null,
    /** The current viewer is behind its PIN: nothing else in the window composes. */
    val gate: DesktopPinPrompt? = null,
    val prompt: DesktopPinPrompt? = null,
    val setup: DesktopPinSetup? = null,
    val error: String? = null
)

/**
 * AUTH-16/18/19 on desktop. Every rule (who is asked, the count and waits, relock after five minutes away, which change
 * needs the current PIN) is core's [ProfileLockStore]; this class only feeds it the desktop's moments: the current
 * viewer changing, the window being away, and the pad's input. PINs are saved only as core's PBKDF2 hashes in
 * `profile-locks.json`; a PIN or its hash is never logged.
 *
 * [clock] is monotonic. `System.nanoTime` on the HotSpot JVM reads the OS monotonic clock (CLOCK_MONOTONIC,
 * mach_absolute_time, QueryPerformanceCounter), which never follows a date or time change.
 */
class DesktopProfileLock(
    directory: File,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    hasher: ProfilePinHasher = JvmProfilePinHasher(),
    private val log: (String) -> Unit = {}
) {
    private val store = ProfileLockStore(DesktopTextPersistence(File(directory, "profile-locks.json")), hasher)
    private val mutable = MutableStateFlow(DesktopPinState())
    val state: StateFlow<DesktopPinState> = mutable.asStateFlow()
    @Volatile private var pending: (() -> Unit)? = null
    private var waitJob: Job? = null
    private var current: Account? = null
    private var iconified = false
    private var focused = true
    private var playing = false
    /** Called when five minutes or more away locked again (AUTH-18): the controller closes the player. */
    @Volatile var onRelock: () -> Unit = {}

    init {
        scope.launch { store.locks.collect { locks -> mutable.update { it.copy(locks = locks) } } }
        scope.launch { store.unlocked.collect { key -> mutable.update { it.copy(unlocked = key) } } }
    }

    suspend fun load() { store.load(); mutable.update { it.copy(ready = true, locks = store.locks.value) } }
    fun lock(key: String): ProfileLock? = store.lock(key)
    fun gated(): Boolean = mutable.value.gate != null

    /** Whether background work may act for [key] (core's admits): playback refuses a viewer behind its PIN. */
    fun admits(key: String?): Boolean = key == null || store.admits(key)

    /**
     * The app-wide check, run synchronously whenever the current viewer changes, before the next frame, so a locked
     * viewer's Home never flashes. A signed-out or expired viewer needs its password anyway.
     */
    @Synchronized fun viewerChanged(account: Account?) {
        current = account
        val usable = account?.takeIf { it.signedIn }
        val needed = store.gateNeeded(usable?.key)
        if (!needed && usable != null && store.unlocked.value != usable.key) store.markUnlocked(usable.key)
        mutable.update { s ->
            s.copy(gate = if (needed && usable != null) (s.gate?.takeIf { it.key == usable.key } ?: DesktopPinPrompt(usable.key, usable.user.name, DesktopPinPurpose.GATE)) else null,
                prompt = s.prompt?.takeIf { it.purpose == DesktopPinPurpose.SWITCH })
        }
        mutable.value.gate?.let { startWaitTicker(it.key) }
    }

    /** The chooser asks before switching to a viewer with a PIN, unless that viewer is already the open one. */
    fun neededOnSwitch(key: String, currentKey: String?): Boolean =
        store.lock(key) != null && !(key == currentKey && store.unlocked.value == key)

    fun askBeforeSwitch(account: Account, then: () -> Unit) {
        pending = then
        mutable.update { it.copy(prompt = DesktopPinPrompt(account.key, account.user.name, DesktopPinPurpose.SWITCH)) }
        startWaitTicker(account.key)
    }

    /** Signing in with the password or Quick Connect is the Forgot-PIN path (AUTH-16/19). */
    fun provenByPassword(key: String) = store.provenByPassword(key)

    fun retain(keys: Set<String>) = scope.launch { runCatching { store.retain(keys) } }

    fun leftSettings() = store.leftPinSettings()

    fun cancelPrompt() {
        pending = null
        mutable.update { it.copy(prompt = null) }
    }

    /** One submission from whichever pad is showing: the gate first, otherwise the dialog. */
    fun submit(pin: String) {
        val prompt = mutable.value.gate ?: mutable.value.prompt ?: return
        if (prompt.busy || prompt.lockedOut) return
        updatePrompt(prompt) { it.copy(busy = true) }
        scope.launch {
            val confirming = prompt.purpose in setOf(DesktopPinPurpose.CONFIRM_CHANGE, DesktopPinPurpose.CONFIRM_REMOVE, DesktopPinPurpose.CONFIRM_ASK_ON, DesktopPinPurpose.CONFIRM_ASK_OFF)
            val result = try {
                if (confirming) store.confirmForChange(prompt.key, pin, clock()) else store.verify(prompt.key, pin, clock())
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { afterFailedCheck(store.lock(prompt.key), clock()) }
            // The result is logged by kind only; never the PIN, its length or the viewer's name.
            log("pin result=${result.name.lowercase()} purpose=${prompt.purpose.name.lowercase()}")
            when (result) {
                ProfilePinResult.UNLOCKED -> {
                    if (prompt.purpose == DesktopPinPurpose.GATE) mutable.update { it.copy(gate = null) }
                    else {
                        mutable.update { it.copy(prompt = null) }
                        when (prompt.purpose) {
                            DesktopPinPurpose.SWITCH -> pending?.invoke()
                            DesktopPinPurpose.CONFIRM_CHANGE -> mutable.update { it.copy(setup = DesktopPinSetup(prompt.key, prompt.name)) }
                            DesktopPinPurpose.CONFIRM_REMOVE -> change { store.remove(prompt.key) }
                            DesktopPinPurpose.CONFIRM_ASK_ON -> change { store.setAskOnStart(prompt.key, true) }
                            DesktopPinPurpose.CONFIRM_ASK_OFF -> change { store.setAskOnStart(prompt.key, false) }
                            DesktopPinPurpose.GATE -> Unit
                        }
                        pending = null
                    }
                }
                ProfilePinResult.WRONG -> updatePrompt(prompt) { it.copy(busy = false, note = DesktopPinNote.WRONG, tries = it.tries + 1) }
                ProfilePinResult.LOCKED_OUT -> updatePrompt(prompt) { it.copy(busy = false, lockedOut = true, tries = it.tries + 1) }
                ProfilePinResult.WAIT -> {
                    updatePrompt(prompt) { it.copy(busy = false, note = DesktopPinNote.WAIT, tries = it.tries + 1) }
                    startWaitTicker(prompt.key)
                }
            }
        }
    }

    private fun updatePrompt(prompt: DesktopPinPrompt, change: (DesktopPinPrompt) -> DesktopPinPrompt) = mutable.update { s ->
        when {
            s.gate?.key == prompt.key && s.gate.purpose == prompt.purpose -> s.copy(gate = change(s.gate))
            s.prompt?.key == prompt.key && s.prompt.purpose == prompt.purpose -> s.copy(prompt = change(s.prompt))
            else -> s
        }
    }

    /** A wait carried over from a restart or a wrong try counts down on the pad; then the note clears. */
    private fun startWaitTicker(key: String) {
        waitJob?.cancel()
        val lock = store.lock(key) ?: return
        if (profilePinLockedOut(lock)) {
            mutable.update { s -> s.copy(gate = s.gate?.takeIf { it.key == key }?.copy(lockedOut = true) ?: s.gate, prompt = s.prompt?.takeIf { it.key == key }?.copy(lockedOut = true) ?: s.prompt) }
            return
        }
        waitJob = scope.launch {
            while (isActive) {
                val left = store.lock(key)?.let { profilePinCooldown(it, clock()) } ?: 0L
                val seconds = ((left + 999) / 1000).toInt()
                mutable.update { s ->
                    fun DesktopPinPrompt.tick() = if (this.key != key) this else copy(waitSeconds = seconds, note = if (seconds == 0 && note == DesktopPinNote.WAIT) DesktopPinNote.NONE else note)
                    s.copy(gate = s.gate?.tick(), prompt = s.prompt?.tick())
                }
                if (left <= 0) break
                delay(250)
            }
        }
    }

    // Settings (AUTH-19): only the current, unlocked viewer changes its own PIN.

    fun beginSetOrChange(account: Account) {
        if (store.unlocked.value != account.key) return
        if (store.changeNeedsPin(account.key)) {
            mutable.update { it.copy(prompt = DesktopPinPrompt(account.key, account.user.name, DesktopPinPurpose.CONFIRM_CHANGE)) }
            startWaitTicker(account.key)
        } else mutable.update { it.copy(setup = DesktopPinSetup(account.key, account.user.name)) }
    }

    fun setAskOnStart(account: Account, value: Boolean) {
        if (store.unlocked.value != account.key || store.lock(account.key) == null) return
        if (!store.changeNeedsPin(account.key)) { change { store.setAskOnStart(account.key, value) }; return }
        mutable.update { it.copy(prompt = DesktopPinPrompt(account.key, account.user.name, if (value) DesktopPinPurpose.CONFIRM_ASK_ON else DesktopPinPurpose.CONFIRM_ASK_OFF)) }
        startWaitTicker(account.key)
    }

    fun beginRemove(account: Account) {
        if (store.unlocked.value != account.key || store.lock(account.key) == null) return
        if (!store.changeNeedsPin(account.key)) { change { store.remove(account.key) }; return }
        mutable.update { it.copy(prompt = DesktopPinPrompt(account.key, account.user.name, DesktopPinPurpose.CONFIRM_REMOVE)) }
        startWaitTicker(account.key)
    }

    /** The new-PIN pad: type it, then the same PIN again. */
    fun setupEnter(pin: String) {
        val setup = mutable.value.setup ?: return
        when {
            !validProfilePin(pin) -> mutable.update { it.copy(setup = setup.copy(note = DesktopPinNote.TOO_SHORT, tries = setup.tries + 1)) }
            setup.first == null -> mutable.update { it.copy(setup = setup.copy(first = pin, note = DesktopPinNote.NONE, tries = setup.tries + 1)) }
            setup.first != pin -> mutable.update { it.copy(setup = setup.copy(first = null, note = DesktopPinNote.MISMATCH, tries = setup.tries + 1)) }
            else -> {
                mutable.update { it.copy(setup = null) }
                val ask = store.lock(setup.key)?.askOnStart ?: true
                change { store.set(setup.key, pin, askOnStart = ask) }
            }
        }
    }

    fun cancelSetup() {
        mutable.update { it.copy(setup = null) }
        store.dropChangeGrant()
    }

    private fun change(block: suspend () -> Unit) = scope.launch {
        try { block(); mutable.update { it.copy(error = null) }; log("pin settings changed") }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.update { it.copy(error = "Could not save the PIN on this computer. Check its storage and try again.") } }
    }

    // AUTH-18: time away. Desktop's "no Pictureshow screen visible" is a minimized window, or a window that lost focus
    // while nothing plays in it. An unfocused window still playing is the desktop's playing picture-in-picture.

    fun window(iconified: Boolean = this.iconified, focused: Boolean = this.focused) {
        this.iconified = iconified; this.focused = focused
        evaluateAway()
    }

    fun playing(value: Boolean) {
        if (playing == value) return
        playing = value
        evaluateAway()
    }

    @Synchronized private fun evaluateAway() {
        val away = desktopPinAway(iconified, focused, playing)
        if (away) store.backgrounded(clock())
        else if (store.foregrounded(clock())) {
            log("pin relock after time away")
            onRelock()
            viewerChanged(current)
        }
    }
}

/** Core's away rule, fed by the desktop window: visible means shown and focused; shown but unfocused is like PiP. */
internal fun desktopPinAway(iconified: Boolean, focused: Boolean, playing: Boolean): Boolean =
    profilePinAwayRuns(sceneVisible = !iconified && focused, pictureInPicture = !iconified && !focused, playing = playing)

/** A check that threw (storage) never hands back a fresh pad: the store already counted the try in memory. */
internal fun afterFailedCheck(lock: ProfileLock?, now: Long): ProfilePinResult = when {
    lock == null -> ProfilePinResult.WRONG
    profilePinLockedOut(lock) -> ProfilePinResult.LOCKED_OUT
    profilePinCooldown(lock, now) > 0 -> ProfilePinResult.WAIT
    else -> ProfilePinResult.WRONG
}

/** The pad's status line, in the Android reference's words. */
internal fun desktopPinMessage(prompt: DesktopPinPrompt): String? = when {
    prompt.lockedOut -> "Too many wrong PINs, so the PIN is off. Sign in with the password, then set a new PIN in Settings."
    prompt.waitSeconds > 0 -> if (prompt.waitSeconds >= 60) "Too many tries. Try again in ${(prompt.waitSeconds + 59) / 60} min." else "Too many tries. Try again in ${prompt.waitSeconds} s."
    prompt.note == DesktopPinNote.WRONG -> "That PIN didn't match. Try again."
    else -> null
}

internal fun desktopPinTitle(prompt: DesktopPinPrompt): String = when (prompt.purpose) {
    DesktopPinPurpose.GATE, DesktopPinPurpose.SWITCH -> "Enter the PIN for ${prompt.name}"
    else -> "Enter the current PIN for ${prompt.name}"
}

internal fun desktopPinSetupMessage(setup: DesktopPinSetup): String? = when (setup.note) {
    DesktopPinNote.TOO_SHORT -> "A PIN is $PROFILE_PIN_MIN to $PROFILE_PIN_MAX digits."
    DesktopPinNote.MISMATCH -> "Those PINs didn't match. Type the new PIN again."
    else -> null
}

/** Only ASCII digits, at most eight: what the field accepts as the viewer types or pastes. */
internal fun desktopPinInput(value: String): String = value.filter { it in '0'..'9' }.take(PROFILE_PIN_MAX)
