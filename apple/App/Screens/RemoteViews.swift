// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
#if os(iOS)
import SwiftUI
import PictureshowCore

/// "Play on": the viewer's TVs and other apps that this phone can control (REM-01).
struct PlayOnSheet: View {
    @EnvironmentObject private var app: AppModel
    @ObservedObject var remote: RemoteControl
    @State private var targets: [RemotePlaybackTarget] = []
    @State private var loading = true
    @State private var failure: String?

    var body: some View {
        NavigationStack {
            List {
                if let failure {
                    Text(failure).foregroundStyle(.secondary)
                } else if targets.isEmpty && !loading {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("No TVs found").font(.headline)
                        Text("Open Pictureshow on your TV, signed in to this server, and it shows up here.")
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 6)
                }
                ForEach(targets) { target in
                    Button { remote.attach(target) } label: { row(target) }
                        .accessibilityIdentifier("play-on-\(target.name)")
                }
            }
            .overlay { if loading && targets.isEmpty { ProgressView() } }
            .refreshable { await load() }
            .navigationTitle(remote.pendingPlay.map { "Play \($0.item.name) on" } ?? "Control a TV")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { remote.choosing = false } } }
        }
        .task { await load() }
    }

    private func row(_ target: RemotePlaybackTarget) -> some View {
        HStack(spacing: 14) {
            Image(systemName: "tv").font(.title3).foregroundStyle(.secondary).frame(width: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(target.name).font(.body).foregroundStyle(.primary)
                if let viewer = target.sameViewer ? nil : target.viewerName {
                    Text("Signed in as \(viewer)").font(.footnote).foregroundStyle(.secondary)
                }
                if let title = target.playingTitle {
                    Text("Playing \(title)").font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
        .frame(minHeight: 48)
        .contentShape(Rectangle())
    }

    private func load() async {
        loading = true
        do { targets = try await remote.targets(); failure = nil }
        catch { failure = "Couldn't ask the server for your TVs. \(error.localizedDescription)" }
        loading = false
    }
}

/// The remote for the attached TV (REM-04, REM-06). Every control reflects what the TV reports.
struct RemoteScreen: View {
    @ObservedObject var remote: RemoteControl
    @Environment(\.dismiss) private var dismiss
    @State private var scrub: Double?
    @State private var volume: Double?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    header
                    if remote.link == .lost { lost } else if remote.now != nil { transport; tracks; volumeControl } else { idle }
                    actions
                }
                .padding(20)
            }
            .navigationTitle(remote.targetName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }

    private var header: some View {
        VStack(spacing: 6) {
            Image(systemName: "tv").font(.system(size: 40)).foregroundStyle(.secondary)
            if let viewer = remote.otherViewerName {
                Text("Signed in as \(viewer)").font(.footnote).foregroundStyle(.secondary)
            }
            // A fixed-height line, so a status appearing or clearing never moves a control (REM-04).
            Text(remote.status ?? " ").font(.footnote).foregroundStyle(.secondary).lineLimit(2)
                .frame(minHeight: 36, alignment: .top).multilineTextAlignment(.center)
        }
    }

    private var idle: some View {
        Text("Nothing is playing on \(remote.targetName). Pick a title and choose Play on \(remote.targetName).")
            .multilineTextAlignment(.center).foregroundStyle(.secondary)
    }

    private var lost: some View {
        VStack(spacing: 12) {
            Text("\(remote.targetName) isn't connected any more").font(.headline)
            Text("It may have restarted or closed Pictureshow. Choose it again to keep controlling it.")
                .multilineTextAlignment(.center).foregroundStyle(.secondary)
            Button("Choose a TV") { remote.choose() }.primaryAction()
        }
    }

    private var transport: some View {
        VStack(spacing: 16) {
            Text(remote.now?["title"] as? String ?? "Playing").font(.title3.weight(.semibold)).multilineTextAlignment(.center)
            if remote.durationSeconds > 0 {
                VStack(spacing: 4) {
                    Slider(value: Binding(get: { scrub ?? remote.positionSeconds }, set: { scrub = $0 }), in: 0...remote.durationSeconds) { editing in
                        if !editing, let target = scrub { remote.seek(toSeconds: target); scrub = nil }
                    }
                    .disabled(!remote.canSeek)
                    .accessibilityLabel("Position")
                    HStack {
                        Text(Self.clock(scrub ?? remote.positionSeconds))
                        Spacer()
                        Text(Self.clock(remote.durationSeconds))
                    }
                    .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                }
            }
            HStack(spacing: 0) {
                control("backward.end.fill", "Previous") { remote.previous() }
                control("gobackward.10", "Back 10 seconds") { remote.skip(by: -10) }.disabled(!remote.canSeek)
                control(remote.paused ? "play.fill" : "pause.fill", remote.paused ? "Play" : "Pause", large: true) { remote.togglePause() }
                control("goforward.30", "Forward 30 seconds") { remote.skip(by: 30) }.disabled(!remote.canSeek)
                control("forward.end.fill", "Next") { remote.next() }
            }
            Button(role: .destructive) { remote.stop() } label: { Label("Stop", systemImage: "stop.fill") }
                .secondaryAction()
        }
    }

    private func control(_ symbol: String, _ label: String, large: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(large ? .largeTitle : .title2).frame(maxWidth: .infinity, minHeight: 56)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    @ViewBuilder private var tracks: some View {
        let audio = remote.tracks.filter { $0.type == "Audio" }, subtitles = remote.tracks.filter { $0.type == "Subtitle" }
        if !audio.isEmpty || !subtitles.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                if remote.commands.contains("SetAudioStreamIndex"), audio.count > 1 {
                    trackMenu("Audio", audio, selected: remote.now?["audio"] as? Int, off: false) { remote.audio($0) }
                }
                if remote.commands.contains("SetSubtitleStreamIndex"), !subtitles.isEmpty {
                    trackMenu("Subtitles", subtitles, selected: remote.now?["subtitle"] as? Int, off: true) { remote.subtitle($0) }
                }
                if remote.now?["audio"] is NSNull || remote.now?["subtitle"] is NSNull {
                    Text("The TV shows its choice here after you pick one.").font(.footnote).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func trackMenu(_ title: String, _ list: [[String: Any]], selected: Int?, off: Bool, pick: @escaping (Int) -> Void) -> some View {
        let label = selected == -1 ? "Off" : list.first { $0.index == selected }?["label"] as? String
        return Menu {
            if off { Button { pick(-1) } label: { if selected == -1 { Label("Off", systemImage: "checkmark") } else { Text("Off") } } }
            ForEach(list.indices, id: \.self) { i in
                let track = list[i], name = track["label"] as? String ?? "Track \(track.index)"
                Button { pick(track.index) } label: { if track.index == selected { Label(name, systemImage: "checkmark") } else { Text(name) } }
            }
        } label: {
            HStack {
                Text(title)
                Spacer()
                Text(label ?? "Not reported").foregroundStyle(.secondary).lineLimit(1)
                Image(systemName: "chevron.up.chevron.down").font(.footnote).foregroundStyle(.secondary)
            }
            .frame(minHeight: 44)
        }
    }

    @ViewBuilder private var volumeControl: some View {
        if remote.supportsVolume, let level = remote.now?["volume"] as? Int {
            HStack {
                Image(systemName: "speaker.fill").foregroundStyle(.secondary)
                Slider(value: Binding(get: { volume ?? Double(level) }, set: { volume = $0 }), in: 0...100) { editing in
                    if !editing, let v = volume { remote.volume(Int(v.rounded())); volume = nil }
                }
                .accessibilityLabel("Volume")
                Image(systemName: "speaker.wave.3.fill").foregroundStyle(.secondary)
            }
        } else if remote.now != nil {
            Text("Use the TV's own remote for volume.").font(.footnote).foregroundStyle(.secondary)
        }
    }

    private var actions: some View {
        VStack(spacing: 12) {
            if remote.link == .attached {
                Button { remote.choose() } label: { Text("Choose another TV").frame(maxWidth: .infinity, minHeight: 48) }
                    .secondaryAction()
            }
            Button { remote.disconnect(); dismiss() } label: { Text("Disconnect").frame(maxWidth: .infinity, minHeight: 48) }
                .secondaryAction()
            Text("Disconnecting leaves the TV playing.").font(.footnote).foregroundStyle(.secondary)
        }
        .padding(.top, 8)
    }

    static func clock(_ seconds: Double) -> String {
        let s = Int(max(0, seconds)), h = s / 3600, m = s / 60 % 60, r = s % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, r) : String(format: "%d:%02d", m, r)
    }
}

/// The persistent bar while a TV is attached: status first, TV name second; tapping opens the remote and sends nothing.
///
/// On iOS 26.1 and later it sits in the tab bar's accessory slot, where the system draws its background and keeps it
/// clear of the floating tab bar; earlier systems get it as a bar above the tab bar.
struct RemoteBar: View {
    @ObservedObject var remote: RemoteControl
    var accessory = false

    var body: some View {
        if remote.link != .none {
            Button { remote.showingRemote = true } label: {
                HStack(spacing: 12) {
                    Image(systemName: "tv").font(accessory ? .body : .title3)
                    VStack(alignment: .leading, spacing: accessory ? 0 : 2) {
                        Text(line).font(accessory ? .footnote.weight(.semibold) : .subheadline.weight(.semibold)).lineLimit(1)
                        Text(remote.targetName).font(accessory ? .caption2 : .footnote).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.up").foregroundStyle(.secondary)
                }
                .padding(.horizontal, 16)
                .frame(minHeight: accessory ? 0 : 64)
                .frame(maxHeight: accessory ? .infinity : nil)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .background { if !accessory { Rectangle().fill(.bar) } }
            .overlay(alignment: .top) { if !accessory { Divider() } }
            .accessibilityIdentifier("remote-bar")
        }
    }

    /// Whether the system accessory slot carries the bar (so the per-tab inset stays empty).
    static var usesAccessory: Bool { if #available(iOS 26.1, *) { true } else { false } }

    private var line: String {
        if let status = remote.status { return status }
        if remote.link == .lost { return "Not connected" }
        guard let now = remote.now else { return "Nothing playing" }
        let title = now["title"] as? String ?? "Playing"
        return remote.paused ? "Paused · \(title)" : title
    }
}

private extension Dictionary where Key == String, Value == Any {
    var type: String { self["type"] as? String ?? "" }
    var index: Int { self["index"] as? Int ?? 0 }
}
#endif
