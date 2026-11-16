// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

/// One programme (LIVE-06): what, where and when; Watch while it airs; Record once or the series per the viewer's
/// rights; an existing timer shown with its cancel. Every write goes through the editor's review step.
struct LiveProgramDetail: View {
    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var model: LiveTVModel
    @State var program: LiveProgram
    @State private var editor: LiveEditorRequest?
    @State private var confirmCancel: LiveCancelRequest?
    @State private var clock = LiveFormat.nowMs()

    init(program: LiveProgram) { _program = State(initialValue: program) }

    private var time: LiveTime { LiveFormat.time(nowMs: clock) }
    private var channel: LiveChannel? { model.state.channels.first { $0.id == program.channelId } }
    private var policy: LivePolicy? { model.state.policy }
    private var fresh: Bool { model.state.actionsAvailable(nowMs: clock) }
    private var timer: LiveSchedule? {
        guard let id = program.timerId, !id.isEmpty else { return nil }
        return model.state.timers.first { $0.id == id } ?? LiveSchedule(fields: ["Id": .string(id), "Name": .string(program.name)])
    }
    private var seriesRule: LiveSchedule? {
        guard let id = program.seriesTimerId, !id.isEmpty else { return nil }
        return model.state.series.first { $0.id == id } ?? LiveSchedule(fields: ["Id": .string(id), "Name": .string(program.name)])
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: platformValue(tv: 22, touch: 16)) {
                header
                actions
                if let overview = program.overview, !overview.isEmpty {
                    Text(overview).font(platformValue(tv: Font.body, touch: .body)).foregroundStyle(Theme.muted)
                        .frame(maxWidth: platformValue(tv: 1100, touch: 700), alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if !fresh, let notice = model.state.core.guideReferenceNotice(nowMs: clock) {
                    LiveWarning(text: notice, retry: { model.refresh() })
                }
            }
            .padding(.horizontal, Theme.gutter)
            .padding(.vertical, platformValue(tv: 48, touch: 16))
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .pageBackground()
        #if os(iOS)
        .navigationTitle(program.name)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task {
            if let latest = await model.freshProgram(program.id) { program = latest }
        }
        .onChange(of: model.state.writeVersion) {
            Task { if let latest = await model.freshProgram(program.id) { program = latest } }
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                clock = LiveFormat.nowMs()
            }
        }
        .sheet(item: $editor) { request in
            LiveScheduleEditor(request: request).environmentObject(model)
        }
        .confirmationDialog(confirmCancel?.title ?? "", isPresented: Binding(get: { confirmCancel != nil }, set: { if !$0 { confirmCancel = nil } }),
                            titleVisibility: .visible, presenting: confirmCancel) { request in
            Button(request.action, role: .destructive) { Task { await model.cancel(request.schedule, series: request.series) } }
            Button("Keep", role: .cancel) {}
        } message: { request in
            Text(request.message)
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let channel {
                HStack(spacing: 12) {
                    LiveChannelMark(channel: channel, size: platformValue(tv: 56, touch: 32), showNumber: false)
                    Text(channel.accessibleName).font(platformValue(tv: Font.headline, touch: .subheadline.weight(.semibold))).foregroundStyle(Theme.muted)
                }
            }
            Text(program.name).font(platformValue(tv: Font.title2.weight(.bold), touch: .title2.weight(.bold)))
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            if let episode = LivePresentation.programEpisodeLabel(program) {
                Text(episode).font(.headline).foregroundStyle(.primary)
            }
            Text(LivePresentation.programTimeLabel(range: time.range(program.startDate ?? "", program.endDate ?? ""),
                                                   start: program.startDate, end: program.endDate, nowMs: clock))
                .font(.subheadline).foregroundStyle(Theme.muted)
            HStack(spacing: 8) {
                if LiveRules.canTune(program, nowMs: clock) { LiveStatusBadge(label: "On now", tone: .live) }
                if program.hasRecordingTimer {
                    LiveStatusBadge(label: LivePresentation.programRecordingLabel(status: timer?.status ?? "New"), tone: LiveRules.recordingTone(timer?.status))
                }
                if program.seriesTimerId?.isEmpty == false { LiveStatusBadge(label: "Series rule", tone: .neutral) }
                if program.isRepeat == true { LiveStatusBadge(label: "Repeat", tone: .muted) }
            }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var actions: some View {
        let manage = policy?.manage ?? false
        let creatable = LivePresentation.programCreationAvailable(endISO: program.endDate, nowMs: clock)
        let ended = LivePresentation.programEnded(endISO: program.endDate, nowMs: clock)
        let layout = platformValue(tv: AnyLayout(HStackLayout(spacing: 24)), touch: AnyLayout(VStackLayout(alignment: .leading, spacing: 10)))
        layout {
            if LiveRules.canTune(program, nowMs: clock), let channel {
                Button { app.tune(channel) } label: { Label("Watch", systemImage: "play.fill") }
                    .primaryAction()
                    .disabled(!fresh)
                    #if os(tvOS)
                    .prefersDefaultFocus(in: namespace)
                    #endif
            }
            if manage {
                if let timer {
                    Button { editor = LiveEditorRequest(original: timer, program: program, series: false, existing: true) } label: {
                        Label("Edit recording", systemImage: "slider.horizontal.3")
                    }
                    .secondaryAction().disabled(!fresh || model.state.writing)
                    Button { confirmCancel = .occurrence(timer, name: program.name) } label: {
                        DestructiveLabel("Cancel recording", systemImage: "xmark.circle")
                    }
                    .secondaryAction().disabled(model.state.writing)
                } else if creatable && !ended {
                    Button { Task { await openDraft(series: false) } } label: { Label("Record once", systemImage: "record.circle") }
                        .secondaryAction().disabled(!fresh || model.state.writing)
                }
                if let seriesRule {
                    Button { confirmCancel = .series(seriesRule, name: program.name) } label: {
                        DestructiveLabel("Cancel series", systemImage: "xmark.circle")
                    }
                    .secondaryAction().disabled(model.state.writing)
                } else if program.isSeries && creatable && !ended {
                    Button { Task { await openDraft(series: true) } } label: { Label("Record series", systemImage: "square.stack.3d.down.right") }
                        .secondaryAction().disabled(!fresh || model.state.writing)
                }
            } else if policy != nil {
                Text("Recording is not available for this viewer.").font(.subheadline).foregroundStyle(Theme.muted)
            }
        }
        #if os(tvOS)
        .focusScope(namespace)
        .focusSection()
        #endif
    }

    #if os(tvOS)
    @Namespace private var namespace
    #endif

    private func openDraft(series: Bool) async {
        guard let draft = await model.draft(program: program, series: series) else { return }
        editor = LiveEditorRequest(original: draft, program: program, series: series, existing: false)
    }
}

/// A cancel that names exactly what goes: one airing, or the rule and its future airings (LIVE-07).
struct LiveCancelRequest: Identifiable {
    let schedule: LiveSchedule
    let series: Bool
    let title: String
    let message: String
    let action: String
    var id: String { "\(series):\(schedule.id)" }

    static func occurrence(_ schedule: LiveSchedule, name: String) -> LiveCancelRequest {
        LiveCancelRequest(schedule: schedule, series: false, title: "Cancel this recording?",
                          message: "“\(name)” won't be recorded. Other airings and series rules stay in place.", action: "Cancel recording")
    }

    static func series(_ schedule: LiveSchedule, name: String) -> LiveCancelRequest {
        LiveCancelRequest(schedule: schedule, series: true, title: "Cancel this series rule?",
                          message: "Future airings of “\(name)” won't be recorded. Recordings you already have stay.", action: "Cancel series")
    }
}

struct LiveEditorRequest: Identifiable {
    let original: LiveSchedule
    let program: LiveProgram?
    let series: Bool
    let existing: Bool
    var id: String { "\(series):\(existing):\(original.id):\(original.programId)" }
}

/// Recording options with a review/confirm step (LIVE-06/07). Only fields the server gave are shown; the core's
/// rules validate and keep every untouched provider field.
struct LiveScheduleEditor: View {
    @EnvironmentObject private var model: LiveTVModel
    @Environment(\.dismiss) private var dismiss
    let request: LiveEditorRequest
    @State private var edits: [String: JSONValue] = [:]
    @State private var reviewing = false
    @State private var more = false
    @State private var error: String?

    private var original: LiveSchedule { request.original }
    private func has(_ key: String) -> Bool { original.fields[key] != nil }
    private func int(_ key: String) -> Int { edits[key].flatMap(\.intValue) ?? original.int(key) ?? 0 }
    private func bool(_ key: String) -> Bool { edits[key].flatMap(\.boolValue) ?? original.boolean(key) ?? false }
    private func string(_ key: String) -> String? { edits[key].flatMap(\.stringValue) ?? original.string(key) }
    private var changed: Bool { LivePresentation.scheduleHasChanges(original, edits: edits) }

    var body: some View {
        NavigationStack {
            Form {
                if reviewing { review } else { options }
                if let error {
                    Section { Text(error).foregroundStyle(Theme.warning) }
                }
            }
            #if os(iOS)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
            #endif
        }
        #if os(iOS)
        .presentationDetents([.large])
        #endif
    }

    private var title: String {
        request.existing ? (request.series ? "Series rule" : "Recording") : (request.series ? "Record series" : "Record once")
    }

    @ViewBuilder private var options: some View {
        Section {
            VStack(alignment: .leading, spacing: 4) {
                Text(original.name).font(.headline)
                if let summary = programLine { Text(summary).font(.subheadline).foregroundStyle(Theme.muted) }
            }
            .accessibilityElement(children: .combine)
        } header: {
            #if os(tvOS)
            Text(title)
            #endif
        }
        if has("PrePaddingSeconds") || has("PostPaddingSeconds") {
            Section("Padding") {
                if has("PrePaddingSeconds") { padding("Start early", key: "PrePaddingSeconds") }
                if has("PostPaddingSeconds") { padding("End late", key: "PostPaddingSeconds") }
            }
        }
        if request.series {
            Section("Series") {
                if has("RecordNewOnly") { choice("Record", key: "RecordNewOnly", on: "New episodes", off: "All episodes") }
                if has("RecordAnyChannel") { choice("Channel", key: "RecordAnyChannel", on: "Any channel", off: "This channel") }
                if has("RecordAnyTime") { choice("Time", key: "RecordAnyTime", on: "Any time", off: "This time") }
                if has("SkipEpisodesInLibrary") { choice("Episodes in library", key: "SkipEpisodesInLibrary", on: "Skip", off: "Record") }
                if has("Days") { days }
                if has("KeepUpTo") {
                    Picker("Keep", selection: Binding(get: { int("KeepUpTo") }, set: { edits["KeepUpTo"] = .integer(Int64($0)) })) {
                        ForEach(Self.keepValues(int("KeepUpTo")), id: \.self) { Text($0 == 0 ? "All" : "Latest \($0)").tag($0) }
                    }
                }
                if has("KeepUntil") { keepUntil }
            }
        }
        if has("Priority") {
            Section {
                Button { more.toggle() } label: {
                    Label("More options", systemImage: more ? "chevron.down" : "chevron.right")
                }
                .accessibilityValue(more ? "Expanded" : "Collapsed")
                if more {
                    Picker("Priority", selection: Binding(get: { int("Priority") }, set: { edits["Priority"] = .integer(Int64($0)) })) {
                        ForEach(-2...2, id: \.self) { Text(LivePresentation.recordingPriorityLabel(String($0))).tag($0) }
                    }
                }
            }
        }
        Section {
            Button(LivePresentation.scheduleReviewLabel(existing: request.existing, changed: changed)) { validate() }
                .disabled(model.state.writing || (request.existing && !changed))
        } footer: {
            Text("Some options depend on your server.")
        }
    }

    private var programLine: String? {
        guard let program = request.program else { return original.channelName }
        let time = LiveFormat.time(nowMs: LiveFormat.nowMs())
        return [original.channelName ?? program.channelName, time.range(program.startDate ?? "", program.endDate ?? "")]
            .compactMap { $0 }.joined(separator: " · ")
    }

    private func padding(_ label: String, key: String) -> some View {
        let current = int(key)
        let choices = Array(Set([0, 60, 120, 300, 600, 900, current])).sorted()
        return Picker(label, selection: Binding(get: { current }, set: { edits[key] = .integer(Int64($0)) })) {
            ForEach(choices, id: \.self) { seconds in
                Text(seconds == 0 ? "Off" : seconds % 60 == 0 ? "\(seconds / 60) min" : "\(seconds) sec").tag(seconds)
            }
        }
    }

    private func choice(_ label: String, key: String, on: String, off: String) -> some View {
        Picker(label, selection: Binding(get: { bool(key) }, set: { edits[key] = .bool($0) })) {
            Text(on).tag(true)
            Text(off).tag(false)
        }
    }

    private static let weekdays = LivePresentation.weekdayOrder

    private var selectedDays: Set<String> {
        let value = edits["Days"] ?? original.fields["Days"]
        if case .array(let items)? = value { return Set(items.compactMap(\.stringValue)) }
        return []
    }

    private var days: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Days").font(.subheadline)
            Text(LivePresentation.recordingDaysLabel(selectedDays)).font(.caption).foregroundStyle(Theme.muted)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Self.weekdays, id: \.self) { day in
                        let on = selectedDays.contains(day)
                        Button(String(day.prefix(3))) {
                            var next = selectedDays
                            if on { next.remove(day) } else { next.insert(day) }
                            edits["Days"] = .array(Self.weekdays.filter(next.contains).map { .string($0) })
                        }
                        #if os(iOS)
                        .buttonStyle(.bordered)
                        .tint(on ? Theme.accent : .secondary)
                        #endif
                        .accessibilityLabel(day)
                        .accessibilityValue(on ? "Selected" : "Not selected")
                    }
                }
            }
        }
    }

    @ViewBuilder private var keepUntil: some View {
        let current = string("KeepUntil") ?? ""
        let editable = ["UntilDeleted", "UntilSpaceNeeded"]
        if editable.contains(current) {
            Picker("Delete", selection: Binding(get: { current }, set: { edits["KeepUntil"] = .string($0) })) {
                Text("Never").tag("UntilDeleted")
                Text("When space is needed").tag("UntilSpaceNeeded")
            }
        } else {
            LabeledContent("Delete", value: current == "UntilWatched" ? "After watching" : current == "UntilDate" ? "On the scheduled date" : "Provider setting")
        }
    }

    static func keepValues(_ current: Int) -> [Int] { Array(Set([0, 1, 3, 5, 10, current])).sorted() }

    // MARK: Review

    @ViewBuilder private var review: some View {
        Section("Review") {
            Text(summary).fixedSize(horizontal: false, vertical: true)
            ForEach(reviewLines, id: \.self) { Text($0).font(.subheadline).foregroundStyle(Theme.muted) }
        }
        Section {
            Button(request.existing ? "Save changes" : "Confirm recording") { Task { await confirm() } }
                .disabled(!LivePresentation.scheduleCanConfirm(existing: request.existing, changed: changed, busy: model.state.writing,
                                                               canSubmit: model.state.actionsAvailable(nowMs: LiveFormat.nowMs())))
            Button("Edit options") { reviewing = false }
        }
    }

    private var summary: String {
        let edited = (try? LiveRules.editSchedule(original, edits: edits, series: request.series)) ?? original
        return LivePresentation.scheduleReviewSummary(edited, series: request.series, existing: request.existing,
                                                      time: LiveFormat.time(nowMs: LiveFormat.nowMs()), touch: platformValue(tv: false, touch: true))
    }

    private var reviewLines: [String] {
        var lines: [String] = []
        func minutes(_ key: String) -> String { let s = int(key); return s == 0 ? "Off" : "\(s / 60) min" }
        if has("PrePaddingSeconds") { lines.append("Start early: \(minutes("PrePaddingSeconds"))") }
        if has("PostPaddingSeconds") { lines.append("End late: \(minutes("PostPaddingSeconds"))") }
        if request.series {
            if has("RecordAnyTime") { lines.append("Time: \(bool("RecordAnyTime") ? "Any time" : "This time")") }
            if has("Days") { lines.append("Days: \(LivePresentation.recordingDaysLabel(selectedDays))") }
            if has("KeepUpTo") { lines.append("Keep: \(int("KeepUpTo") == 0 ? "All" : "Latest \(int("KeepUpTo"))")") }
        }
        if has("Priority") { lines.append("Priority: \(LivePresentation.recordingPriorityLabel(String(int("Priority"))))") }
        return lines
    }

    private func validate() {
        do {
            _ = try LiveRules.editSchedule(original, edits: edits, series: request.series)
            error = nil
            reviewing = true
        } catch {
            self.error = (error as? LocalizedError)?.errorDescription ?? "These options can't be saved."
        }
    }

    private func confirm() async {
        let schedule: LiveSchedule
        do { schedule = try LiveRules.editSchedule(original, edits: edits, series: request.series) } catch {
            self.error = "These options can't be saved."; reviewing = false; return
        }
        guard let result = await model.save(schedule, series: request.series, existing: request.existing) else { return }
        if result.outcome == "ok" { dismiss() } else { error = result.message ?? LiveTVModel.outcomeMessage(result.outcome, deleting: false) }
    }
}

extension JSONValue {
    var intValue: Int? {
        switch self {
        case .integer(let v): return Int(v)
        case .number(let v): return Int(v)
        case .string(let v): return Int(v)
        default: return nil
        }
    }
    var boolValue: Bool? {
        switch self {
        case .bool(let v): return v
        case .string(let v): return v == "true" ? true : v == "false" ? false : nil
        default: return nil
        }
    }
    var stringValue: String? { if case .string(let v) = self { return v }; return nil }
}
