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
        TelevisionProminentAction(configuration: configuration)
        #else
        Button(configuration).buttonStyle(.borderedProminent).tint(Theme.accent).foregroundStyle(Theme.background)
        #endif
    }
}

extension View {
    @ViewBuilder func touchSecondaryInk(destructive: Bool = false) -> some View {
        #if os(iOS)
        foregroundStyle(destructive ? Color.red : Color.primary)
        #else
        self
        #endif
    }
}


extension View {
    /// Keep the platform navigation bar and provide explicit TV title ink. Global
    /// UINavigationBarAppearance setters assert in the supported tvOS runtime.
    @ViewBuilder func pictureshowNavigationTitle(_ title: String, opaqueTVTitle: Bool = false) -> some View {
        #if os(tvOS)
        navigationTitle(title)
            // Keep scrolled Form/List rows behind an opaque native bar instead
            // of letting their text show through the principal title.
            .toolbarBackground(Theme.background, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbar {
            ToolbarItem(placement: .principal) {
                if opaqueTVTitle {
                    Text(title).font(.title2).foregroundStyle(.white).lineLimit(1)
                        .frame(maxWidth: .infinity)
                        // tvOS can leave the principal surface transparent despite
                        // toolbarBackground. This changes no measured header size.
                        .background { Theme.background.padding(.horizontal, 16).padding(.vertical, 12) }
                        .accessibilityAddTraits(.isHeader)
                } else {
                    Text(title).font(.title2).foregroundStyle(.white).lineLimit(1)
                        .accessibilityAddTraits(.isHeader)
                }
            }
        }
        #else
        navigationTitle(title)
        #endif
    }
}


#if os(tvOS)
/// Native bordered focus supplies the white platter and lift. Only its focused
/// label needs dark ink; selection/role never make resting labels look disabled.
struct TelevisionActionInk<Content: View>: View {
    @Environment(\.isFocused) private var focused
    @ViewBuilder var content: () -> Content
    var body: some View { content().foregroundStyle(focused ? Theme.background : Color.white) }
}

private struct TelevisionPrimaryFocus: PreferenceKey {
    static let defaultValue = false
    static func reduce(value: inout Bool, nextValue: () -> Bool) { value = nextValue() || value }
}

private struct TelevisionPrimaryLabel<Content: View>: View {
    @Environment(\.isFocused) private var focused
    @ViewBuilder var content: () -> Content
    var body: some View { content().preference(key: TelevisionPrimaryFocus.self, value: focused) }
}

private struct TelevisionProminentAction: View {
    let configuration: PrimitiveButtonStyleConfiguration
    @Environment(\.isEnabled) private var enabled
    @State private var focused = false
    var body: some View {
        Button(role: configuration.role) { configuration.trigger() } label: {
            TelevisionPrimaryLabel { configuration.label }
        }
        .buttonStyle(.bordered).tint(.white).foregroundStyle(Theme.background)
        // Read native focus from the label without adding a competing FocusState.
        // Remove the resting accent completely under native focus or disability.
        .onPreferenceChange(TelevisionPrimaryFocus.self) { focused = $0 }
        .background(enabled && !focused ? Theme.accent : Color.clear, in: Capsule())
    }
}

private struct TelevisionSecondaryActionStyle: PrimitiveButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button(role: configuration.role) { configuration.trigger() } label: {
            TelevisionActionInk { configuration.label }
        }.buttonStyle(.bordered).tint(.white)
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
