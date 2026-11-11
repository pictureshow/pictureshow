// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

/// Owns one account-scoped command. Reopening or refreshing never resends it.
@MainActor final class RemotePlayModel: ObservableObject {
    @Published private(set) var targets: [RemotePlaybackTarget] = []
    @Published private(set) var selectedName: String?
    @Published private(set) var result: String?
    @Published private(set) var phase = "detached"
    @Published private(set) var error: String?
    @Published private(set) var loading = false
    @Published private(set) var receiptStatus: Int?
    private(set) var operation = "play-now"
    private let account: Account
    private let client: JellyfinClient
    private let clock: () -> Int64
    private let automaticallyObserve: Bool
    private var state: [String: Any]
    private var sequence: Int64 = 0
    private var generation = 0
    private var observer: Task<Void, Never>?
    private var ticker: Task<Void, Never>?
    private var sender: Task<Void, Never>?
    private var closed = false
    private var attemptedAttached = false

    init(account: Account, client: JellyfinClient, clock: @escaping () -> Int64 = { Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000) }, automaticallyObserve: Bool = true, operation: String = "play-now") {
        self.account = account; self.client = client; self.clock = clock; self.automaticallyObserve = automaticallyObserve
        self.operation = operation
        state = ["server": account.server.id.isEmpty ? account.server.url.absoluteString : account.server.id, "viewer": account.userId]
    }
    private func reduce(_ event: [String: Any]) throws {
        state = try RemoteReducers.reduce(suite: "remote-reconnect", operation: "controller", state: state, event: event)
        phase = state["phase"] as? String ?? "detached"
        let command = state["command"] as? [String: Any]
        result = (command?["pending"] as? [String: Any])?["result"] as? String
            ?? (state["followUp"] as? [String: Any])?["result"] as? String
    }
    func tick() {
        guard !closed else { return }
        do { try reduce(["op": "tick", "nowMs": String(clock())]) }
        catch { self.error = "Playback status is unavailable. Refresh status to check the TV." }
    }
    @discardableResult func refresh() async -> Bool {
        guard !closed, !loading else { return false }
        loading = true
        let scope = generation
        defer { if scope == generation { loading = false } }
        do {
            let rows = try await client.remotePlaybackTargets(account: account)
            guard !closed, scope == generation else { return false }
            // Expire before applying a delayed network response; it cannot revive a timed-out command.
            tick()
            targets = rows
            try reduce(["op": "refresh", "targets": rows.map(\.reducerValue), "nowMs": String(clock())])
            error = nil
            return true
        } catch {
            guard !closed, scope == generation else { return false }
            if let failure = error as? ClientError {
                if failure == .invalidCredentials { try? reduce(["op": "signed-out"]) }
                if case .server(let status) = failure, [403, 404].contains(status) { try? reduce(["op": "answer", "status": status]) }
            }
            self.error = "Could not read TV status. Refresh status to try again."
            return false
        }
    }
    /// A retained target is only a hint until the authorized Sessions response validates
    /// its exact identity through the shared reconnect policy. Mount retries never send.
    func beginAttached(_ target: RemotePlaybackTarget, itemID: String, operation: String) async {
        guard !closed, !attemptedAttached, selectedName == nil, ["play-next", "add-to-queue"].contains(operation) else { return }
        attemptedAttached = true
        self.operation = operation
        do { try reduce(["op": "attach", "target": target.reducerValue]) }
        catch { self.error = "The attached TV is unavailable. Choose an available device."; return }
        guard await refresh(), !closed else { return }
        guard phase == "attached", let fresh = targets.first(where: { $0.id == target.id }) else { return }
        await begin(on: fresh, itemID: itemID, operation: operation)
    }
    /// Queue edits use the shared follow-up command state; there is no queue observation
    /// in a Sessions response, so a transport receipt must never claim confirmation.
    func begin(on target: RemotePlaybackTarget, itemID: String, operation: String = "play-now", fromStart: Bool = false) async {
        guard !closed, selectedName == nil else { return }
        self.operation = operation
        sequence += 1
        let commandID = sequence, scope = generation
        do {
            try reduce(["op": "attach", "target": target.reducerValue])
            let queueEdit = ["play-next", "add-to-queue"].contains(operation)
            var intent: [String: Any] = ["op": "intent", "id": String(commandID), "value": queueEdit ? "queue" : operation, "nowMs": String(clock())]
            if operation == "play-now" { intent["entry"] = itemID }
            try reduce(intent)
            guard state["effect"] as? String == "dispatch" else { error = "This TV cannot accept that command. Refresh the devices and try again."; return }
            selectedName = target.name
            if automaticallyObserve { startObservation() }
            // This is the only dispatch site. Polling and Retry call GET /Sessions only.
            let receipt = try await client.sendRemotePlayback(account: account, target: target, operation: operation, itemID: operation == "play-now" || queueEdit ? itemID : nil, ticks: operation == "play-now" && fromStart ? 0 : nil)
            guard !closed, scope == generation else { return }
            receiptStatus = receipt.status
            tick()
            try reduce(["op": "sent", "id": String(commandID), "status": receipt.status ?? 0])
        } catch {
            guard !closed, scope == generation else { return }
            try? reduce(["op": "sent", "id": String(commandID), "status": 0])
            self.error = "Could not confirm delivery. Check the TV, then refresh status."
        }
    }
    func play(on target: RemotePlaybackTarget, itemID: String, operation: String = "play-now", fromStart: Bool = false) {
        guard sender == nil else { return }
        sender = Task { await begin(on: target, itemID: itemID, operation: operation, fromStart: fromStart) }
    }
    private func startObservation() {
        // The policy decides the deadline; this timer has no duplicated confirmation-window constant.
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, self.result == "sent", !self.closed else { return }
                self.tick()
                do { try await Task.sleep(for: .milliseconds(100)) } catch { return }
            }
        }
        observer = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, self.result == "sent", !self.closed else { return }
                await self.refresh()
                do { try await Task.sleep(for: .milliseconds(500)) } catch { return }
            }
        }
    }
    func close() {
        closed = true; generation += 1; loading = false
        observer?.cancel(); ticker?.cancel(); sender?.cancel()
        try? reduce(["op": "detach"])
    }
    var message: String {
        switch phase {
        case "signed-out": return "Sign in again to control a TV."
        case "denied": return "You no longer have permission to control this TV."
        case "target-gone", "target-restarted": return selectedName == nil ? "The attached TV disconnected or restarted. Choose an available device." : "The TV disconnected or restarted. Choose it again from a new Play on TV window."
        default: break
        }
        if isQueueEdit {
            switch result {
            case "unknown": return "The queue change could not be confirmed. Check the TV’s queue. Refreshing will not send the request again."
            case "sent":
                if let receiptStatus, (200..<300).contains(receiptStatus) { return "Queue request sent. Check the TV’s queue to verify the change." }
                return "Sending the queue request…"
            default: return "Choose a device whose queue you want to change."
            }
        }
        switch result {
        case "confirmed": return "Playback confirmed on the TV."
        case "unknown": return "Playback could not be confirmed. Check the TV, then refresh status. Refreshing will not send the title again."
        case "sent": return "Waiting for the TV to start this title…"
        default: return "Choose a device to play this title."
        }
    }
    var isQueueEdit: Bool { ["play-next", "add-to-queue"].contains(operation) }
    var waiting: Bool { result == "sent" && (!isQueueEdit || receiptStatus == nil) }
}

struct RemotePlayView: View {
    let item: MediaItem
    let operation: String
    let fromStart: Bool
    let onThisDevice: (() -> Void)?
    let attachedTarget: RemotePlaybackTarget?
    let onAttached: ((RemotePlaybackTarget) -> Void)?
    @StateObject private var model: RemotePlayModel
    @Environment(\.dismiss) private var dismiss
    init(item: MediaItem, account: Account, client: JellyfinClient, operation: String = "play-now", fromStart: Bool = false, onThisDevice: (() -> Void)? = nil, attachedTarget: RemotePlaybackTarget? = nil, onAttached: ((RemotePlaybackTarget) -> Void)? = nil) {
        self.item = item; self.operation = operation; self.fromStart = fromStart; self.onThisDevice = onThisDevice
        self.attachedTarget = attachedTarget; self.onAttached = onAttached
        _model = StateObject(wrappedValue: RemotePlayModel(account: account, client: client, operation: operation))
    }
    private var panelTitle: String {
        operation == "play-next" ? "Play next" : operation == "add-to-queue" ? "Add to queue" : fromStart ? "Play from start" : "Play on TV"
    }
    #if os(tvOS)
    @State private var contentHeight: CGFloat = 600
    #endif
    var body: some View {
        NavigationStack {
            #if os(tvOS)
            televisionContent
                .toolbar(.hidden, for: .navigationBar)
                .onExitCommand { dismiss() }
            #else
            List {
                Section { statusRows }
                if model.selectedName == nil { Section("Devices") { deviceRows } }
                Section { refreshButton }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.background)
            .navigationTitle(panelTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { doneButton } }
            #endif
        }
        .background(Theme.background)
        #if os(tvOS)
        // Fit short choosers to their measured contents; many targets and large
        // text keep the same scrollable surface within the TV safe area.
        .frame(width: 920, height: min(contentHeight, UIScreen.main.bounds.height - 160))
        .modifier(RemoteChooserPresentationSizing())
        #endif
        .task {
            if ["play-next", "add-to-queue"].contains(operation), let attachedTarget {
                await model.beginAttached(attachedTarget, itemID: item.id, operation: operation)
            } else { await model.refresh() }
        }
        .onDisappear { model.close() }
    }
    #if os(tvOS)
    private var televisionContent: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text(panelTitle).font(.title3.weight(.semibold))
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader).accessibilityIdentifier("remote-play-heading")
                VStack(alignment: .leading, spacing: 12) { statusRows }
                if model.selectedName == nil {
                    Text("Devices").font(.subheadline).foregroundStyle(Theme.muted)
                        .accessibilityAddTraits(.isHeader).padding(.top, 8)
                    VStack(alignment: .leading, spacing: 24) { deviceRows }
                }
                refreshButton.frame(maxWidth: .infinity, alignment: .leading)
                doneButton.frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(32)
            .background {
                GeometryReader { geometry in
                    Color.clear.preference(key: RemoteChooserHeight.self, value: geometry.size.height)
                }
            }
        }
        .buttonStyle(.bordered)
        .background(Theme.background)
        .onPreferenceChange(RemoteChooserHeight.self) { contentHeight = $0 }
    }
    #endif
    @ViewBuilder private var statusRows: some View {
        Text(item.name).font(.headline).fixedSize(horizontal: false, vertical: true)
        Text(model.message).fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("remote-play-status")
        if let name = model.selectedName { Text(name).font(.subheadline) }
        if let error = model.error { Text(error).fixedSize(horizontal: false, vertical: true).accessibilityIdentifier("remote-play-error") }
        if model.waiting { ProgressView().accessibilityLabel("Waiting for the TV") }
    }
    @ViewBuilder private var deviceRows: some View {
        if operation == "play-now", let onThisDevice {
            Button { dismiss(); onThisDevice() } label: {
                RemotePlaybackTargetRow(name: "This device", detail: "Play here")
            }
            .accessibilityIdentifier("remote-play-this-device")
            .accessibilityHint(fromStart ? "Play this title from the start on this device" : "Play this title on this device")
        }
        ForEach(model.targets) { target in
            Button {
                onAttached?(target)
                model.play(on: target, itemID: item.id, operation: operation, fromStart: fromStart)
            } label: {
                RemotePlaybackTargetRow(name: target.name, detail: target.client,
                    viewer: target.duplicateName ? target.viewerName : nil,
                    differentViewer: !target.sameViewer, playingTitle: target.playingTitle)
            }
            .accessibilityIdentifier("remote-play-target-\(target.id)")
            .disabled(model.loading)
            .accessibilityHint(operation == "play-next" ? "Play this title next on this device" : operation == "add-to-queue" ? "Add this title to this device’s queue" : "Play this title on this device")
        }
        if model.targets.isEmpty, !model.loading {
            Text("No available TVs. Open Jellyfin on the TV, then refresh.").fixedSize(horizontal: false, vertical: true)
        }
    }
    private var refreshButton: some View {
        Button(model.selectedName == nil ? "Refresh devices" : "Refresh status") { Task { await model.refresh() } }
            .disabled(model.loading)
            .accessibilityIdentifier("remote-play-refresh")
    }
    private var doneButton: some View {
        Button("Done") { dismiss() }.accessibilityIdentifier("remote-play-done")
    }
}

/// Local and remote destinations use the same type hierarchy and target geometry.
private struct RemotePlaybackTargetRow: View {
    let name: String
    let detail: String
    var viewer: String? = nil
    var differentViewer = false
    var playingTitle: String? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(name).font(.headline)
            Text(detail).font(.subheadline)
            if let viewer { Text(viewer).font(.subheadline) }
            if differentViewer { Text("Different viewer").font(.subheadline) }
            if let playingTitle { Text("Now playing: \(playingTitle)").font(.subheadline) }
        }
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 6)
    }
}
#if os(tvOS)
private struct RemoteChooserHeight: PreferenceKey {
    static var defaultValue: CGFloat { 0 }
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}
private struct RemoteChooserPresentationSizing: ViewModifier {
    @ViewBuilder func body(content: Content) -> some View {
        if #available(tvOS 18.0, *) { content.presentationSizing(.fitted) }
        else { content }
    }
}
#endif
