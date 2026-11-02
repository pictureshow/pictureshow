// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import UIKit
import OSLog
import PictureshowCore

/// Opt-in observation only: never changes VoiceOver state or announcement policy.
@MainActor func detailDiagnostic(_ event: String) {
    #if DEBUG
    guard ProcessInfo.processInfo.environment["PICTURESHOW_DETAIL_DIAGNOSTICS"] == "1" else { return }
    Logger(subsystem: "app.pictureshow", category: "Detail").notice("\(event, privacy: .public)")
    #endif
}

/// Identity is the accepted user event, so identical failed attempts can speak again
/// while transport refinements within an attempt stay silent.
struct AccessibilityCue: Equatable {
    let id = UUID()
    let message: String
    var urgent = true
}

/// One page owns one delivery lane. Queue behind the focused label, coalesce
/// unsubmitted cues, and consume every identity once. UIKit provides no API to
/// retract an announcement once submitted; departure cancels only pending work.
@MainActor final class DetailCueDelivery: ObservableObject {
    private(set) var inFlight: UUID?
    private(set) var inFlightMessage: String?
    private var consumed = Set<UUID>()
    private var pending: (cue: AccessibilityCue, until: TimeInterval)?
    private var active = true
    private let enabled: () -> Bool
    private let now: () -> TimeInterval
    private let post: (AccessibilityCue, NSAttributedString) -> Void
    init(enabled: @escaping () -> Bool = { UIAccessibility.isVoiceOverRunning },
         now: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime },
         post: @escaping (AccessibilityCue, NSAttributedString) -> Void = { cue, speech in
             UIAccessibility.post(notification: .announcement, argument: speech)
             detailDiagnostic("event=cuePosted cue=\(cue.id) kind=\(cue.message == "Ready" ? "Ready" : "other") queued=true")
         }) { self.enabled = enabled; self.now = now; self.post = post }
    func resume() { active = true }
    func cancel() { active = false; pending = nil; inFlight = nil; inFlightMessage = nil }
    func submit(_ cue: AccessibilityCue?) {
        guard let cue else { pending = nil; return }
        guard !consumed.contains(cue.id) else { return }
        consumed.insert(cue.id)
        guard active, enabled(), !cue.message.isEmpty else { return }
        if inFlight != nil { pending = (cue, now() + 30); return }
        deliver(cue)
    }
    func finished(message: String? = nil) {
        if let message, message != inFlightMessage { return }
        inFlight = nil; inFlightMessage = nil
        guard let next = pending else { return }
        pending = nil
        guard active, enabled(), now() < next.until else { return }
        deliver(next.cue)
    }
    private func deliver(_ cue: AccessibilityCue) {
        inFlight = cue.id; inFlightMessage = cue.message
        let speech = NSAttributedString(string: cue.message, attributes: [.accessibilitySpeechQueueAnnouncement: true])
        post(cue, speech)
    }
}

private struct CueAnnouncement: ViewModifier {
    let cue: AccessibilityCue?
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var delivery = DetailCueDelivery()
    func body(content: Content) -> some View {
        content.onAppear { delivery.resume(); delivery.submit(cue) }
            .onChange(of: cue, initial: true) { _, current in
                delivery.submit(current)
            }
            .onReceive(NotificationCenter.default.publisher(for: UIAccessibility.announcementDidFinishNotification)) { note in
                // UIKit completion has no application identity. Match our current
                // utterance so an unrelated announcement cannot drain this page.
                guard let text = note.userInfo?[UIAccessibility.announcementStringValueUserInfoKey] as? String,
                      text == delivery.inFlightMessage else { return }
                detailDiagnostic("event=cueFinished success=\(note.userInfo?[UIAccessibility.announcementWasSuccessfulUserInfoKey] as? Bool ?? false)")
                delivery.finished(message: text)
            }
            .onReceive(NotificationCenter.default.publisher(for: UIAccessibility.voiceOverStatusDidChangeNotification)) { _ in
                if !UIAccessibility.isVoiceOverRunning { delivery.cancel() } else if scenePhase == .active { delivery.resume() }
            }
            .onChange(of: scenePhase) { _, phase in if phase == .active { delivery.resume() } else { delivery.cancel() } }
            .onDisappear { delivery.cancel() }
    }
}

private struct StatusAnnouncement: ViewModifier {
    let value: String?
    @State private var lastValue: String?
    func body(content: Content) -> some View {
        content.onChange(of: value, initial: true) { _, current in
            guard current != lastValue else { return }
            lastValue = current
            guard UIAccessibility.isVoiceOverRunning, let current, !current.isEmpty else { return }
            let speech = NSAttributedString(string: current, attributes: [.accessibilitySpeechQueueAnnouncement: true])
            UIAccessibility.post(notification: .announcement, argument: speech)
        }
    }
}

private struct DetailActionState: ViewModifier {
    let value: String
    let unavailable: Bool
    let held: Bool
    let label: String
    let requestStatus: String?
    let acceptsHeldRequest: Bool
    func body(content: Content) -> some View {
        // Disable only the hidden accessibility representation. The visible native
        // Button retains input focus and its existing deferred-command guards.
        // SwiftUI has no public notEnabled AccessibilityTraits member; disabled
        // controls publish the platform's native disabled accessibility property.
        let disabled = unavailable || (held && !acceptsHeldRequest)
        content.opacity(disabled ? 0.55 : 1).accessibilityRepresentation {
            content.accessibilityLabel(requestStatus ?? label).accessibilityValue(requestStatus != nil || disabled ? "" : value).disabled(disabled)
        }
    }
}

extension View {
    func announceStatus(_ value: String?) -> some View { modifier(StatusAnnouncement(value: value)) }
    func announceCue(_ cue: AccessibilityCue?) -> some View { modifier(CueAnnouncement(cue: cue)) }
    func detailActionState(value: String, unavailable: Bool, held: Bool, label: String, requestStatus: String? = nil, acceptsHeldRequest: Bool = false) -> some View { modifier(DetailActionState(value: value, unavailable: unavailable, held: held, label: label, requestStatus: requestStatus, acceptsHeldRequest: acceptsHeldRequest)) }
}

/// Apply only to catalog path mutations. Playback dismissal keeps its existing
/// transition and hands focus back after the retained detail is ready.
func performCatalogNavigation(_ action: () -> Void) {
    var transaction = Transaction()
    if !DetailFeedback.catalogArrivalUsesAnimation(accessibilityEnabled: UIAccessibility.isVoiceOverRunning, sourceIsPlayback: false, destinationIsCatalog: true) {
        transaction.disablesAnimations = true
        transaction.animation = nil
    }
    withTransaction(transaction, action)
}
