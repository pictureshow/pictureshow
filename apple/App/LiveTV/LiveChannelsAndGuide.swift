// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

// MARK: - Channels (LIVE-02)

/// One focusable row per channel: mark, name, now and next. Activation tunes; focus and scrolling never do.
struct LiveChannelsList: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var model: LiveTVModel
    let channels: [LiveChannel]
    let nowMs: Int64
    @Binding var preview: String?

    var body: some View {
        let state = model.state
        let canAct = state.actionsAvailable(nowMs: nowMs)
        ScrollView {
            LazyVStack(alignment: .leading, spacing: platformValue(tv: 14, touch: 0)) {
                ForEach(channels) { channel in
                    let shows = state.programs.filter { $0.channelId == channel.id }
                    let now = current(channel, shows)
                    let next = shows.filter { (LiveRules.instant($0.startDate) ?? 0) > nowMs }
                        .min { (LiveRules.instant($0.startDate) ?? 0) < (LiveRules.instant($1.startDate) ?? 0) }
                    row(channel, now: now, next: next, hasPrograms: !shows.isEmpty, canAct: canAct)
                    #if os(iOS)
                    Divider().padding(.leading, Theme.gutter + 52)
                    #endif
                }
            }
            .padding(.vertical, platformValue(tv: 12, touch: 0))
            #if os(tvOS)
            .padding(.horizontal, Theme.gutter)
            #endif
        }
        #if os(tvOS)
        .scrollClipDisabled()
        #endif
    }

    private func current(_ channel: LiveChannel, _ shows: [LiveProgram]) -> LiveProgram? {
        if let program = channel.currentProgram, LiveRules.canTune(program, nowMs: nowMs) { return program }
        return shows.first { LiveRules.canTune($0, nowMs: nowMs) }
    }

    @ViewBuilder private func row(_ channel: LiveChannel, now: LiveProgram?, next: LiveProgram?, hasPrograms: Bool, canAct: Bool) -> some View {
        let time = LiveFormat.time(nowMs: nowMs)
        let unavailable = model.state.offline
        let idle = LivePresentation.channelIdleLabel(hasPrograms: hasPrograms || now != nil, unavailable: unavailable)
        let nowLine = now.map { "\($0.name) · \(time.range($0.startDate ?? "", $0.endDate ?? ""))" } ?? idle
        let nextLine = next.map { "Next: \($0.name) · \(time.clock($0.startDate ?? ""))" }
        let label = [channel.accessibleName, nowLine, nextLine, channel.userData.isFavorite ? "Favorite" : nil].compactMap { $0 }.joined(separator: ", ")
        let content = HStack(spacing: platformValue(tv: 16, touch: 12)) {
            LiveChannelMark(channel: channel)
            VStack(alignment: .leading, spacing: 3) {
                Text(channel.name).font(platformValue(tv: Font.headline, touch: .body.weight(.semibold))).lineLimit(1)
                LiveSecondary(text: nowLine)
                if let nextLine { LiveSecondary(text: nextLine) }
            }
            Spacer(minLength: 8)
            if channel.userData.isFavorite {
                Image(systemName: "heart.fill").foregroundStyle(Theme.accent).accessibilityHidden(true)
            }
        }
        .padding(.horizontal, platformValue(tv: 24, touch: Theme.gutter))
        .padding(.vertical, platformValue(tv: 16, touch: 10))
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())

        Button { app.tune(channel) } label: { content }
            .disabled(!canAct)
            #if os(tvOS)
            .buttonStyle(LiveTVRowStyle())
            .onMoveFocus(channel, preview: preview(channel, now: now, idle: idle))
            #else
            .buttonStyle(.plain)
            .opacity(canAct ? 1 : 0.38)
            #endif
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(label)
            .accessibilityHint(canAct ? "Plays this channel" : "Refresh the guide before tuning")
            .accessibilityAddTraits(.isButton)
            .contextMenu {
                Button { app.tune(channel) } label: { Label("Watch", systemImage: "play.fill") }.disabled(!canAct)
                if model.state.policy?.userData ?? false {
                    Button { Task { await model.favorite(channel) } } label: {
                        Label(channel.userData.isFavorite ? "Remove from favorites" : "Add to favorites",
                              systemImage: channel.userData.isFavorite ? "heart.slash" : "heart")
                    }
                }
            }
            #if os(iOS)
            .swipeActions {
                if model.state.policy?.userData ?? false {
                    Button { Task { await model.favorite(channel) } } label: { Label("Favorite", systemImage: channel.userData.isFavorite ? "heart.slash" : "heart") }
                        .tint(Theme.accent)
                }
            }
            #endif
    }

    private func preview(_ channel: LiveChannel, now: LiveProgram?, idle: String) -> String {
        if let now { return LiveRules.previewLine(program: now, channelName: channel.name, nowMs: nowMs) }
        return "\(channel.name): \(idle)"
    }
}

#if os(tvOS)
private extension View {
    /// Shows a row's preview line in the TV header while it has focus; focus never tunes.
    func onMoveFocus(_ channel: LiveChannel, preview: String) -> some View { modifier(LivePreviewOnFocus(text: preview)) }
}
#endif

/// Writes a preview line into the header while this view has focus (tvOS only does anything).
struct LivePreviewOnFocus: ViewModifier {
    let text: String
    @Environment(\.livePreview) private var setPreview
    @FocusState private var focused: Bool
    func body(content: Content) -> some View {
        content.focused($focused).onChange(of: focused) { _, now in if now { setPreview(text) } }
            .onChange(of: text) { _, value in if focused { setPreview(value) } }
    }
}

private struct LivePreviewKey: EnvironmentKey { static let defaultValue: (String) -> Void = { _ in } }
extension EnvironmentValues {
    var livePreview: (String) -> Void {
        get { self[LivePreviewKey.self] }
        set { self[LivePreviewKey.self] = newValue }
    }
}

// MARK: - Guide (LIVE-03, PD03)

/// The bounded three-hour guide. TV: a fixed channel column and one time axis for every lane, ruler, now-line, overlaps
/// in lanes, gaps empty, Earlier/day/Later/Now. Touch: the same grid scrolling sideways under a shared ruler, or a List.
struct LiveGuide: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var model: LiveTVModel
    let channels: [LiveChannel]
    let list: Bool
    let clock: Int64
    @Binding var preview: String?
    @State private var choosingDay = false
    @State private var offsetX: CGFloat = 0

    private var start: Int64 { model.state.windowStart }
    private var end: Int64 { start + LiveTVModel.window }
    private var time: LiveTime { LiveFormat.time(nowMs: clock) }

    #if os(tvOS)
    static let column: CGFloat = 340
    static let lane: CGFloat = 86
    static let ruler: CGFloat = 56
    #else
    static let lane: CGFloat = 64
    static let ruler: CGFloat = 32
    #endif

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            dateBar
            if list { linear } else { grid }
        }
        .environment(\.livePreview, { preview = $0 })
        .confirmationDialog("Guide day", isPresented: $choosingDay, titleVisibility: .visible) {
            ForEach(0..<7, id: \.self) { day in
                let target = dayStart(day)
                Button(LiveFormat.day(target)) { model.refresh(start: target) }
            }
        }
    }

    /// Earlier, the day picker and Later together, plus a permanent Now slot (round 2/3).
    private var dateBar: some View {
        let enabled = !model.state.loading
        let atNow = start == LivePresentation.guideWindowStart(nowMs: clock, zone: .current)
        return HStack(spacing: platformValue(tv: 12, touch: 4)) {
            Button { model.refresh(start: start - LiveTVModel.window) } label: { Image(systemName: "chevron.left") }
                .accessibilityLabel("Earlier")
            Button { choosingDay = true } label: {
                Text(platformValue(tv: "\(LiveFormat.day(start)) ▾", touch: "\(LiveFormat.day(start)) · \(time.clock(LiveTVModel.iso(start))) ▾"))
                    .lineLimit(1)
                    #if os(iOS)
                    .frame(maxWidth: .infinity)
                    #endif
            }
            .accessibilityLabel("Guide day, \(LiveFormat.day(start))")
            Button { model.refresh(start: start + LiveTVModel.window) } label: { Image(systemName: "chevron.right") }
                .accessibilityLabel("Later")
            Button("Now") { model.refresh(start: LivePresentation.guideWindowStart(nowMs: clock, zone: .current)) }
                .disabled(!LivePresentation.guideNowEnabled(tv: platformValue(tv: true, touch: false), enabled: enabled, atNow: atNow))
        }
        .disabled(!enabled)
        #if os(iOS)
        .buttonStyle(.borderless)
        .frame(maxWidth: 400)
        .padding(.horizontal, Theme.gutter)
        .frame(height: 44)
        #else
        .secondaryAction()
        .fixedSize() // the TV buttons keep their whole labels
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Theme.gutter)
        .focusSection()
        #endif
    }

    private func dayStart(_ day: Int) -> Int64 {
        if day == 0 { return LivePresentation.guideWindowStart(nowMs: clock, zone: .current) }
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: Date(timeIntervalSince1970: Double(clock) / 1000))
        let date = calendar.date(byAdding: .day, value: day, to: today) ?? today
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    // MARK: Grid

    private var grid: some View {
        GeometryReader { geo in
            #if os(tvOS)
            let canvas = max(600, geo.size.width - Self.column - Theme.gutter * 2)
            let columnWidth = Self.column
            #else
            let wide = geo.size.width >= 840
            let columnWidth: CGFloat = wide ? 200 : 80
            let canvas = max(1080, geo.size.width - columnWidth)
            #endif
            let rows = channels.map { channel in
                (channel, LivePresentation.guideCells(model.state.programs.filter { $0.channelId == channel.id }, startMs: start, endMs: end))
            }
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 0) {
                    Color.clear.frame(width: columnWidth, height: Self.ruler)
                    ruler(width: canvas)
                        .frame(width: canvas, height: Self.ruler, alignment: .leading)
                        .offset(x: -offsetX)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .clipped()
                }
                #if os(tvOS)
                .padding(.horizontal, Theme.gutter)
                #endif
                ScrollView(.vertical) {
                    HStack(alignment: .top, spacing: 0) {
                        LazyVStack(spacing: 0) {
                            ForEach(rows, id: \.0.id) { channel, cells in
                                channelCell(channel, cells: cells, width: columnWidth)
                                    .frame(height: laneHeight(cells))
                            }
                        }
                        .frame(width: columnWidth)
                        #if os(tvOS)
                        lanes(rows, canvas: canvas)
                        #else
                        ScrollView(.horizontal, showsIndicators: false) {
                            lanes(rows, canvas: canvas)
                        }
                        .onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.x } action: { _, x in offsetX = x }
                        #endif
                    }
                    #if os(tvOS)
                    .padding(.horizontal, Theme.gutter)
                    .padding(.vertical, 10)
                    #endif
                }
                #if os(tvOS)
                .scrollClipDisabled()
                #endif
            }
        }
    }

    private func laneHeight(_ cells: [LiveGuideCell]) -> CGFloat {
        CGFloat(max(1, (cells.map(\.lane).max() ?? 0) + 1)) * Self.lane
    }

    private func ruler(width: CGFloat) -> some View {
        ZStack(alignment: .topLeading) {
            ForEach(0..<6, id: \.self) { tick in
                let ms = start + Int64(tick) * 30 * 60 * 1000
                let x = width * CGFloat(tick) / 6
                VStack(alignment: .leading, spacing: 2) {
                    Text(time.clock(LiveTVModel.iso(ms))).font(platformValue(tv: Font.callout, touch: .caption2).monospacedDigit())
                        .foregroundStyle(Theme.muted).lineLimit(1)
                        .padding(.leading, 6)
                    Rectangle().fill(Theme.muted.opacity(0.5)).frame(width: 1, height: 6)
                }
                .offset(x: x)
            }
            nowLine(width: width, height: Self.ruler)
        }
        .accessibilityHidden(true)
    }

    @ViewBuilder private func nowLine(width: CGFloat, height: CGFloat?) -> some View {
        if clock >= start && clock < end {
            Rectangle().fill(Theme.accent).frame(width: 2).frame(maxHeight: height ?? .infinity)
                .offset(x: width * CGFloat(clock - start) / CGFloat(end - start))
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }

    @ViewBuilder private func channelCell(_ channel: LiveChannel, cells: [LiveGuideCell], width: CGFloat) -> some View {
        let canAct = model.state.actionsAvailable(nowMs: clock)
        let idle = LivePresentation.channelIdleLabel(hasPrograms: !cells.isEmpty, unavailable: model.state.offline)
        let current = cells.map(\.program).first { LiveRules.canTune($0, nowMs: clock) }
        let content = HStack(spacing: platformValue(tv: 16, touch: 6)) {
            LiveChannelMark(channel: channel, size: platformValue(tv: 54, touch: 34), showNumber: width < 120)
            if width >= 120 {
                VStack(alignment: .leading, spacing: 2) {
                    Text(channel.name).font(platformValue(tv: Font.callout.weight(.semibold), touch: .footnote.weight(.semibold))).lineLimit(2)
                    if let number = channel.number { LiveSecondary(text: number) }
                }
                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, platformValue(tv: 12, touch: 4))
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        Button { app.tune(channel) } label: { content }
            .disabled(!canAct)
            #if os(tvOS)
            .buttonStyle(LiveTVRowStyle(corner: 10))
            .padding(4)
            .modifier(LivePreviewOnFocus(text: current.map { LiveRules.previewLine(program: $0, channelName: channel.name, nowMs: clock) } ?? "\(channel.name): \(idle)"))
            #else
            .buttonStyle(.plain)
            .opacity(canAct ? 1 : 0.38)
            #endif
            .accessibilityLabel("\(channel.accessibleName), \(current?.name ?? idle)")
            .accessibilityHint("Plays this channel")
    }

    private func lanes(_ rows: [(LiveChannel, [LiveGuideCell])], canvas: CGFloat) -> some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            ForEach(rows, id: \.0.id) { channel, cells in
                ZStack(alignment: .topLeading) {
                    Rectangle().fill(Color.primary.opacity(0.04)).frame(width: canvas, height: laneHeight(cells))
                        .overlay(alignment: .bottom) { Rectangle().fill(Color.primary.opacity(0.06)).frame(height: 1) }
                    if cells.isEmpty {
                        emptyLane(channel, width: canvas)
                    }
                    ForEach(cells, id: \.program.id) { cell in
                        programCell(cell, channel: channel, canvas: canvas)
                    }
                }
                .frame(width: canvas, height: laneHeight(cells), alignment: .topLeading)
            }
        }
        .frame(width: canvas)
        .overlay(alignment: .topLeading) { nowLine(width: canvas, height: nil) }
    }

    /// A channel with no programmes in the window: one honest cell, so the remote still has somewhere to go.
    private func emptyLane(_ channel: LiveChannel, width: CGFloat) -> some View {
        let label = LivePresentation.guideEmptyMessage(unavailable: model.state.offline)
        return Button { app.tune(channel) } label: {
            Text(label).font(platformValue(tv: Font.callout, touch: .caption)).foregroundStyle(Theme.muted)
                .padding(.horizontal, 12)
                .frame(width: width - 8, height: Self.lane - 8, alignment: .leading)
        }
        .disabled(!model.state.actionsAvailable(nowMs: clock))
        #if os(tvOS)
        .buttonStyle(LiveTVRowStyle(corner: 8))
        .modifier(LivePreviewOnFocus(text: "\(channel.name): \(label)"))
        #else
        .buttonStyle(.plain)
        #endif
        .padding(4)
        .accessibilityLabel("\(channel.accessibleName), \(label)")
    }

    private func programCell(_ cell: LiveGuideCell, channel: LiveChannel, canvas: CGFloat) -> some View {
        let program = cell.program
        let x = canvas * CGFloat(cell.startFraction)
        let width = max(2, canvas * CGFloat(cell.widthFraction))
        let showsText = LivePresentation.guideShowsText(width: Double(width))
        let range = time.range(program.startDate ?? "", program.endDate ?? "")
        let scheduled = program.hasRecordingTimer
        let label = [program.name, channel.name, range, LivePresentation.programEpisodeLabel(program), scheduled ? "Recording scheduled" : nil]
            .compactMap { $0 }.joined(separator: ", ")
        return NavigationLink(value: LiveRoute.program(program)) {
            ZStack(alignment: .topTrailing) {
                VStack(alignment: .leading, spacing: 2) {
                    if showsText {
                        Text(program.name).font(platformValue(tv: Font.callout.weight(.semibold), touch: .caption.weight(.semibold))).lineLimit(platformValue(tv: 1, touch: 2))
                        LiveSecondary(text: range)
                    }
                }
                .padding(.horizontal, platformValue(tv: 12, touch: 6))
                .frame(width: max(2, width - 4), height: Self.lane - 8, alignment: .leading)
                if scheduled {
                    Circle().fill(LiveStatusBadge.liveRed).frame(width: 6, height: 6).padding(6).accessibilityHidden(true)
                }
            }
            #if os(iOS)
            .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(LiveRules.canTune(program, nowMs: clock) ? Theme.accent.opacity(0.22) : Theme.surface))
            #endif
        }
        #if os(tvOS)
        .buttonStyle(LiveTVRowStyle(corner: 8))
        .modifier(LivePreviewOnFocus(text: LiveRules.previewLine(program: program, channelName: channel.name, nowMs: clock)))
        #else
        .buttonStyle(.plain)
        #endif
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityAddTraits(.isButton)
        .offset(x: x + 2, y: CGFloat(cell.lane) * Self.lane + 4)
    }

    // MARK: List alternative

    /// The linear reader alternative: every channel and programme in order, scheduled state as text.
    private var linear: some View {
        List {
            ForEach(channels) { channel in
                let shows = LivePresentation.guideCells(model.state.programs.filter { $0.channelId == channel.id }, startMs: start, endMs: end).map(\.program)
                Section {
                    if shows.isEmpty {
                        Text(LivePresentation.guideEmptyMessage(unavailable: model.state.offline)).foregroundStyle(Theme.muted)
                    }
                    ForEach(shows) { program in
                        NavigationLink(value: LiveRoute.program(program)) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(program.name).font(.body.weight(.semibold))
                                LiveSecondary(text: [time.range(program.startDate ?? "", program.endDate ?? ""),
                                                     LiveRules.canTune(program, nowMs: clock) ? "On now" : nil,
                                                     program.hasRecordingTimer ? "Recording scheduled" : nil].compactMap { $0 }.joined(separator: " · "))
                            }
                            .accessibilityElement(children: .combine)
                        }
                        .modifier(LivePreviewOnFocus(text: LiveRules.previewLine(program: program, channelName: channel.name, nowMs: clock)))
                    }
                } header: {
                    Text(channel.accessibleName)
                }
            }
        }
        #if os(iOS)
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        #endif
    }
}
