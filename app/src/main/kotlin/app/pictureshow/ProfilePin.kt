/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pictureshow.core.PROFILE_PIN_MAX
import app.pictureshow.core.PROFILE_PIN_MIN
import app.pictureshow.core.ProfileLockStore
import app.pictureshow.core.ProfilePinResult
import app.pictureshow.core.validProfilePin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the pad says under the dots after a try. Words live in strings.xml. */
internal enum class PinPadNote { NONE, WRONG, WAIT, TOO_SHORT, MISMATCH }

/**
 * A number pad, the same on a TV remote (D-pad focus, or the remote's number keys) and a touch screen. A pad instead
 * of a text field: no keyboard that differs per TV, and a TV keyboard would show the digits on screen.
 */
@Composable
internal fun ProfilePinPad(
    title: String,
    subtitle: String?,
    tv: Boolean,
    busy: Boolean,
    note: PinPadNote,
    waitSeconds: Int,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    clearSignal: Int = 0,
    /** Ten wrong tries: the pad goes away and only the actions (the password route) remain. */
    lockedOut: Boolean = false,
    actions: @Composable () -> Unit = {}
) {
    val palette = pinPalette(tv)
    var pin by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    // During a wait the keys rest too, so nobody types a PIN that would be thrown away.
    val keysOn = !busy && waitSeconds == 0
    LaunchedEffect(clearSignal) { pin = "" }
    LaunchedEffect(lockedOut) { runCatching { first.requestFocus() } }
    fun type(digit: Char) {
        if (!busy && waitSeconds == 0 && !lockedOut && pin.length < PROFILE_PIN_MAX) pin += digit
    }
    fun submit() {
        if (!busy && waitSeconds == 0 && !lockedOut && pin.isNotEmpty()) onSubmit(pin)
    }
    val noteText = when {
        lockedOut -> stringResource(R.string.pin_locked_out)
        waitSeconds >= 60 -> stringResource(R.string.pin_wait_minutes, (waitSeconds + 59) / 60)
        waitSeconds > 0 -> stringResource(R.string.pin_wait, waitSeconds)
        note == PinPadNote.WRONG -> stringResource(R.string.pin_wrong)
        note == PinPadNote.TOO_SHORT -> stringResource(R.string.pin_rule, PROFILE_PIN_MIN, PROFILE_PIN_MAX)
        note == PinPadNote.MISMATCH -> stringResource(R.string.pin_mismatch)
        else -> ""
    }
    Column(
        modifier.widthIn(max = 420.dp).verticalScroll(rememberScrollState()).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            pinDigit(event.key)?.let {
                type(it)
                true
            } ?: when (event.key) {
                Key.Backspace, Key.Delete -> {
                    pin = pin.dropLast(1)
                    true
                }
                Key.NumPadEnter -> {
                    submit()
                    true
                }
                else -> false
            }
        }.testTag("pin:pad"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (tv) 8.dp else 12.dp)
    ) {
        Text(
            title,
            style = if (tv) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )
        subtitle?.let { Text(it, textAlign = TextAlign.Center, color = palette.variant, minLines = 2) }
        val entered = androidx.compose.ui.res.pluralStringResource(R.plurals.pin_entered, pin.length, pin.length)
        Text(
            if (pin.isEmpty()) "–" else "●".repeat(pin.length),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.heightIn(min = 40.dp).testTag("pin:dots").clearAndSetSemantics { contentDescription = entered }
        )
        Text(
            noteText,
            color = palette.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.heightIn(min = 24.dp).testTag("pin:note").semantics { liveRegion = LiveRegionMode.Polite }
        )
        if (lockedOut) {
            Column(
                Modifier.focusRequester(first).focusGroup(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) { actions() }
            return@Column
        }
        val rows = listOf("123", "456", "789")
        rows.forEachIndexed { index, keys ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                keys.forEach { digit ->
                    PinKey(
                        digit.toString(),
                        tv,
                        keysOn,
                        Modifier.testTag("pin:key:$digit").then(
                            if (index == 0 &&
                                digit == '1'
                            ) {
                                Modifier.focusRequester(first)
                            } else {
                                Modifier
                            }
                        )
                    ) { type(digit) }
                }
            }
        }
        val clearLabel = stringResource(R.string.pin_delete)
        val okLabel = stringResource(R.string.pin_ok)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PinKey("⌫", tv, keysOn && pin.isNotEmpty(), Modifier.testTag("pin:delete").semantics { contentDescription = clearLabel }) {
                pin = pin.dropLast(1)
            }
            PinKey("0", tv, keysOn, Modifier.testTag("pin:key:0")) { type('0') }
            PinKey(okLabel, tv, keysOn && pin.isNotEmpty(), Modifier.testTag("pin:ok"), primary = true) { submit() }
        }
        actions()
    }
}

private fun pinDigit(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

@Composable
private fun PinKey(label: String, tv: Boolean, enabled: Boolean, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val size = if (tv) Modifier.size(width = 80.dp, height = 44.dp) else Modifier.size(width = 80.dp, height = 56.dp)
    if (tv) {
        androidx.tv.material3.Button(onClick = onClick, enabled = enabled, modifier = modifier.then(size)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.tv.material3.Text(label) }
        }
    } else if (primary) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier.then(size)) { Text(label) }
    } else {
        FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.then(size)) {
            Text(label, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
internal fun PinTextAction(label: String, tv: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (tv) {
        androidx.tv.material3.OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
            androidx.tv.material3.Text(label)
        }
    } else {
        TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) { Text(label) }
    }
}

/** Runs a PIN check off the main thread (the hash takes real time on a slow TV) and keeps the pad's note and wait. */
internal class PinCheck {
    var busy by mutableStateOf(false)
    var note by mutableStateOf(PinPadNote.NONE)
    var waitSeconds by mutableStateOf(0)
    var tries by mutableStateOf(0)
    var lockedOut by mutableStateOf(false)
}

@Composable
internal fun rememberPinCheck(store: ProfileLockStore, key: String, onUnlocked: () -> Unit): Pair<PinCheck, (String) -> Unit> {
    val check = remember(key) { PinCheck() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(key) {
        // A wait carried over from a restart shows straight away.
        val lock = store.lock(key) ?: return@LaunchedEffect
        if (app.pictureshow.core.profilePinLockedOut(lock)) {
            check.lockedOut = true
            return@LaunchedEffect
        }
        tickWait(check) { app.pictureshow.core.profilePinCooldown(store.lock(key) ?: lock, android.os.SystemClock.elapsedRealtime()) }
    }
    val submit: (String) -> Unit = { pin ->
        scope.launch {
            check.busy = true
            // A save that fails (storage full) must not crash the lock screen or hand back a fresh pad: the store has
            // already counted the try in memory, so the result is read from that count.
            val result = try {
                withContext(Dispatchers.Default) { store.verify(key, pin, android.os.SystemClock.elapsedRealtime()) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                pinResultAfterFailedCheck(store.lock(key), android.os.SystemClock.elapsedRealtime())
            } finally {
                check.busy = false
            }
            check.tries++
            when (result) {
                ProfilePinResult.UNLOCKED -> {
                    check.note = PinPadNote.NONE
                    onUnlocked()
                }
                ProfilePinResult.WRONG -> check.note = PinPadNote.WRONG
                ProfilePinResult.LOCKED_OUT -> check.lockedOut = true
                ProfilePinResult.WAIT -> {
                    check.note = PinPadNote.WAIT
                    tickWait(check) {
                        store.lock(key)?.let { app.pictureshow.core.profilePinCooldown(it, android.os.SystemClock.elapsedRealtime()) }
                            ?: 0
                    }
                }
            }
        }
    }
    return check to submit
}

/**
 * What the pad shows when a check threw: never a fresh pad. A locked-out or waiting count stays so; otherwise the try
 * reads as wrong, since the viewer was not unlocked.
 */
internal fun pinResultAfterFailedCheck(lock: app.pictureshow.core.ProfileLock?, now: Long): ProfilePinResult = when {
    lock == null -> ProfilePinResult.WRONG
    app.pictureshow.core.profilePinLockedOut(lock) -> ProfilePinResult.LOCKED_OUT
    app.pictureshow.core.profilePinCooldown(lock, now) > 0 -> ProfilePinResult.WAIT
    else -> ProfilePinResult.WRONG
}

private suspend fun tickWait(check: PinCheck, remaining: () -> Long) {
    while (true) {
        val left = remaining()
        check.waitSeconds = ((left + 999) / 1000).toInt()
        if (left <= 0) break
        delay(250)
    }
    if (check.note == PinPadNote.WAIT) check.note = PinPadNote.NONE
}

/**
 * Asks for a viewer's PIN before switching to it (AUTH-16). "Use the password instead" signs in again, which unlocks
 * the viewer so a forgotten PIN can be changed in Settings.
 */
@Composable
internal fun ProfilePinDialog(
    store: ProfileLockStore,
    key: String,
    name: String,
    tv: Boolean,
    onUnlocked: () -> Unit,
    onPassword: () -> Unit,
    onCancel: () -> Unit
) {
    val (check, submit) = rememberPinCheck(store, key, onUnlocked)
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PinSurface(tv, Modifier) {
            ProfilePinPad(
                stringResource(R.string.pin_enter_for, name),
                null,
                tv,
                check.busy,
                check.note,
                check.waitSeconds,
                submit,
                clearSignal = check.tries,
                lockedOut = check.lockedOut
            ) {
                PinTextAction(stringResource(R.string.pin_use_password), tv, Modifier.testTag("pin:password"), onPassword)
                PinTextAction(stringResource(R.string.pin_cancel), tv, Modifier.testTag("pin:cancel"), onCancel)
            }
        }
    }
}

/**
 * The whole-app lock when Pictureshow opens on a viewer with a PIN, or lands on one by any route other than the
 * chooser. Nothing behind it is composed, so no row, deep link or playback entry runs until it opens.
 */
@Composable
internal fun ProfileLockScreen(
    store: ProfileLockStore,
    key: String,
    name: String,
    tv: Boolean,
    onUnlocked: () -> Unit,
    onSwitch: () -> Unit,
    onPassword: () -> Unit
) {
    val (check, submit) = rememberPinCheck(store, key, onUnlocked)
    BackHandler(onBack = onSwitch)
    PinSurface(tv, Modifier.testTag("pin:lock")) {
        ProfilePinPad(
            stringResource(R.string.pin_enter_for, name),
            stringResource(R.string.pin_lock_subtitle),
            tv,
            check.busy,
            check.note,
            check.waitSeconds,
            submit,
            clearSignal = check.tries,
            lockedOut = check.lockedOut
        ) {
            PinTextAction(stringResource(R.string.pin_switch_viewer), tv, Modifier.testTag("pin:switch"), onSwitch)
            PinTextAction(stringResource(R.string.pin_use_password), tv, Modifier.testTag("pin:password"), onPassword)
        }
    }
}

/** Set or change: type the new PIN twice. The caller has already unlocked this viewer. */
@Composable
internal fun ProfilePinSetup(tv: Boolean, name: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var firstPin by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf(PinPadNote.NONE) }
    var tries by remember { mutableStateOf(0) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PinSurface(tv, Modifier.testTag("pin:setup")) {
            ProfilePinPad(
                stringResource(if (firstPin == null) R.string.pin_new_title else R.string.pin_confirm_title),
                // Both steps keep a subtitle so the keys stay where they were.
                if (firstPin == null) {
                    stringResource(R.string.pin_new_subtitle, name, PROFILE_PIN_MIN, PROFILE_PIN_MAX)
                } else {
                    stringResource(R.string.pin_confirm_subtitle, name)
                },
                tv,
                busy = false,
                note = note,
                waitSeconds = 0,
                clearSignal = tries,
                onSubmit = { pin ->
                    val chosen = firstPin
                    tries++
                    when {
                        chosen == null && !validProfilePin(pin) -> note = PinPadNote.TOO_SHORT
                        chosen == null -> {
                            firstPin = pin
                            note = PinPadNote.NONE
                        }
                        chosen != pin -> {
                            firstPin = null
                            note = PinPadNote.MISMATCH
                        }
                        else -> onDone(pin)
                    }
                }
            ) {
                PinTextAction(stringResource(R.string.pin_cancel), tv, Modifier.testTag("pin:cancel"), onCancel)
            }
        }
    }
}

/**
 * Renders the app-wide lock when the current viewer needs its PIN and returns true; the caller then composes nothing
 * else. Also drops PINs of viewers no longer saved here. A signed-out or expired viewer needs its password anyway.
 */
@Composable
internal fun profileLockGate(
    snapshot: app.pictureshow.core.AccountSnapshot,
    accounts: app.pictureshow.core.AccountStore,
    tv: Boolean,
    onPassword: (app.pictureshow.core.Account) -> Unit
): Boolean {
    // Absent only in test hosts that build their own Koin graph without it: then there is nothing to lock.
    val store = remember { org.koin.mp.KoinPlatform.getKoinOrNull()?.getOrNull<ProfileLockStore>() } ?: return false
    val ready by androidx.compose.runtime.produceState(false, store) {
        store.load()
        value = true
    }
    val unlocked by store.unlocked.collectAsStateWithLifecycle()
    val locks by store.locks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val saved = snapshot.accounts.map { it.key }.toSet()
    LaunchedEffect(ready, saved) { if (ready) store.retain(saved) }
    if (!ready) {
        PinSurface(tv) {}
        return true
    }
    val account = snapshot.currentAccount?.takeUnless { it.expired || it.signedOut }
    val needed = remember(unlocked, locks, account?.key) { store.gateNeeded(account?.key) }
    // Past the gate the current viewer is the open one, so leaving it locks it again behind its PIN.
    LaunchedEffect(needed, account?.key) {
        if (!needed && account != null && store.unlocked.value != account.key) store.markUnlocked(account.key)
    }
    if (!needed || account == null) return false
    ProfileLockScreen(
        store,
        account.key,
        account.user.name,
        tv,
        onUnlocked = {},
        onSwitch = { scope.launch { accounts.select(null) } },
        onPassword = {
            onPassword(account)
            scope.launch { accounts.select(null) }
        }
    )
    return true
}

/** The chooser asks before switching to a viewer with a PIN, unless that viewer is the open one already. */
internal fun profilePinOnSwitch(store: ProfileLockStore?, key: String, currentKey: String?): Boolean =
    store != null && store.lock(key) != null && !(key == currentKey && store.unlocked.value == key)

/** What the Settings PIN rows are doing: a pad for setting a new PIN, or nothing. */
private enum class PinSettingsStep { NONE, SETUP }

/**
 * AUTH-16 Settings rows for the current viewer: set, change, ask-when-opening and turn off. The current viewer is the
 * unlocked one (the app-wide gate saw to that), so no second PIN prompt is needed here. [row] and [toggle] draw a row
 * in the host's style (TV settings rows or touch rows).
 */
@Composable
internal fun ProfilePinSettings(
    tv: Boolean,
    row: @Composable (title: String, supporting: String?, tag: String, onClick: () -> Unit) -> Unit,
    toggle: @Composable (title: String, supporting: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) -> Unit
) {
    val store = remember { org.koin.mp.KoinPlatform.getKoinOrNull()?.getOrNull<ProfileLockStore>() } ?: return
    val accounts: app.pictureshow.core.AccountStore = org.koin.compose.koinInject()
    val snapshot by accounts.snapshots.collectAsStateWithLifecycle()
    val locks by store.locks.collectAsStateWithLifecycle()
    val unlocked by store.unlocked.collectAsStateWithLifecycle()
    val account = snapshot.currentAccount?.takeUnless { it.expired || it.signedOut } ?: return
    if (unlocked != account.key) return
    val scope = rememberCoroutineScope()
    var step by remember(account.key) { mutableStateOf(PinSettingsStep.NONE) }
    val lock = locks[account.key]
    val name = account.user.name
    row(
        stringResource(if (lock == null) R.string.pin_set else R.string.pin_change),
        stringResource(if (lock == null) R.string.pin_settings_off else R.string.pin_settings_on, name),
        "settings:pin"
    ) { step = PinSettingsStep.SETUP }
    if (lock != null) {
        toggle(
            stringResource(R.string.pin_ask_on_open),
            stringResource(if (lock.askOnStart) R.string.pin_ask_on_open_on else R.string.pin_ask_on_open_off, name),
            lock.askOnStart,
            "settings:pin-ask-on-open"
        ) { value -> scope.launch { runCatching { store.setAskOnStart(account.key, value) } } }
        row(stringResource(R.string.pin_remove), stringResource(R.string.pin_note), "settings:pin-remove") {
            scope.launch { runCatching { store.remove(account.key) } }
        }
    }
    if (step == PinSettingsStep.SETUP) {
        ProfilePinSetup(
            tv,
            name,
            onDone = { pin ->
                step = PinSettingsStep.NONE
                scope.launch {
                    withContext(Dispatchers.Default) {
                        runCatching { store.set(account.key, pin, askOnStart = lock?.askOnStart ?: true) }
                    }
                }
            },
            onCancel = { step = PinSettingsStep.NONE }
        )
    }
}

/** The PIN screens draw on the host's theme: TV Material on a TV, Material 3 on touch (TV leaves M3 at its light default). */
private data class PinPalette(
    val surface: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color,
    val variant: androidx.compose.ui.graphics.Color,
    val error: androidx.compose.ui.graphics.Color
)

@Composable
private fun pinPalette(tv: Boolean): PinPalette = if (tv) {
    val c = androidx.tv.material3.MaterialTheme.colorScheme
    PinPalette(c.surface, c.onSurface, c.onSurfaceVariant, c.error)
} else {
    val c = MaterialTheme.colorScheme
    PinPalette(c.surface, c.onSurface, c.onSurfaceVariant, c.error)
}

/** A full-screen PIN surface with the right colors for [tv]. */
@Composable
private fun PinSurface(tv: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val palette = pinPalette(tv)
    Box(
        modifier.fillMaxSize().background(palette.surface).padding(if (tv) 16.dp else 24.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides palette.content,
            content = content
        )
    }
}
