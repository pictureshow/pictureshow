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
        navigationTitle(title)
            // Keep scrolled Form/List rows behind an opaque native bar instead
            // of letting their text show through the principal title.
            .toolbarBackground(Theme.background, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
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


#if os(tvOS)
private struct TelevisionSecondaryActionStyle: PrimitiveButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button(role: configuration.role) { configuration.trigger() } label: {
            // Set ink on the label itself so the native bordered style cannot
            // inherit light text onto the shared light lavender fill.
            configuration.label.foregroundStyle(Theme.background)
        }.buttonStyle(.bordered)
    }
}
#endif

extension View {
    @ViewBuilder func secondaryActionStyle() -> some View {
        #if os(tvOS)
        buttonStyle(TelevisionSecondaryActionStyle())
        #else
        buttonStyle(.bordered)
        #endif
    }
}
