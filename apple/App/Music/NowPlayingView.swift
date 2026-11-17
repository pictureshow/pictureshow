// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

/// The mini player under every tab (MUS-03): what's playing, Play/Pause and Next, and a way into Now playing.
struct MiniPlayer: View {
    @EnvironmentObject private var app: AppModel
    @ObservedObject var music: MusicHost

    var body: some View {
        if let card = music.currentCard {
            HStack(spacing: platformValue(tv: 24, touch: 12)) {
                Button { music.showingNowPlaying = true } label: {
                    HStack(spacing: platformValue(tv: 24, touch: 12)) {
                        MusicArtwork(url: card.art).frame(width: platformValue(tv: 72, touch: 44))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(card.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                            if let artist = card.artist ?? card.albumArtist { Text(artist).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                        }
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(MusicRowStyle())
                .accessibilityLabel("Now playing: \(card.title)")
                .accessibilityHint("Opens Now playing")
                .accessibilityIdentifier("music:mini")
                Button { music.toggle() } label: {
                    Image(systemName: music.isPlaying ? "pause.fill" : "play.fill").font(.title3).frame(width: 44, height: 44).contentShape(Rectangle())
                }
                .buttonStyle(MusicRowStyle())
                .accessibilityLabel(music.isPlaying ? "Pause" : "Play")
                .accessibilityIdentifier("music:mini-toggle")
                Button { music.next() } label: { Image(systemName: "forward.fill").font(.title3).frame(width: 44, height: 44).contentShape(Rectangle()) }
                    .buttonStyle(MusicRowStyle())
                    .disabled(!music.hasNext)
                    .accessibilityLabel("Next")
            }
            .padding(.horizontal, platformValue(tv: Theme.gutter, touch: 12))
            .padding(.vertical, platformValue(tv: 12, touch: 6))
            #if os(tvOS)
            .background(.regularMaterial)
            .focusSection()
            #else
            .background(.bar)
            #endif
        }
    }
}

/// The music sheets and notices over the whole app: Now playing, Replace queue, a failed save and short messages.
struct MusicChrome: ViewModifier {
    @EnvironmentObject private var app: AppModel
    @ObservedObject var music: MusicHost

    func body(content: Content) -> some View {
        content
            #if os(tvOS)
            .fullScreenCover(isPresented: $music.showingNowPlaying) { NowPlayingView(music: music).environmentObject(app) }
            #else
            .sheet(isPresented: $music.showingNowPlaying) { NowPlayingView(music: music).environmentObject(app) }
            #endif
            .confirmationDialog("Replace the queue?", isPresented: Binding(get: { music.prompt != nil }, set: { if !$0 { music.dismissPrompt() } }), titleVisibility: .visible) {
                Button("Replace queue") { music.confirm() }
                Button("Keep queue", role: .cancel) { music.dismissPrompt() }
            } message: {
                Text("Playing \(music.prompt?.play.contextName ?? "this") clears your current queue.")
            }
            .alert("Queue not saved", isPresented: Binding(get: { music.canRetrySave && music.message != nil }, set: { if !$0 { music.message = nil } })) {
                Button("Try again") { music.message = nil; music.retrySave() }
                Button("Not now", role: .cancel) { music.message = nil }
            } message: { Text(music.message ?? "Couldn't save the queue on this device.") }
            .overlay(alignment: .top) {
                if let message = music.message, !music.canRetrySave {
                    Text(message)
                        .font(.callout.weight(.semibold))
                        .padding(.horizontal, 18).padding(.vertical, 12)
                        .background(Capsule().fill(Theme.elevated).shadow(radius: 8))
                        .padding(.top, platformValue(tv: 40, touch: 8))
                        .transition(.move(edge: .top).combined(with: .opacity))
                        .accessibilityIdentifier("music:message")
                        .task(id: message) {
                            try? await Task.sleep(for: .seconds(4))
                            if music.message == message { withAnimation { music.message = nil } }
                        }
                        .onAppear { UIAccessibility.post(notification: .announcement, argument: message) }
                }
            }
            .animation(.easeOut(duration: 0.2), value: music.message)
    }
}

/// Now playing (MUS-03): art, title, artist and album, place and length, transport, seek, repeat, shuffle, what the
/// output is, and the editable queue.
struct NowPlayingView: View {
    @EnvironmentObject private var app: AppModel
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var music: MusicHost
    @State private var scrubbing: Double?

    var body: some View {
        Group {
            if let card = music.currentCard {
                #if os(tvOS)
                HStack(alignment: .top, spacing: 80) {
                    VStack(alignment: .leading, spacing: 28) {
                        MusicArtwork(url: card.art).frame(width: 520)
                        facts(card)
                    }
                    .frame(width: 560)
                    VStack(alignment: .leading, spacing: 28) {
                        transport
                        modes
                        queue
                    }
                }
                .padding(Theme.gutter)
                .onPlayPauseCommand { music.toggle() }
                #else
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        MusicArtwork(url: card.art).frame(maxWidth: 360).frame(maxWidth: .infinity)
                        facts(card)
                        scrubber
                        transport
                        modes
                        queue
                    }
                    .padding(Theme.gutter)
                }
                #endif
            } else {
                MessageView(symbol: "music.note", title: "Nothing is playing.", actionTitle: "Close") { dismiss() }
            }
        }
        .pageBackground()
        #if os(iOS)
        .presentationDragIndicator(.visible)
        #endif
        .accessibilityIdentifier("music:now-playing")
    }

    private func facts(_ card: MusicCard) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(card.title).font(.title2.weight(.bold)).lineLimit(2).accessibilityAddTraits(.isHeader)
            if let artist = card.artist ?? card.albumArtist { Text(artist).font(.headline).foregroundStyle(.secondary) }
            if let album = card.album { Text(album).font(.subheadline).foregroundStyle(Theme.muted) }
            if let output = music.output {
                Text(output.label).font(.caption.weight(.semibold)).foregroundStyle(output.converted ? Theme.warning : Theme.muted).padding(.top, 6)
                    .accessibilityIdentifier("music:output")
                if let reason = output.reason { Text(reason).font(.caption).foregroundStyle(Theme.muted) }
            }
            #if os(tvOS)
            progress.padding(.top, 12)
            #endif
        }
    }

    private var timeLine: String {
        let at = Format.clock(scrubbing ?? music.positionSeconds)
        return music.durationSeconds.map { "\(at) / \(Format.clock($0))" } ?? at
    }

    #if os(tvOS)
    private var progress: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let duration = music.durationSeconds, duration > 0 {
                ProgressView(value: min(1, music.positionSeconds / duration)).tint(Theme.accent)
            }
            Text(timeLine).font(.caption.monospacedDigit()).foregroundStyle(Theme.muted)
        }
        .accessibilityElement(children: .combine)
    }
    #else
    private var scrubber: some View {
        VStack(spacing: 4) {
            if let duration = music.durationSeconds, duration > 0 {
                Slider(value: Binding(get: { scrubbing ?? music.positionSeconds }, set: { scrubbing = $0 }), in: 0...duration) { editing in
                    if !editing, let target = scrubbing { music.seek(to: target); scrubbing = nil }
                }
                .accessibilityLabel("Seek")
                .accessibilityValue(timeLine)
            }
            HStack {
                Text(Format.clock(scrubbing ?? music.positionSeconds))
                Spacer()
                if let duration = music.durationSeconds { Text(Format.clock(duration)) }
            }
            .font(.caption.monospacedDigit()).foregroundStyle(Theme.muted)
            .accessibilityHidden(true)
        }
    }
    #endif

    private var transport: some View {
        HStack(spacing: platformValue(tv: 28, touch: 20)) {
            Button { music.previous() } label: { glyph("backward.fill") }.accessibilityLabel("Previous")
            #if os(tvOS)
            Button { music.skip(by: -15) } label: { glyph("gobackward.15") }.accessibilityLabel("Back 15 seconds")
            #endif
            Button { music.toggle() } label: { glyph(music.isPlaying ? "pause.fill" : "play.fill").font(.title) }
                .accessibilityLabel(music.isPlaying ? "Pause" : "Play")
                .accessibilityIdentifier("music:toggle")
            #if os(tvOS)
            Button { music.skip(by: 15) } label: { glyph("goforward.15") }.accessibilityLabel("Forward 15 seconds")
            #endif
            Button { music.next() } label: { glyph("forward.fill") }.disabled(!music.hasNext).accessibilityLabel("Next")
            Button { music.stop(); dismiss() } label: { glyph("stop.fill") }.accessibilityLabel("Stop")
            if music.buffering { ProgressView() }
        }
        .font(.title2)
        .modifier(MusicTransportStyle())
        #if os(tvOS)
        .focusSection()
        #endif
    }

    /// A transport glyph with a full-size target (44 pt on touch), not just the symbol's own bounds.
    private func glyph(_ name: String) -> some View {
        Image(systemName: name).frame(minWidth: platformValue(tv: 0, touch: 52), minHeight: platformValue(tv: 0, touch: 48)).contentShape(Rectangle())
    }

    private var modes: some View {
        HStack(spacing: platformValue(tv: 24, touch: 10)) {
            Button { music.cycleRepeat() } label: {
                Label(repeatLabel, systemImage: music.repeatMode == "one" ? "repeat.1" : "repeat")
            }
            .secondaryAction()
            .accessibilityIdentifier("music:repeat")
            Button { music.setShuffle(!music.shuffled) } label: {
                Label(music.shuffled ? "Shuffle: on" : "Shuffle: off", systemImage: "shuffle")
            }
            .secondaryAction()
            .accessibilityIdentifier("music:shuffle")
        }
        #if os(tvOS)
        .focusSection()
        #endif
    }

    private var repeatLabel: String {
        switch music.repeatMode { case "all": "Repeat: all"; case "one": "Repeat: this track"; default: "Repeat: off" }
    }

    private var queue: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                SectionHeader(title: "Up next")
                Spacer()
                if !music.upNext.isEmpty { Button("Clear up next") { music.clearUpcoming() }.secondaryAction() }
            }
            let upcoming = music.upNext
            if upcoming.isEmpty { Text("Nothing after this one.").foregroundStyle(Theme.muted) }
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(Array(upcoming.enumerated()), id: \.element) { index, entry in
                    QueueRow(music: music, entry: entry, index: index, count: upcoming.count)
                }
            }
        }
        #if os(tvOS)
        .focusSection()
        #endif
    }
}

/// One upcoming entry. Move and remove are buttons on TV and accessible actions on touch, as well as the menu.
private struct QueueRow: View {
    @ObservedObject var music: MusicHost
    let entry: String
    let index: Int
    let count: Int

    var body: some View {
        let card = music.card(entry: entry)
        let title = card?.title ?? "Unknown track"
        let position = (music.order.firstIndex(of: entry) ?? 0)
        HStack(spacing: 12) {
            Button { music.jump(entry) } label: {
                HStack(spacing: 12) {
                    MusicArtwork(url: card?.art).frame(width: platformValue(tv: 64, touch: 40))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).lineLimit(1)
                        if let artist = card?.artist { Text(artist).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                    }
                    Spacer(minLength: 0)
                }
                .padding(.vertical, 6)
                .contentShape(Rectangle())
            }
            .buttonStyle(MusicRowStyle())
            .accessibilityHint("Plays this now")
            .contextMenu { menu(title: title, position: position) }
            .accessibilityAction(named: "Move \(title) up") { if index > 0 { music.move(entry, to: position - 1) } }
            .accessibilityAction(named: "Move \(title) down") { if index < count - 1 { music.move(entry, to: position + 1) } }
            .accessibilityAction(named: "Remove \(title)") { music.remove(entry) }
            #if os(tvOS)
            Button { music.move(entry, to: position - 1) } label: { Image(systemName: "arrow.up") }.buttonStyle(MusicRowStyle()).disabled(index == 0).accessibilityLabel("Move \(title) up")
            Button { music.move(entry, to: position + 1) } label: { Image(systemName: "arrow.down") }.buttonStyle(MusicRowStyle()).disabled(index >= count - 1).accessibilityLabel("Move \(title) down")
            Button { music.remove(entry) } label: { Image(systemName: "xmark") }.buttonStyle(MusicRowStyle()).accessibilityLabel("Remove \(title)")
            #endif
        }
        .accessibilityIdentifier("music:queue:\(entry)")
    }

    @ViewBuilder private func menu(title: String, position: Int) -> some View {
        if index > 0 { Button("Move up", systemImage: "arrow.up") { music.move(entry, to: position - 1) } }
        if index < count - 1 { Button("Move down", systemImage: "arrow.down") { music.move(entry, to: position + 1) } }
        Button("Remove", systemImage: "xmark", role: .destructive) { music.remove(entry) }
    }
}

private struct MusicTransportStyle: ViewModifier {
    func body(content: Content) -> some View {
        #if os(tvOS)
        content.buttonStyle(TVActionStyle(prominent: false))
        #else
        content.buttonStyle(.borderless).frame(minHeight: 48)
        #endif
    }
}
