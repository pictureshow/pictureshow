// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI

extension View {
    /// Apply inside a custom Button/NavigationLink label so its interaction region
    /// grows with its layout. Native AVKit and system alert controls keep OS sizing.
    @ViewBuilder func minimumTouchTarget() -> some View {
        #if os(iOS)
        frame(minWidth: 48, minHeight: 48).contentShape(Rectangle())
        #else
        self
        #endif
    }
    /// List/Form rows need their own minimum; this does not size standalone actions.
    @ViewBuilder func minimumTouchListRows() -> some View {
        #if os(iOS)
        environment(\.defaultMinListRowHeight, 48)
        #else
        self
        #endif
    }
}

/// Retain native pressed/focus/disabled behavior with readable ink on the shared
/// light lavender accent. Setting this inside the style affects button labels only.
struct ProminentActionStyle: PrimitiveButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button(configuration).buttonStyle(.borderedProminent).foregroundStyle(Theme.background)
    }
}
