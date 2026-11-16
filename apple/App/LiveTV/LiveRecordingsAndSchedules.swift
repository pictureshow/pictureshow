// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

// MARK: - Recordings (LIVE-09)

/// Recordings in progress first, then finished ones; each opens through the normal item page and playback negotiation
/// (resume, versions, tracks). Deleting the recorded file is its own scoped confirmation, never a timer cancel.
struct LiveRecordingsView: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var model: LiveTVModel
    let nowMs: Int64
    @State private var deleting: MediaItem?

    var body: some View {
        let recordings = model.state.recordings
        // The server marks a growing recording either way (IsInProgress or Status InProgress), as the core reads it.
        let growing: (MediaItem) -> Bool = { $0.isInProgress || $0.recordingStatus == "InProgress" }
        let active = recordings.filter(growing)
        let done = recordings.filter { !growing($0) }
        ScrollView {
            LazyVStack(alignment: .leading, spacing: platformValue(tv: 14, touch: 10)) {
                if !active.isEmpty { section("Recording now", active) }
                if !done.isEmpty { section("Recorded", done) }
            }
            .padding(.horizontal, Theme.gutter)
            .padding(.vertical, platformValue(tv: 12, touch: 8))
        }
        #if os(tvOS)
        .scrollClipDisabled()
        #endif
        .confirmationDialog("Delete recording?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                            titleVisibility: .visible, presenting: deleting) { item in
            Button("Delete recording", role: .destructive) { Task { await model.delete(item) } }
            Button("Keep", role: .cancel) {}
        } message: { item in
            Text("“\(LivePresentation.recordingTitle(item))” will be permanently deleted. Recording schedules stay in place.")
        }
    }

    @ViewBuilder private func section(_ title: String, _ items: [MediaItem]) -> some View {
        Text(title).font(platformValue(tv: Font.title3.weight(.semibold), touch: .headline))
            .padding(.top, 8)
            .accessibilityAddTraits(.isHeader)
        ForEach(items) { item in row(item) }
    }

    private func canDelete(_ item: MediaItem) -> Bool {
        guard let policy = model.state.policy else { return item.canDelete == true }
        return item.canDelete == true || (policy.deleteContent && item.canDelete != false)
    }

    private func row(_ item: MediaItem) -> some View {
        let time = LiveFormat.time(nowMs: nowMs)
        let title = LivePresentation.recordingTitle(item)
        let meta = LivePresentation.recordingMetadata(item, time: time)
        let status = item.recordingStatus.map(LiveRules.recordingStatus)
        let progress = LivePresentation.recordingProgress(item)
        return NavigationLink(value: Route.item(item)) {
            HStack(spacing: platformValue(tv: 20, touch: 12)) {
                ArtworkImage(url: app.account.flatMap { app.client(for: $0).artworkURL(account: $0, item: item, kind: .wide, width: 400) },
                             placeholderSymbol: "recordingtape", contentMode: .fill, fallbackText: title)
                    .frame(width: platformValue(tv: 240, touch: 112), height: platformValue(tv: 135, touch: 63))
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(platformValue(tv: Font.headline, touch: .body.weight(.semibold))).lineLimit(2)
                    if !meta.isEmpty { LiveSecondary(text: meta, lines: 2) }
                    HStack(spacing: 8) {
                        if let status { LiveStatusBadge(label: status, tone: LiveRules.recordingTone(item.recordingStatus)) }
                    }
                    if let progress { ProgressStrip(value: progress).frame(maxWidth: 260) }
                }
                Spacer(minLength: 0)
            }
            .padding(platformValue(tv: 16, touch: 8))
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        #if os(tvOS)
        .buttonStyle(LiveTVRowStyle())
        #else
        .buttonStyle(.plain)
        #endif
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([title, meta, status].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: ", "))
        .accessibilityAddTraits(.isButton)
        .accessibilityActions {
            if canDelete(item) { Button("Delete recording") { deleting = item } }
        }
        .contextMenu {
            Button { app.play(item) } label: { Label("Play", systemImage: "play.fill") }
            if canDelete(item) {
                Button(role: .destructive) { deleting = item } label: { Label("Delete recording…", systemImage: "trash") }
            }
        }
    }
}

// MARK: - Schedules (LIVE-07/08)

/// Every timer and series rule the viewer can manage, in the core's sections, each with its status badge and a
/// cancel that says whether it removes one airing or the whole rule.
struct LiveSchedulesView: View {
    @EnvironmentObject private var model: LiveTVModel
    let nowMs: Int64
    @State private var cancelling: LiveCancelRequest?
    @State private var editor: LiveEditorRequest?

    var body: some View {
        let groups = LivePresentation.scheduleGroups(timers: model.state.timers, series: model.state.series, nowMs: nowMs)
        ScrollView {
            LazyVStack(alignment: .leading, spacing: platformValue(tv: 14, touch: 10)) {
                ForEach(groups.filter { !$0.schedules.isEmpty }, id: \.title) { group in
                    Text(group.title).font(platformValue(tv: Font.title3.weight(.semibold), touch: .headline))
                        .padding(.top, 8)
                        .accessibilityAddTraits(.isHeader)
                    ForEach(group.schedules, id: \.id) { schedule in row(schedule, series: group.series) }
                }
            }
            .padding(.horizontal, Theme.gutter)
            .padding(.vertical, platformValue(tv: 12, touch: 8))
        }
        #if os(tvOS)
        .scrollClipDisabled()
        #endif
        .sheet(item: $editor) { LiveScheduleEditor(request: $0).environmentObject(model) }
        .confirmationDialog(cancelling?.title ?? "", isPresented: Binding(get: { cancelling != nil }, set: { if !$0 { cancelling = nil } }),
                            titleVisibility: .visible, presenting: cancelling) { request in
            Button(request.action, role: .destructive) { Task { await model.cancel(request.schedule, series: request.series) } }
            Button("Keep", role: .cancel) {}
        } message: { request in Text(request.message) }
    }

    private func row(_ schedule: LiveSchedule, series: Bool) -> some View {
        let time = LiveFormat.time(nowMs: nowMs)
        let status = LivePresentation.scheduleStatus(schedule, series: series)
        let tone: RecordingTone = series ? .neutral : LiveRules.recordingTone(schedule.status)
        let when: String? = series ? seriesLine(schedule) :
            schedule.startDate.map { "\(LiveFormat.day(LiveRules.instant($0) ?? nowMs)) · \(time.range($0, schedule.endDate ?? ""))" }
        let detail = [schedule.channelName, when].compactMap { $0 }.joined(separator: " · ")
        let error = schedule.errorMessage
        let content = HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(schedule.name).font(platformValue(tv: Font.headline, touch: .body.weight(.semibold))).lineLimit(2)
                if !detail.isEmpty { LiveSecondary(text: detail, lines: 2) }
                if let error, !error.isEmpty { LiveSecondary(text: error, lines: 2) }
            }
            Spacer(minLength: 8)
            LiveStatusBadge(label: status, tone: tone)
        }
        .padding(platformValue(tv: 18, touch: 10))
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        let cancel = series ? LiveCancelRequest.series(schedule, name: schedule.name) : LiveCancelRequest.occurrence(schedule, name: schedule.name)
        let cancellable = !LiveRules.recordingStatus(schedule.status).lowercased().contains("completed")
        return Menu {
            Button { editor = LiveEditorRequest(original: schedule, program: nil, series: series, existing: true) } label: {
                Label(series ? "Edit series rule" : "Edit recording", systemImage: "slider.horizontal.3")
            }
            if cancellable {
                Button(role: .destructive) { cancelling = cancel } label: { Label(cancel.action, systemImage: "xmark.circle") }
            }
            if !series, let rule = schedule.seriesTimerId, !rule.isEmpty, let parent = model.state.series.first(where: { $0.id == rule }) {
                Button(role: .destructive) { cancelling = .series(parent, name: parent.name) } label: { Label("Cancel series", systemImage: "xmark.circle") }
            }
        } label: { content }
        #if os(tvOS)
        .buttonStyle(LiveTVRowStyle())
        #else
        .buttonStyle(.plain)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Theme.surface))
        #endif
        .disabled(model.state.writing)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([schedule.name, detail, status, error].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: ", "))
        .accessibilityAddTraits(.isButton)
        .accessibilityHint("Shows edit and cancel")
    }

    private func seriesLine(_ schedule: LiveSchedule) -> String {
        var parts: [String] = []
        parts.append(schedule.boolean("RecordNewOnly") == true ? "New episodes" : "All episodes")
        parts.append(schedule.boolean("RecordAnyChannel") == true ? "Any channel" : "This channel")
        if case .array(let days)? = schedule.fields["Days"] {
            parts.append(LivePresentation.recordingDaysLabel(Set(days.compactMap(\.stringValue))))
        }
        return parts.joined(separator: " · ")
    }
}
