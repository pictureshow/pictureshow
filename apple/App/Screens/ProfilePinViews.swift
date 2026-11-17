// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

/// AUTH-16..19 screens. A number pad rather than a text field: it works the same with the Siri Remote (focus moves
/// over the keys, a click types), touch, and a TV keyboard would show the digits on screen.
struct PinPad<Actions: View>: View {
    let title: String
    var subtitle: String?
    var note: String = ""
    /// Keys rest while a check runs or a wait lasts, so nobody types a PIN that would be thrown away.
    var keysOn: Bool = true
    /// Ten wrong tries or an unreadable PIN: the keys go and only the actions (the password route) remain.
    var padHidden: Bool = false
    /// Bumped by the owner to clear what was typed (after each try).
    var clearSignal: Int = 0
    let submit: (String) -> Void
    @ViewBuilder var actions: () -> Actions

    @State private var pin = ""
    @FocusState private var focus: String?

    var body: some View {
        VStack(spacing: platformValue(tv: 22, touch: 14)) {
            Text(title).font(platformValue(tv: Font.title2, touch: Font.title3).weight(.bold)).multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            if let subtitle {
                Text(subtitle).font(.callout).foregroundStyle(Theme.muted).multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text(pin.isEmpty ? "–" : String(repeating: "●", count: pin.count))
                .font(.title.monospaced()).frame(minHeight: platformValue(tv: 56, touch: 40))
                .accessibilityLabel(pin.isEmpty ? "No digits entered" : "\(pin.count) digit\(pin.count == 1 ? "" : "s") entered")
                .accessibilityIdentifier("pin.dots")
            Text(note).font(.callout).foregroundStyle(Theme.warning).multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(minHeight: platformValue(tv: 34, touch: 22))
                .accessibilityIdentifier("pin.note")
            if !padHidden {
                VStack(spacing: platformValue(tv: 18, touch: 12)) {
                    ForEach(["123", "456", "789"], id: \.self) { row in
                        HStack(spacing: platformValue(tv: 22, touch: 16)) {
                            ForEach(Array(row), id: \.self) { digit in key(String(digit)) { type(digit) } }
                        }
                    }
                    HStack(spacing: platformValue(tv: 22, touch: 16)) {
                        key("⌫", label: "Delete last digit", enabled: keysOn && !pin.isEmpty, id: "delete") { pin = String(pin.dropLast()) }
                        key("0") { type("0") }
                        key("OK", enabled: keysOn && !pin.isEmpty, primary: true, id: "ok") { if keysOn, !pin.isEmpty { submit(pin) } }
                    }
                }
            }
            VStack(spacing: platformValue(tv: 14, touch: 6)) { actions() }
                .padding(.top, platformValue(tv: 12, touch: 6))
        }
        .frame(maxWidth: platformValue(tv: 720, touch: 380))
        .onChange(of: clearSignal) { _, _ in pin = "" }
        // A disabled key can't hold focus, so after OK (or a wait) the remote would have nothing lit: back to 1.
        .onChange(of: pin.isEmpty) { _, empty in if empty, focus == "ok" || focus == "delete" { focus = "1" } }
        .onChange(of: keysOn) { _, on in if on, focus == nil, !padHidden { focus = "1" } }
        .defaultFocus($focus, padHidden ? nil : "1")
    }

    private func type(_ digit: Character) {
        if keysOn, !padHidden, pin.count < ProfilePin.maxLength { pin.append(digit) }
    }

    private func key(_ text: String, label: String? = nil, enabled: Bool? = nil, primary: Bool = false, id: String? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text)
                .font(platformValue(tv: Font.title3, touch: Font.title2).weight(primary ? .bold : .medium))
                .frame(width: platformValue(tv: 150, touch: 84), height: platformValue(tv: 76, touch: 58))
                #if !os(tvOS)
                .foregroundStyle(primary ? Theme.onAccent : .primary)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(primary ? Theme.accent : Theme.surface))
                .contentShape(Rectangle())
                #endif
        }
        #if os(tvOS)
        .buttonStyle(PinKeyStyle(primary: primary))
        #else
        .buttonStyle(.plain)
        #endif
        .opacity((enabled ?? keysOn) ? 1 : 0.4)
        .disabled(!(enabled ?? keysOn))
        .focused($focus, equals: id ?? text)
        .accessibilityLabel(label ?? text)
        .accessibilityIdentifier("pin.key.\(id ?? text)")
    }
}

#if os(tvOS)
/// A pad key on Apple TV. The bordered system style takes the app tint for both fill and digit, so unfocused digits
/// vanished; here the digit stays white on the surface, the focused key goes white with a dark digit and lifts.
private struct PinKeyStyle: ButtonStyle {
    let primary: Bool
    func makeBody(configuration: Configuration) -> some View { PinKeyBody(configuration: configuration, primary: primary) }
}

private struct PinKeyBody: View {
    let configuration: ButtonStyleConfiguration
    let primary: Bool
    @Environment(\.isFocused) private var focused
    var body: some View {
        configuration.label
            .foregroundStyle(focused ? Color.black : (primary ? Theme.onAccent : Color.white))
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(focused ? Color.white : (primary ? Theme.accent : Color.white.opacity(0.12))))
            .scaleEffect(configuration.isPressed ? 0.96 : (focused ? 1.08 : 1))
            .shadow(color: .black.opacity(focused ? 0.45 : 0), radius: 16, y: 10)
            .animation(.easeOut(duration: 0.12), value: focused)
    }
}
#endif

/// Runs PIN tries (the hash runs off the main actor), keeps the pad's note, and counts a wait down on the monotonic clock.
struct PinEntry<Actions: View>: View {
    @EnvironmentObject private var app: AppModel
    let key: String
    let title: String
    var subtitle: String?
    /// Unlocking by default; Settings passes `confirmForChange`, which counts the same way (AUTH-19).
    var confirmingChange = false
    let onUnlocked: () -> Void
    @ViewBuilder var actions: () -> Actions

    @State private var busy = false
    @State private var note = ""
    @State private var waitSeconds = 0
    @State private var tries = 0
    @State private var waitRevision = 0

    private var passwordOnly: Bool { app.pins.passwordOnly(key) }

    var body: some View {
        PinPad(title: title, subtitle: subtitle, note: shownNote, keysOn: !busy && waitSeconds == 0, padHidden: passwordOnly,
               clearSignal: tries, submit: submit, actions: actions)
            // A wait carried over (from another screen, or a restart) shows straight away and counts down.
            .task(id: waitRevision) { await countDown() }
    }

    private var shownNote: String {
        if app.pins.unreadable.contains(key) {
            return "This PIN couldn't be read on this device, so the viewer stays locked. Sign in with the password, then set a new PIN in Settings."
        }
        if passwordOnly { return "Too many wrong PINs, so the PIN is off. Sign in with the password, then set a new PIN in Settings." }
        if waitSeconds >= 60 { return "Too many tries. Try again in \((waitSeconds + 59) / 60) min." }
        if waitSeconds > 0 { return "Too many tries. Try again in \(waitSeconds) s." }
        return note
    }

    private func submit(_ pin: String) {
        guard !busy else { return }
        busy = true
        Task {
            let now = ProfilePin.monotonicNow()
            let result = confirmingChange ? await app.pins.confirmForChange(key, pin: pin, now: now) : await app.pins.verify(key, pin: pin, now: now)
            busy = false
            tries += 1
            switch result {
            case .unlocked: note = ""; onUnlocked()
            case .wrong: note = "That PIN didn't match. Try again."
            case .wait: note = ""; waitRevision += 1
            case .lockedOut: note = ""
            case .unavailable: note = "The PIN couldn't be checked on this device, so the viewer stays locked. Sign in with the password."
            }
        }
    }

    private func countDown() async {
        while !Task.isCancelled {
            let left = app.pins.lock(key).map { ProfilePin.lockedOut($0) ? 0 : ProfilePin.cooldown($0, now: ProfilePin.monotonicNow()) } ?? 0
            waitSeconds = Int((left + 999) / 1000)
            if left <= 0 { return }
            try? await Task.sleep(for: .milliseconds(250))
        }
    }
}

/// A plain text action under the pad (the password route, Switch user, Cancel).
struct PinAction: View {
    let title: String
    let id: String
    let action: () -> Void
    var body: some View {
        Button(title, action: action)
            #if os(tvOS)
            .buttonStyle(TVActionStyle(prominent: false))
            #else
            .font(.callout.weight(.semibold))
            .frame(minHeight: 44)
            #endif
            .accessibilityIdentifier("pin.\(id)")
    }
}

/// The whole-app lock when Pictureshow opens on a viewer with a PIN, comes back after five minutes away, or lands on
/// one by any route other than the chooser. Nothing behind it is built, so no row, deep link or playback entry runs.
struct ProfileLockScreen: View {
    @EnvironmentObject private var app: AppModel
    let account: Account

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                PinEntry(key: account.key, title: "Enter the PIN for \(account.username)",
                         subtitle: "This viewer has a PIN on this device.", onUnlocked: {}) {
                    PinAction(title: "Switch user", id: "switch") { app.leaveLockedViewer() }
                    PinAction(title: "Forgot the PIN? Use the password", id: "password") { app.signingInAgain = account }
                }
                .padding(.vertical, platformValue(tv: 40, touch: 32))
                .padding(.horizontal, Theme.gutter)
                // Centred on screen when it fits; scrolls on a short landscape phone.
                .frame(maxWidth: .infinity, minHeight: geometry.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .pageBackground()
        #if os(tvOS)
        .onExitCommand { app.leaveLockedViewer() }
        #endif
        .accessibilityIdentifier("pin.lock")
    }
}

/// Asked before switching to a viewer with a PIN (AUTH-16); the open viewer stays until the PIN is right.
struct ProfilePinSwitchSheet: View {
    @EnvironmentObject private var app: AppModel
    let account: Account

    var body: some View {
        PinEntry(key: account.key, title: "Enter the PIN for \(account.username)", onUnlocked: { app.choose(account) }) {
            PinAction(title: "Forgot the PIN? Use the password", id: "password") {
                app.pinSwitch = nil
                app.signingInAgain = account
            }
            PinAction(title: "Cancel", id: "cancel") { app.pinSwitch = nil }
        }
        .padding(platformValue(tv: 40, touch: 24))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .pageBackground()
        .accessibilityIdentifier("pin.switch-sheet")
    }
}

/// Set or change: type the new PIN twice. The caller already has permission for this viewer.
struct ProfilePinSetup: View {
    let name: String
    let onDone: (String) -> Void
    let onCancel: () -> Void
    @State private var first: String?
    @State private var note = ""
    @State private var tries = 0

    var body: some View {
        PinPad(title: first == nil ? "Choose a PIN" : "Type the PIN again",
               // Both steps keep a subtitle so the keys stay where they were.
               subtitle: first == nil ? "\(ProfilePin.minLength) to \(ProfilePin.maxLength) digits. Pictureshow asks for it before anyone opens \(name) on this device."
                                      : "Type the same PIN again to turn it on for \(name).",
               note: note, clearSignal: tries, submit: submit) {
            PinAction(title: "Cancel", id: "cancel", action: onCancel)
        }
    }

    private func submit(_ pin: String) {
        tries += 1
        guard let chosen = first else {
            if ProfilePin.valid(pin) { first = pin; note = "" } else { note = "A PIN is \(ProfilePin.minLength) to \(ProfilePin.maxLength) digits." }
            return
        }
        if chosen == pin { onDone(pin) } else { first = nil; note = "Those PINs didn't match. Type the new PIN again." }
    }
}

/// AUTH-16 and AUTH-19 Settings rows for the open viewer: set, change, ask when Pictureshow opens, and turn off.
/// Every change but the first PIN asks for the current PIN, since a viewer can be open without anyone having typed it.
struct ProfilePinSettingsSection: View {
    @EnvironmentObject private var app: AppModel
    @State private var flow: PinFlow?
    @State private var error: String?

    enum Change: Equatable { case newPin, remove, askOnStart(Bool) }
    struct PinFlow: Identifiable, Equatable {
        var change: Change
        var confirmed: Bool
        var id: String { "\(change)" }
    }

    var body: some View {
        if let account = app.account, app.pins.unlocked == account.key {
            let lock = app.pins.lock(account.key)
            let hasPin = app.pins.hasPin(account.key)
            Section {
                Button { start(.newPin, account) } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(hasPin ? "Change PIN" : "Set a PIN")
                        Text(hasPin ? "On. Asked when switching to \(account.username)." : "Off. Anyone using this device can open \(account.username).")
                            .font(.caption).foregroundStyle(Theme.muted)
                    }
                }
                .accessibilityIdentifier("settings.pin")
                if let lock {
                    Toggle(isOn: Binding(get: { lock.askOnStart }, set: { start(.askOnStart($0), account) })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Ask when Pictureshow opens")
                            Text(lock.askOnStart ? "On: the PIN is asked every time Pictureshow starts on \(account.username)." : "Off: asked only when someone switches to \(account.username).")
                                .font(.caption).foregroundStyle(Theme.muted)
                        }
                    }
                    .accessibilityIdentifier("settings.pin-ask-on-open")
                }
                if hasPin {
                    Button(role: .destructive) { start(.remove, account) } label: { Text("Turn off PIN") }
                        .accessibilityIdentifier("settings.pin-remove")
                }
                if let error { Text(error).font(.footnote).foregroundStyle(Theme.warning) }
            } header: {
                Text("Profile PIN")
            } footer: {
                Text("The PIN stays on this device. Ratings, libraries and viewing hours are set for each user on your Jellyfin server.")
            }
            // Closing the sheet ends a permission the new-PIN screen never used (AUTH-19); the viewer changing ends it in
            // the store.
            .sheet(item: $flow, onDismiss: { app.pins.dropChangeGrant() }) { current in
                sheet(current, account: account, askOnStart: lock?.askOnStart ?? true).environmentObject(app)
            }
            // Leaving the PIN settings ends a password proof too (AUTH-19). A sheet over the section (tvOS presents
            // it full screen) is not leaving.
            .onDisappear { if flow == nil { app.pins.leftPinSettings() } }
        }
    }

    private func start(_ change: Change, _ account: Account) {
        error = nil
        if app.pins.changeNeedsPin(account.key) { flow = PinFlow(change: change, confirmed: false); return }
        if change == .newPin { flow = PinFlow(change: .newPin, confirmed: true) } else { apply(change, account) }
    }

    @ViewBuilder private func sheet(_ current: PinFlow, account: Account, askOnStart: Bool) -> some View {
        Group {
            if !current.confirmed {
                PinEntry(key: account.key, title: "Enter the current PIN for \(account.username)", confirmingChange: true, onUnlocked: {
                    if current.change == .newPin { flow = PinFlow(change: .newPin, confirmed: true) } else { flow = nil; apply(current.change, account) }
                }) {
                    PinAction(title: "Cancel", id: "cancel") { flow = nil }
                }
            } else {
                ProfilePinSetup(name: account.username, onDone: { pin in
                    // Saved before the sheet closes, while the one-change permission still holds.
                    Task {
                        do { try await app.pins.set(account.key, pin: pin, askOnStart: askOnStart) }
                        catch { self.error = "The PIN couldn't be saved on this device. Nothing changed; try again." }
                        flow = nil
                    }
                }, onCancel: { flow = nil })
            }
        }
        .padding(platformValue(tv: 40, touch: 24))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .pageBackground()
    }

    private func apply(_ change: Change, _ account: Account) {
        do {
            switch change {
            case .newPin: return
            case .remove: try app.pins.remove(account.key)
            case .askOnStart(let value): try app.pins.setAskOnStart(account.key, value)
            }
        } catch {
            self.error = "That change couldn't be saved on this device. Nothing changed; try again."
        }
    }
}
