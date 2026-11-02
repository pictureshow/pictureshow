// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import UIKit
import PictureshowCore

/// Identity is the accepted user event, so identical failed attempts can speak again
/// while transport refinements within an attempt stay silent.
struct AccessibilityCue: Equatable {
    let id = UUID()
    let message: String
    var urgent = true
}

private struct CueAnnouncement: ViewModifier {
    let cue: AccessibilityCue?
    @State private var delivered: UUID?
    func body(content: Content) -> some View {
        content.onChange(of: cue, initial: true) { _, current in
            guard let current, current.id != delivered else { return }
            delivered = current.id
            guard UIAccessibility.isVoiceOverRunning, !current.message.isEmpty else { return }
            let speech = NSAttributedString(string: current.message, attributes: [.accessibilitySpeechQueueAnnouncement: !current.urgent])
            UIAccessibility.post(notification: .announcement, argument: speech)
        }
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
    func body(content: Content) -> some View {
        // Disable only the hidden accessibility representation. The visible native
        // Button retains input focus and its existing deferred-command guards.
        // SwiftUI has no public notEnabled AccessibilityTraits member; disabled
        // controls publish the platform's native disabled accessibility property.
        let disabled = held || unavailable
        content.opacity(disabled ? 0.55 : 1).accessibilityRepresentation {
            content.accessibilityLabel(label).accessibilityValue(disabled ? "" : value).disabled(disabled)
        }
    }
}

extension View {
    func announceStatus(_ value: String?) -> some View { modifier(StatusAnnouncement(value: value)) }
    func announceCue(_ cue: AccessibilityCue?) -> some View { modifier(CueAnnouncement(cue: cue)) }
    func detailActionState(value: String, unavailable: Bool, held: Bool, label: String) -> some View { modifier(DetailActionState(value: value, unavailable: unavailable, held: held, label: label)) }
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
