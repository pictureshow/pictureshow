// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

/// The Live TV destination: Channels, Guide, Recordings and Schedules for the signed-in viewer (LIVE-01).
/// Opened from the Libraries card on iPhone/iPad (Libraries stays selected), Home on Apple TV, or any server view typed
/// `livetv`. The model is owned by this screen and its account, so nothing is shared with another viewer.
struct LiveTVScreen: View {
    @EnvironmentObject private var app: AppModel
    let account: Account

    var body: some View {
        LiveTVContent(account: account, service: app.liveService(for: account))
            .id(account.key)
    }
}

private struct LiveTVContent: View {
    @EnvironmentObject private var app: AppModel
    @StateObject private var model: LiveTVModel
    @SceneStorage("live.tab") private var tabName = LiveTab.channels.rawValue
    @State private var favoritesOnly = false
    @State private var search = ""
    @State private var guideList = false
    @State private var preview: String?
    @State private var clock = LiveFormat.nowMs()

    init(account: Account, service: LiveTVService) {
        _model = StateObject(wrappedValue: LiveTVModel(account: account, service: service))
    }

    private var tab: LiveTab { LiveTab(rawValue: tabName) ?? .channels }
    private var state: LiveTVModel.State { model.state }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            if state.writing || (state.loading && state.fetchedAtMs != nil) {
                ProgressView().progressViewStyle(.linear).tint(Theme.accent).frame(height: 2).padding(.horizontal, Theme.gutter)
            }
            content
        }
        .pageBackground()
        .environmentObject(model)
        #if os(iOS)
        .navigationTitle("Live TV")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text("Live TV").font(.headline)
                    if let fresh = freshness { Text(fresh).font(.caption2).foregroundStyle(Theme.muted) }
                }
                .accessibilityElement(children: .combine)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { model.refresh() } label: { Image(systemName: "arrow.clockwise") }
                    .accessibilityLabel("Refresh")
                    .disabled(state.loading || denied)
            }
        }
        .refreshable { model.refresh(); await model.waitForLoad() }
        #else
        .toolbar(.hidden, for: .navigationBar)
        #endif
        .navigationDestination(for: LiveRoute.self) { route in
            switch route {
            case .program(let program): LiveProgramDetail(program: program).environmentObject(model)
            }
        }
        .task { if state.fetchedAtMs == nil { model.refresh() } }
        // LIVE-11: one clock drives freshness labels, the now-line and the quiet four-minute refresh.
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                clock = LiveFormat.nowMs()
                if tab == .guide || tab == .channels { model.quietRefreshIfDue(at: clock) }
            }
        }
        .alert(state.notice ?? "", isPresented: Binding(get: { state.notice != nil }, set: { if !$0 { model.clearNotice() } })) {
            Button("OK", role: .cancel) { model.clearNotice() }
        }
    }

    private var denied: Bool { state.policy.map { !$0.access } ?? false }
    private var time: LiveTime { LiveFormat.time(nowMs: clock) }
    private var freshness: String? { LivePresentation.guideFreshness(fetchedAtMs: state.fetchedAtMs, offline: state.offline, time: time) }

    // MARK: Header

    @ViewBuilder private var header: some View {
        #if os(tvOS)
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline, spacing: 24) {
                Text("Live TV").font(.title2.weight(.bold))
                if let fresh = freshness { Text(fresh).font(.callout).foregroundStyle(Theme.muted) }
                Spacer()
            }
            // PD03: the focused programme under the title, led by where and when it is.
            Text(preview ?? " ").font(.title3.weight(.semibold)).lineLimit(1)
                .foregroundStyle(.primary)
                .accessibilityHidden(preview == nil)
            if !denied {
                HStack(spacing: 18) {
                    ForEach(LiveTab.allCases) { item in
                        Button { select(item) } label: {
                            Text(item.rawValue).font(.callout.weight(.semibold))
                        }
                        .buttonStyle(LiveTabStyle(selected: item == tab))
                        .fixedSize() // never hyphenate a tab name to make room for the filters
                    }
                    Spacer()
                    filters
                }
                .focusSection()
            }
        }
        .padding(.horizontal, Theme.gutter)
        .padding(.top, 36).padding(.bottom, 12)
        #else
        VStack(alignment: .leading, spacing: 10) {
            if !denied {
                Picker("Live TV section", selection: Binding(get: { tab }, set: { select($0) })) {
                    ForEach(LiveTab.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                filters
            }
        }
        .padding(.horizontal, Theme.gutter)
        .padding(.top, 8).padding(.bottom, 8)
        #endif
        if let warning = warningText {
            LiveWarning(text: warning, retry: state.loading ? nil : { model.refresh() })
                .padding(.horizontal, Theme.gutter).padding(.bottom, 8)
        }
    }

    /// LIVE-11 and round 3: a failed read with cached content keeps it, with "Couldn't reach the server" and the last
    /// fetch; a stale snapshot says it's out of date. Denied access has its own empty state instead.
    private var warningText: String? {
        guard !denied else { return nil }
        if state.offline, hasContent {
            let last = state.fetchedAtMs.map { " Last loaded \(time.clock(ms: $0))." } ?? ""
            return "Couldn't reach the server.\(last) Tuning and scheduling stay off until a refresh succeeds."
        }
        return state.core.guideReferenceNotice(nowMs: clock)
    }

    private var hasContent: Bool {
        switch tab {
        case .channels, .guide: return !state.channels.isEmpty
        case .recordings: return !state.recordings.isEmpty
        case .schedules: return !state.timers.isEmpty || !state.series.isEmpty
        }
    }

    @ViewBuilder private var filters: some View {
        HStack(spacing: platformValue(tv: 18, touch: 10)) {
            if tab == .channels || tab == .guide {
                Button { favoritesOnly.toggle() } label: { Label("Favorites", systemImage: favoritesOnly ? "heart.fill" : "heart") }
                    .accessibilityAddTraits(favoritesOnly ? .isSelected : [])
                    #if os(iOS)
                    .buttonStyle(.bordered)
                    .tint(favoritesOnly ? Theme.accent : .secondary)
                    #else
                    .secondaryAction()
                    .fixedSize() // the header row would otherwise squeeze the label out of the button
                    #endif
                    .accessibilityValue(favoritesOnly ? "On" : "Off")
            }
            if tab == .guide {
                Picker("Guide layout", selection: $guideList) {
                    Text("Grid").tag(false)
                    Text("List").tag(true)
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: platformValue(tv: 320, touch: 160))
            }
            #if os(iOS)
            if tab == .channels {
                TextField("Search channels", text: $search)
                    .textFieldStyle(.roundedBorder)
                    .submitLabel(.search)
                    .autocorrectionDisabled()
            }
            #else
            if tab == .channels {
                TextField("Search channels", text: $search).frame(minWidth: 220, maxWidth: 340)
            }
            #endif
            #if os(tvOS)
            Button { model.refresh() } label: { Label("Refresh", systemImage: "arrow.clockwise") }
                .labelStyle(.iconOnly)
                .secondaryAction()
                .fixedSize()
                .disabled(state.loading)
            #endif
        }
    }

    private func select(_ next: LiveTab) {
        tabName = next.rawValue
        preview = nil
    }

    // MARK: Content

    @ViewBuilder private var content: some View {
        if state.loading && state.fetchedAtMs == nil && !denied && state.error == nil {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let empty = LivePresentation.emptyPresentation(state: state.core, tab: tab.rawValue, filtered: filteredChannels.count, favorites: favoritesOnly, search: search),
                  !empty.showGuideNavigation {
            ScrollView { LiveEmptyView(presentation: empty, act: perform) }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        } else {
            switch tab {
            case .channels: LiveChannelsList(channels: filteredChannels, nowMs: clock, preview: $preview)
            case .guide:
                LiveGuide(channels: filteredChannels, list: guideList, clock: clock, preview: $preview)
            case .recordings: LiveRecordingsView(nowMs: clock)
            case .schedules: LiveSchedulesView(nowMs: clock)
            }
        }
    }

    private var filteredChannels: [LiveChannel] {
        let query = search.trimmingCharacters(in: .whitespaces).lowercased()
        return state.channels.filter { channel in
            (!favoritesOnly || channel.userData.isFavorite)
                && (query.isEmpty || channel.name.lowercased().contains(query) || (channel.number ?? "").lowercased().contains(query))
        }
    }

    private func perform(_ action: LiveEmptyAction) {
        switch action {
        case .retry: model.refresh()
        case .channels: select(.channels)
        case .guide: select(.guide)
        case .allChannels, .clearFilters: favoritesOnly = false; search = ""
        case .back: dismissLive()
        }
    }

    @Environment(\.dismiss) private var dismiss
    private func dismissLive() { dismiss() }
}

#if os(tvOS)
/// Tab buttons on the TV header: the selected one keeps an underline whether or not it has focus.
struct LiveTabStyle: ButtonStyle {
    let selected: Bool
    func makeBody(configuration: Configuration) -> some View { LiveTabBody(configuration: configuration, selected: selected) }
}

private struct LiveTabBody: View {
    let configuration: ButtonStyleConfiguration
    let selected: Bool
    @Environment(\.isFocused) private var focused
    var body: some View {
        configuration.label
            .padding(.horizontal, 26).padding(.vertical, 12)
            .foregroundStyle(focused ? Color.black : (selected ? Color.white : Color(red: 0.765, green: 0.788, blue: 0.847)))
            .background(Capsule().fill(focused ? Color.white : (selected ? Color.white.opacity(0.18) : .clear)))
            .overlay(alignment: .bottom) {
                if selected && !focused { Capsule().fill(Theme.accent).frame(width: 28, height: 3).offset(y: 2) }
            }
            .scaleEffect(focused ? 1.06 : 1)
            .animation(.easeOut(duration: 0.12), value: focused)
            .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
#endif
