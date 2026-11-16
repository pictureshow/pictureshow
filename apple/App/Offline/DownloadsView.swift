// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

/// What a download's row and its title page say about it, in plain words (OFF-03b: no invented totals).
enum DownloadWords {
    static func bytes(_ value: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        formatter.allowsNonnumericFormatting = false  // "0 KB", not "Zero KB"
        return formatter.string(fromByteCount: value)
    }

    static func state(_ entry: OfflineEntry, _ progress: DownloadProgress?, wifiOnly: Bool) -> String {
        switch entry.job.phase {
        case .queued: return "Waiting to download"
        case .waitingNetwork: return wifiOnly && !entry.job.cellularAllowed ? "Waiting for Wi-Fi" : "Waiting for a connection"
        case .waitingStorage: return "Waiting for storage space"
        case .preparing: return "Preparing"
        case .transferring:
            guard let progress else { return "Downloading" }
            if let percent = progress.percent {
                if let total = progress.total { return "Downloading · \(percent)% of \(bytes(total))" }
                return "Downloading · \(percent)%"
            }
            return progress.received > 0 ? "Downloading · \(bytes(progress.received))" : "Downloading"
        case .verifying: return "Checking the download"
        case .ready: return "Downloaded · \(bytes(entry.committedBytes()))"
        case .paused: return entry.job.pausedByCap ? "Paused: over the storage limit" : "Paused"
        case .failed: return failure(entry.job.failure)
        case .missing: return "The downloaded file is gone from this device"
        case .deleting: return "Deleting"
        }
    }

    /// Never a raw reason code.
    static func failure(_ reason: String?) -> String {
        switch reason {
        case "needs-password": return "Enter your password to download from this server"
        case "denied", "http-403": return "The server doesn't allow this download"
        case "http-404": return "The server no longer has this title"
        case "storage": return "Not enough storage space to finish"
        case "short": return "The server's conversion ended early"
        case "length", "verify", "unreadable", "empty": return "The download didn't arrive whole"
        default: return "The download stopped"
        }
    }

    static func subtitle(_ entry: OfflineEntry) -> String {
        var parts: [String] = []
        if entry.kind == .episode {
            if let series = entry.labels.seriesName { parts.append(series) }
            if let s = entry.labels.seasonNumber, let e = entry.labels.episodeNumber { parts.append("S\(s):E\(e)") }
        } else if let year = entry.labels.year { parts.append(String(year)) }
        parts.append(entry.qualityLabel)
        return parts.joined(separator: " · ")
    }
}

#if os(iOS)
/// OFF-04: opens with no server, groups what's ready, in flight and stopped, and holds the storage settings.
struct DownloadsView: View {
    @EnvironmentObject private var app: AppModel
    var body: some View { DownloadsList(downloads: app.downloads) }
}

/// Observes the engine directly, so a progress tick redraws only download views.
private struct DownloadsList: View {
    @EnvironmentObject private var app: AppModel
    @ObservedObject var downloads: Downloads
    @State private var asking: OfflineEntry?
    @State private var askingAll = false

    private var account: Account? { app.account }
    private var entries: [OfflineEntry] { downloads.entries(account) }
    private var active: [OfflineEntry] { entries.filter { ![.ready, .failed, .missing, .paused].contains($0.job.phase) } }
    private var ready: [OfflineEntry] { entries.filter { $0.job.phase == .ready } }
    private var stopped: [OfflineEntry] { entries.filter { [.failed, .missing, .paused].contains($0.job.phase) } }

    var body: some View {
        List {
            if !downloads.networkUp {
                Section { Label("You're offline. Your downloads still play.", systemImage: "wifi.slash").foregroundStyle(Theme.muted) }
            }
            ForEach(downloads.kept.filter { $0.accountKey == account?.key }) { notice in
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Server progress kept").font(.headline)
                        Text("\(notice.title) was watched somewhere else too, so the server's place was kept instead of this device's.")
                            .font(.subheadline).foregroundStyle(Theme.muted)
                        Button("OK") { downloads.dismissKept(notice) }.padding(.top, 4)
                    }
                }
            }
            if let account, downloads.passwordNeeded.contains(account.key) { PasswordRow(account: account) }
            if entries.isEmpty {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("No downloads yet").font(.headline)
                        Text("Open a movie or an episode and choose Download to watch it without a connection.")
                            .font(.subheadline).foregroundStyle(Theme.muted)
                    }
                    .padding(.vertical, 6)
                }
            }
            if !active.isEmpty { Section("Downloading") { ForEach(active, id: \.id) { row($0) } } }
            if !ready.isEmpty { Section("Ready to watch") { ForEach(ready, id: \.id) { row($0) } } }
            if !stopped.isEmpty { Section("Stopped") { ForEach(stopped, id: \.id) { row($0) } } }
            if let account { StorageSection(account: account, downloads: downloads, askingAll: $askingAll) }
        }
        .navigationTitle("Downloads")
        .confirmationDialog(asking.map { "Delete \($0.labels.title)?" } ?? "", isPresented: Binding(get: { asking != nil }, set: { if !$0 { asking = nil } }), titleVisibility: .visible) {
            Button("Delete download", role: .destructive) { if let account, let e = asking { downloads.delete(account, e.id) }; asking = nil }
            Button("Cancel", role: .cancel) { asking = nil }
        } message: {
            Text("It's removed from this device. You can download it again later.")
        }
        .confirmationDialog("Delete all downloads?", isPresented: $askingAll, titleVisibility: .visible) {
            Button("Delete all", role: .destructive) { if let account { downloads.deleteAll(account) } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Everything downloaded for \(account?.username ?? "this viewer") is removed from this device.")
        }
    }

    @ViewBuilder private func row(_ entry: OfflineEntry) -> some View {
        let progress = account.flatMap { downloads.progress($0, entry.id) }
        HStack(alignment: .top, spacing: 12) {
            LocalArtwork(url: account.flatMap { localArt($0, entry) })
                .frame(width: 96, height: 54).clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 4) {
                Text(entry.labels.title).font(.headline).lineLimit(2)
                Text(DownloadWords.subtitle(entry)).font(.caption).foregroundStyle(Theme.muted)
                Text(DownloadWords.state(entry, progress, wifiOnly: downloads.wifiOnly(account)))
                    .font(.caption).foregroundStyle(entry.job.phase == .failed || entry.job.phase == .missing ? Color.red : Theme.muted)
                if entry.job.phase == .transferring {
                    if let p = progress?.percent { ProgressView(value: Double(p), total: 100) } else { ProgressView().progressViewStyle(.linear) }
                }
                if entry.job.phase == .ready, let summary = entry.notDownloadedSummary {
                    Text(summary).font(.caption2).foregroundStyle(Theme.muted)
                }
                actions(entry).padding(.top, 2)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onTapGesture { if entry.job.phase == .ready, let account, let media = downloads.local(account.key, entry) { app.playLocal(media) } }
        .swipeActions { Button("Delete", role: .destructive) { asking = entry } }
    }

    @ViewBuilder private func actions(_ entry: OfflineEntry) -> some View {
        if let account {
            HStack(spacing: 10) {
                switch entry.job.phase {
                case .ready:
                    Button { if let m = downloads.local(account.key, entry) { app.playLocal(m) } } label: { Label("Play", systemImage: "play.fill") }
                case .paused:
                    Button { downloads.resume(account, entry.id) } label: { Label("Resume", systemImage: "arrow.down.circle") }
                case .failed where entry.job.failure == "needs-password":
                    EmptyView()
                case .failed:
                    Button { downloads.retry(account, entry.id) } label: { Label("Retry", systemImage: "arrow.clockwise") }
                case .missing:
                    Button { downloads.redownload(account, entry.id) } label: { Label("Download again", systemImage: "arrow.down.circle") }
                case .deleting:
                    EmptyView()
                default:
                    Button { downloads.pause(account, entry.id) } label: { Label("Pause", systemImage: "pause.fill") }
                    // OFF-06a: offered only on a metered network, and only for this download.
                    if entry.job.phase == .waitingNetwork, downloads.networkUp, downloads.networkExpensive, !entry.job.cellularAllowed {
                        Button { downloads.setCellular(account, entry.id, true) } label: { Label("Use mobile data", systemImage: "antenna.radiowaves.left.and.right") }
                    }
                }
                if entry.job.phase != .deleting {
                    Button(role: .destructive) { asking = entry } label: { Label("Delete", systemImage: "trash") }
                }
            }
            .buttonStyle(.bordered).controlSize(.small).font(.caption)
        }
    }

    private func localArt(_ account: Account, _ entry: OfflineEntry) -> URL? {
        guard let art = entry.resources.first(where: { $0.role == .artwork && $0.complete }) else { return nil }
        return OfflineFiles(accountKey: account.key).resourceURL(entry.id, art)
    }
}

private struct PasswordRow: View {
    @EnvironmentObject private var app: AppModel
    let account: Account
    @State private var password = ""
    @State private var working = false
    @State private var wrong = false

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Text("Password needed").font(.headline)
                Text("This server has Quick Connect turned off, so downloads need your password once to get their own sign-in. It isn't stored.")
                    .font(.subheadline).foregroundStyle(Theme.muted)
                SecureField("Password for \(account.username)", text: $password).textContentType(.password)
                if wrong { Text("That password didn't work.").font(.caption).foregroundStyle(.red) }
                Button(working ? "Checking…" : "Continue") {
                    working = true
                    Task { wrong = !(await app.downloads.providePassword(account, password)); working = false; if !wrong { password = "" } }
                }
                .disabled(password.isEmpty || working)
            }
        }
    }
}

private struct StorageSection: View {
    let account: Account
    @ObservedObject var downloads: Downloads
    @Binding var askingAll: Bool

    private static let steps: [Int64] = [2, 5, 10, 20, 50, 100].map { $0 * 1024 * 1024 * 1024 }

    var body: some View {
        let usage = downloads.usage(account)
        Section {
            LabeledContent("Used by downloads", value: DownloadWords.bytes(usage.used))
            if let free = OfflineFiles.freeBytes() { LabeledContent("Free on this device", value: DownloadWords.bytes(free)) }
            // The current limit is always a selected option, even when it isn't one of the steps.
            let cap = usage.cap ?? offlineDefaultCap(quotaBytes: OfflineFiles.totalBytes()) ?? Self.steps[2]
            Picker("Storage limit", selection: Binding(get: { cap }, set: { downloads.setCap(account, $0) })) {
                ForEach(Array(Set(Self.steps + [cap])).sorted(), id: \.self) { Text(DownloadWords.bytes($0)).tag($0) }
            }
            Toggle("Download on Wi-Fi only", isOn: Binding(get: { downloads.wifiOnly(account) }, set: { downloads.setWifiOnly(account, $0) }))
        } header: { Text("Storage") } footer: {
            Text("Downloads never remove each other to make room. Lowering the limit pauses the newest downloads that no longer fit.")
        }
        // OFF-10: Apple's converted-download packages keep the address they came from, including the download-only
        // sign-in in it (observed in .movpkg playlists). Say so plainly, and how it ends.
        if downloads.entries(account).contains(where: { $0.preset != .original }) {
            Section {
                Text("Compatible downloads keep a download-only sign-in inside their files, so anyone who copies them off this device could use it to reach your server until it ends. Signing out of this account ends it and deletes your downloads.")
                    .font(.footnote).foregroundStyle(.secondary)
            } header: { Text("Privacy") }
        }
        if !downloads.entries(account).isEmpty {
            Section { Button("Delete all downloads", role: .destructive) { askingAll = true } }
        }
    }
}

struct LocalArtwork: View {
    let url: URL?
    var body: some View {
        if let url, let image = UIImage(contentsOfFile: url.path) {
            Image(uiImage: image).resizable().scaledToFill()
        } else {
            Theme.surface.overlay(Image(systemName: "film").foregroundStyle(Theme.muted))
        }
    }
}

/// The title page's download control (OFF-04a): Download where it can, then the download's own state in its place.
struct DownloadControl: View {
    @EnvironmentObject private var app: AppModel
    let item: MediaItem
    var body: some View { DownloadControlBody(downloads: app.downloads, item: item) }
}

private struct DownloadControlBody: View {
    @EnvironmentObject private var app: AppModel
    @ObservedObject var downloads: Downloads
    let item: MediaItem
    @State private var choosing = false

    var body: some View {
        if let account = app.account, ["Movie", "Episode", "Video"].contains(item.type) {
            if let entry = downloads.entry(for: account, itemID: item.id) {
                let words = DownloadWords.state(entry, downloads.progress(account, entry.id), wifiOnly: downloads.wifiOnly(account))
                Menu {
                    switch entry.job.phase {
                    case .paused: Button("Resume download") { downloads.resume(account, entry.id) }
                    case .failed: Button("Retry download") { downloads.retry(account, entry.id) }
                    case .missing: Button("Download again") { downloads.redownload(account, entry.id) }
                    case .ready, .deleting: EmptyView()
                    default: Button("Pause download") { downloads.pause(account, entry.id) }
                    }
                    if entry.job.phase == .ready { Button("Stream from the server instead") { app.play(item, stream: true) } }
                    Button("Show in Downloads") { app.showingDownloads = true }
                    Button("Delete download", role: .destructive) { downloads.delete(account, entry.id) }
                } label: {
                    Label(entry.job.phase == .ready ? "Downloaded" : words, systemImage: entry.job.phase == .ready ? "checkmark.circle.fill" : "arrow.down.circle")
                }
                .secondaryAction()
                .accessibilityLabel(words)
            } else {
                Button { choosing = true } label: { Label("Download", systemImage: "arrow.down.circle") }
                    .secondaryAction()
                    .sheet(isPresented: $choosing) { QualitySheet(item: item, account: account).environmentObject(app) }
            }
        }
    }
}

/// OFF-01/02: scope, quality and storage, before anything is fetched.
struct QualitySheet: View {
    @EnvironmentObject private var app: AppModel
    @Environment(\.dismiss) private var dismiss
    let item: MediaItem
    let account: Account
    @State private var options: DownloadOptions?
    @State private var failed = false
    @State private var picked: OfflinePreset?

    var body: some View {
        NavigationStack {
            List {
                if let options {
                    Section {
                        ForEach(options.choices, id: \.preset) { choice in choiceRow(choice, options) }
                    } footer: { footer(options) }
                } else if failed {
                    Text("Couldn't reach the server to check what can be downloaded.").foregroundStyle(Theme.muted)
                } else {
                    ProgressView()
                }
            }
            .navigationTitle("Download \(item.name)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Download") {
                        if let picked {
                            app.downloads.download(account: account, item: item, preset: picked, cellular: false)
                        }
                        dismiss()
                    }
                    .disabled(picked == nil)
                }
            }
            .task {
                do {
                    let loaded = try await app.downloads.options(account: account, item: item)
                    options = loaded
                    picked = loaded.choices.first { usable($0, loaded) }?.preset
                } catch { failed = true }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func usable(_ choice: OfflinePresetChoice, _ options: DownloadOptions) -> Bool {
        choice.available && (choice.preset != .original || options.originalPlaysHere)
    }

    @ViewBuilder private func choiceRow(_ choice: OfflinePresetChoice, _ options: DownloadOptions) -> some View {
        let ok = usable(choice, options)
        Button { if ok { picked = choice.preset } } label: {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text(title(choice.preset)).foregroundStyle(ok ? Color.primary : Theme.muted)
                    Text(detail(choice, ok: ok, options)).font(.caption).foregroundStyle(Theme.muted)
                }
                Spacer()
                if picked == choice.preset { Image(systemName: "checkmark").foregroundStyle(Theme.accent) }
            }
        }
        .disabled(!ok)
    }

    private func title(_ preset: OfflinePreset) -> String {
        switch preset {
        case .original: "Original"
        case .video720: "Compatible · up to 720p"
        case .video1080: "Compatible · up to 1080p"
        default: preset.rawValue
        }
    }

    private func detail(_ choice: OfflinePresetChoice, ok: Bool, _ options: DownloadOptions) -> String {
        if choice.preset == .original && choice.available && !options.originalPlaysHere {
            return "This device can't play the original file. Choose Compatible."
        }
        guard choice.available else {
            switch choice.reason {
            case "denied": return "Your server account doesn't allow downloads."
            case "no-conversion-right": return "Your server account doesn't allow converting video."
            case "live": return "Live TV can't be downloaded."
            case "recording-incomplete": return "This recording hasn't finished yet."
            default: return "Not available for this title."
            }
        }
        let size = choice.estimateBytes.map { (choice.exactSize ? "" : "About ") + DownloadWords.bytes($0) } ?? "Size unknown"
        switch choice.preset {
        case .original: return "\(size) · the file as the server has it"
        default: return "\(size) · converted by the server, plays anywhere"
        }
    }

    private func footer(_ options: DownloadOptions) -> some View {
        var lines: [String] = []
        if let free = options.freeBytes { lines.append("\(DownloadWords.bytes(free)) free on this device.") }
        if let cap = options.capBytes { lines.append("Downloads use \(DownloadWords.bytes(options.usedBytes)) of their \(DownloadWords.bytes(cap)) limit.") }
        lines.append(app.downloads.wifiOnly(account) ? "Downloads wait for Wi-Fi." : "Downloads can use mobile data.")
        return Text(lines.joined(separator: " "))
    }
}
#else
/// OFF-PD03: Apple TV has no durable download storage.
struct DownloadsUnsupportedView: View {
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "arrow.down.circle").font(.system(size: 64)).foregroundStyle(Theme.muted)
            Text(Downloads.unsupportedText).font(.title3)
            Text("Downloads work on iPhone and iPad.").foregroundStyle(Theme.muted)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .focusable()
    }
}
#endif
