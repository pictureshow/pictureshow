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
        #if os(tvOS)
        // Native tvOS buttons choose contrasting ink as their focus fill changes.
        Button(configuration).buttonStyle(.borderedProminent)
        #else
        Button(configuration).buttonStyle(.borderedProminent).foregroundStyle(Theme.background)
        #endif
    }
}

extension View {
    @ViewBuilder func touchSecondaryInk() -> some View {
        #if os(iOS)
        foregroundStyle(.primary)
        #else
        self
        #endif
    }
}


extension View {
    /// Keep the platform navigation bar and provide explicit TV title ink. Global
    /// UINavigationBarAppearance setters assert in the supported tvOS runtime.
    @ViewBuilder func pictureshowNavigationTitle(_ title: String) -> some View {
        #if os(tvOS)
        navigationTitle(title).toolbar {
            ToolbarItem(placement: .principal) {
                Text(title).font(.title2).foregroundStyle(.white).lineLimit(1)
                    .accessibilityAddTraits(.isHeader)
            }
        }
        #else
        navigationTitle(title)
        #endif
    }
}
