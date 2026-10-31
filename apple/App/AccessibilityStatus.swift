// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import UIKit

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
extension View {
    func announceStatus(_ value: String?) -> some View { modifier(StatusAnnouncement(value: value)) }
}
