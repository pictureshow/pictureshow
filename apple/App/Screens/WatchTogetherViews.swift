// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

/// "Watch together" on a title page (SYN-01). Live and infinite sources can't share a timeline (SYN-11).
struct WatchTogetherButton: View {
    @EnvironmentObject private var app: AppModel
    let item: MediaItem?
    @State private var showing = false

    var body: some View {
        Button { showing = true } label: { Label("Watch together", systemImage: "person.2") }
            .secondaryAction()
            .accessibilityIdentifier("watch-together")
            .sheet(isPresented: $showing) { WatchTogetherSheet(sync: app.syncPlay, item: item).environmentObject(app) }
    }
}

/// The group this viewer is in, or the server's groups to join or create (SYN-01).
struct WatchTogetherSheet: View {
    @EnvironmentObject private var app: AppModel
    @ObservedObject var sync: SyncPlaySession
    /// The title the sheet was opened from: a new group starts with it.
    let item: MediaItem?
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""

    var body: some View {
        NavigationStack {
            Form {
                if sync.participating { current } else { chooser }
                Section {
                    Text("Groups live on your server. Other people on this server who can use Watch together can see a group's name and who is in it.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Watch together")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            #endif
        }
        .task {
            if name.isEmpty, let user = app.account?.username { name = "\(user)'s group" }
            if !sync.participating { await sync.refresh() }
        }
    }

    // MARK: In a group

    @ViewBuilder private var current: some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Text(sync.groupName ?? "Group").font(.headline)
                Text(SyncLabels.phase(sync.phase)).font(.subheadline).foregroundStyle(.secondary)
                if let quality = SyncLabels.quality(sync.quality) {
                    Text(quality).font(.footnote).foregroundStyle(.secondary)
                }
            }
            .accessibilityElement(children: .combine)
            if let notice = sync.notice { Text(notice).font(.footnote) }
        }
        let transport = sync.transport
        if transport.visible {
            Section {
                HStack(spacing: 12) {
                    transportButton("Previous", systemImage: "backward.end.fill", enabled: transport.previousEnabled, id: "sync-previous") { sync.requestPrevious() }
                    if let primary = transport.primary {
                        transportButton(primary == "pause" ? "Pause" : "Play", systemImage: primary == "pause" ? "pause.fill" : "play.fill",
                                        enabled: transport.playEnabled, id: "sync-play") { sync.requestPlayPause() }
                    }
                    transportButton("Next", systemImage: "forward.end.fill", enabled: transport.nextEnabled, id: "sync-next") { sync.requestNext() }
                }
                .frame(maxWidth: .infinity)
            } header: {
                Text("Group playback")
            } footer: {
                Text("Changes here apply to everyone.")
            }
        }
        let people = SyncPresentation.people(sync.participants)
        Section(SyncPresentation.watchingCount(sync.participants.count)) {
            ForEach(people.names, id: \.self) { Text($0) }
            if people.remaining > 0 { Text("and \(people.remaining) more").foregroundStyle(.secondary) }
        }
        Section {
            Toggle(isOn: Binding(get: { sync.waitForMe }, set: { sync.setWaitForMe($0) })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Wait for me")
                    Text("When off, the group doesn't wait while this device loads. Nobody else is affected.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            if let reminder = sync.stayReminder, reminder.rejoin {
                Button("Watch") { sync.watch(); dismiss() }
            }
            Button("Leave group", role: .destructive) { sync.leave(); dismiss() }
                .accessibilityIdentifier("leave-group")
        } footer: {
            Text("Play, pause and seeking move everyone in the group. To use AirPlay, leave the group first.")
        }
    }

    private func transportButton(_ title: String, systemImage: String, enabled: Bool, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: systemImage).labelStyle(.iconOnly).font(.title3).frame(minWidth: 56, minHeight: 48)
        }
        .buttonStyle(.bordered)
        .disabled(!enabled)
        .accessibilityLabel(title)
        .accessibilityIdentifier(id)
    }

    // MARK: Choosing a group

    @ViewBuilder private var chooser: some View {
        let access = sync.access
        if let message = SyncLabels.access(access.notice) {
            Section { Text(message).font(.subheadline) }
        }
        if let notice = sync.notice { Section { Text(notice).font(.footnote) } }
        if access.canJoin {
            Section("Groups on this server") {
                if sync.groups.isEmpty {
                    Text(sync.listFailed ? "Couldn't load the groups." : "No groups right now.").foregroundStyle(.secondary)
                }
                ForEach(sync.groups, id: \.id) { group in
                    Button {
                        sync.join(group)
                        dismiss()
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(group.name.isEmpty ? "Unnamed group" : group.name)
                            Text(SyncLabels.members(group.participants.count)).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    .accessibilityLabel("Join \(group.name), \(SyncLabels.members(group.participants.count))")
                }
            }
        }
        if access.canCreate {
            Section("New group") {
                TextField("Group name", text: $name)
                    #if os(iOS)
                    .textInputAutocapitalization(.sentences)
                    #endif
                Button(item == nil ? "Create group" : "Create group and watch this") {
                    let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                    sync.create(name: trimmed.isEmpty ? "Watch together" : trimmed, item: item, startTicks: item?.userData.playbackPositionTicks ?? 0)
                    dismiss()
                }
                .accessibilityIdentifier("create-group")
            }
        }
        Section {
            Button(sync.listing ? "Checking…" : "Refresh") { Task { await sync.refresh() } }
                .disabled(!access.canRefresh)
        }
    }
}

/// "Still in {group} · Leave", with Watch when there's something to rejoin (SYN-08). Shown on Home, Libraries and
/// title pages while this device stays in a group without a player open.
struct SyncStayBanner: View {
    @ObservedObject var sync: SyncPlaySession

    var body: some View {
        if let reminder = sync.stayReminder {
            // One row where it fits (iPad, TV); on a phone the buttons go under the group name rather than squeezing it.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 14) {
                    label(reminder).fixedSize()
                    Spacer(minLength: 8)
                    buttons(reminder)
                }
                VStack(alignment: .leading, spacing: 10) {
                    label(reminder)
                    HStack(spacing: 12) { buttons(reminder) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 18).padding(.vertical, 10)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Theme.surface))
            .padding(.horizontal, Theme.gutter)
            #if os(tvOS)
            .focusSection()
            #endif
            .accessibilityElement(children: .contain)
        }
    }
}

private extension SyncStayBanner {
    func label(_ reminder: SyncStayReminder) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "person.2.fill").foregroundStyle(Theme.accent).accessibilityHidden(true)
            Text(reminder.leaveUnconfirmed ? "Leaving \(reminder.groupName ?? "the group")…" : "Still in \(reminder.groupName ?? "a group")")
                .font(.subheadline.weight(.semibold))
                .lineLimit(2)
        }
    }

    @ViewBuilder func buttons(_ reminder: SyncStayReminder) -> some View {
        if reminder.rejoin {
            Button("Watch") { sync.watch() }.primaryAction().fixedSize().accessibilityIdentifier("stay-watch")
        }
        Button(reminder.leaveUnconfirmed ? "Try again" : "Leave") { sync.leave() }
            .secondaryAction().fixedSize().accessibilityIdentifier("stay-leave")
    }
}

#if os(iOS)
/// AirPlay while grouped (SYN-10): asks to leave the group first; AirPlay opens once the server accepted the Leave.
struct SyncAirPlayButton: View {
    @ObservedObject var sync: SyncPlaySession
    let route: () -> Void

    var body: some View {
        let consent = sync.airPlayConsent
        Button { sync.askToLeaveForAirPlay(route) } label: {
            Image(systemName: "airplayvideo").font(.title3).frame(width: 44, height: 44)
        }
        .accessibilityLabel("AirPlay")
        .accessibilityIdentifier("sync-airplay")
        .confirmationDialog("Leave \(sync.groupName ?? "the group") to use AirPlay?", isPresented: Binding(
            get: { consent != nil },
            set: { if !$0, let consent, sync.airPlayConsent == consent { sync.cancelAirPlay(consent) } }
        ), titleVisibility: .visible) {
            // Each answer carries the consent this dialog showed; an older dialog can't answer a newer question.
            if let consent {
                Button("Leave group and use AirPlay", role: .destructive) { sync.leaveForAirPlay(consent) }
                Button("Cancel", role: .cancel) { sync.cancelAirPlay(consent) }
            }
        } message: {
            Text("AirPlay can't stay in step with the group. Everyone else keeps watching together.")
        }
    }
}
#endif

/// The player's compact group chip (SYN-06): group name and phase, plus this device's own sync estimate.
struct SyncGroupChip: View {
    @ObservedObject var sync: SyncPlaySession
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: "person.2.fill")
                Text(sync.groupName ?? "Group").lineLimit(1)
                Text("·").opacity(0.6)
                Text(SyncLabels.phase(sync.phase)).lineLimit(1)
            }
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(Capsule().fill(.black.opacity(0.55)))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Watch together: \(sync.groupName ?? "group"), \(SyncLabels.phase(sync.phase))\(SyncLabels.quality(sync.quality).map { ", \($0)" } ?? "")")
        .accessibilityIdentifier("sync-chip")
    }
}

enum SyncLabels {
    static func phase(_ phase: SyncPhase) -> String {
        switch phase {
        case .EMPTY: return "Nothing playing yet"
        case .JOINING: return "Joining…"
        case .WAITING, .WAITING_BUFFER, .WAITING_SEEK: return "Waiting for everyone"
        case .PLAYING: return "Playing together"
        case .PAUSED: return "Paused"
        case .RECONNECTING: return "Reconnecting…"
        case .LEAVING: return "Leaving…"
        case .ERROR: return "Needs attention"
        }
    }

    /// Only this device's estimate; never a claim about other people's screens.
    static func quality(_ quality: SyncQuality) -> String? {
        switch quality {
        case .UNKNOWN: return nil
        case .IN_SYNC: return "This device: in sync"
        case .CATCHING_UP: return "This device: catching up"
        case .OUT_OF_SYNC: return "This device: out of sync"
        }
    }

    static func access(_ notice: SyncAccessNotice) -> String? {
        switch notice {
        case .UNKNOWN: return nil
        case .CHECKING: return "Checking whether this viewer can use Watch together…"
        case .UNAVAILABLE: return "Couldn't check Watch together on this server. Try Refresh."
        case .DENIED: return "This server's administrator hasn't allowed this viewer to use Watch together."
        case .ALLOWED: return nil
        }
    }

    static func members(_ count: Int) -> String { count == 1 ? "1 person" : "\(count) people" }
}
