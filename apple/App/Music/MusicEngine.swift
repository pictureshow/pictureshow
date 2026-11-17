// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import AVFoundation
import Foundation

/// One queue entry ready to play: the entry id the queue knows it by, and the stream the server gave for it.
struct MusicEngineItem: Equatable {
    var entry: String
    var url: URL
}

/// Why the output stopped on a track.
enum MusicEngineFailure: Equatable { case revoked, network, media }

/// What the output tells the host. All on the main actor, in the order they happened.
@MainActor protocol MusicEngineEvents: AnyObject {
    /// A new entry became current. `natural` is true when the one before played to its end (or repeated).
    func engineAdvanced(to entry: String, natural: Bool)
    /// The last held entry played to its end and nothing follows it in the output.
    func engineEnded()
    func engineFailed(entry: String?, _ failure: MusicEngineFailure)
    /// Playing, buffering or duration changed.
    func engineChanged()
}

/// The audio output the host drives. It holds a short window of entries (MusicQueue.window) and nothing else: the
/// queue, its order and what comes next stay in the shared reducer.
@MainActor protocol MusicEngine: AnyObject {
    var events: MusicEngineEvents? { get set }
    /// The entries held, in play order, starting with the current one.
    var held: [String] { get }
    var playing: String? { get }
    var isPlaying: Bool { get }
    var buffering: Bool { get }
    var seconds: Double { get }
    var duration: Double? { get }
    /// Repeat this track (repeat one, or repeat all of a one-track queue): the output restarts it itself.
    var repeatsOne: Bool { get set }
    func replace(_ items: [MusicEngineItem], at seconds: Double)
    func removeFirst(_ count: Int)
    func removeLast(_ count: Int)
    func append(_ items: [MusicEngineItem])
    func setPlaying(_ on: Bool)
    func seek(to seconds: Double)
    func stop()
}

/// AVQueuePlayer holding the queue window. The next entries are already in the player, so AVFoundation opens them ahead
/// and moves on without the host in the loop; how seamless that is depends on the format (MUS-07, see PARITY.md).
@MainActor final class QueuePlayerEngine: NSObject, MusicEngine {
    weak var events: MusicEngineEvents?
    private(set) var player = AVQueuePlayer()
    private var entries: [ObjectIdentifier: String] = [:]
    private var observers: [NSKeyValueObservation] = []
    private var itemObservers: [ObjectIdentifier: NSKeyValueObservation] = [:]
    private var notifications: [NSObjectProtocol] = []
    private var lastCurrent: AVPlayerItem?
    /// The item that just played to its end, so the next current item counts as a natural move.
    private weak var finished: AVPlayerItem?
    private var wantsPlay = false
    var repeatsOne = false { didSet { player.actionAtItemEnd = repeatsOne ? .none : .advance } }

    override init() {
        super.init()
        configure()
    }

    private func configure() {
        player.automaticallyWaitsToMinimizeStalling = true
        player.actionAtItemEnd = repeatsOne ? .none : .advance
        #if os(iOS)
        player.allowsExternalPlayback = true
        #endif
        observers = [
            player.observe(\.currentItem, options: [.new]) { [weak self] _, _ in Task { @MainActor in self?.currentChanged() } },
            player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in Task { @MainActor in self?.events?.engineChanged() } },
        ]
        let center = NotificationCenter.default
        notifications = [
            center.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: nil, queue: .main) { [weak self] note in
                let item = note.object as? AVPlayerItem
                MainActor.assumeIsolated { self?.playedToEnd(item) }
            },
            center.addObserver(forName: AVPlayerItem.failedToPlayToEndTimeNotification, object: nil, queue: .main) { [weak self] note in
                let item = note.object as? AVPlayerItem, error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
                MainActor.assumeIsolated { self?.failed(item, error) }
            },
        ]
    }

    var held: [String] { player.items().compactMap { entries[ObjectIdentifier($0)] } }
    var playing: String? { player.currentItem.flatMap { entries[ObjectIdentifier($0)] } }
    var isPlaying: Bool { player.timeControlStatus == .playing }
    var buffering: Bool { player.timeControlStatus == .waitingToPlayAtSpecifiedRate && player.reasonForWaitingToPlay != AVPlayer.WaitingReason.noItemToPlay }
    var seconds: Double { let s = player.currentTime().seconds; return s.isFinite ? max(0, s) : 0 }
    var duration: Double? {
        guard let d = player.currentItem?.duration.seconds, d.isFinite, d > 0 else { return nil }
        return d
    }

    private func make(_ item: MusicEngineItem) -> AVPlayerItem {
        let asset = AVURLAsset(url: item.url)
        let playerItem = AVPlayerItem(asset: asset)
        // Music needs no look-ahead beyond what the window already holds; a few seconds keep Next quick.
        playerItem.preferredForwardBufferDuration = 30
        let key = ObjectIdentifier(playerItem)
        entries[key] = item.entry
        itemObservers[key] = playerItem.observe(\.status, options: [.new]) { [weak self] observed, _ in
            guard observed.status == .failed else { return }
            let error = observed.error
            Task { @MainActor in self?.failed(observed, error) }
        }
        return playerItem
    }

    func replace(_ items: [MusicEngineItem], at seconds: Double) {
        if player.status == .failed { rebuild() }
        finished = nil
        lastCurrent = nil
        player.removeAllItems()
        forgetUnheld()
        for item in items { player.insert(make(item), after: nil) }
        if seconds > 0 { player.seek(to: CMTime(seconds: seconds, preferredTimescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero) }
        if wantsPlay { player.play() }
        currentChanged()
    }

    func removeFirst(_ count: Int) {
        for item in player.items().prefix(count) where item !== player.currentItem { player.remove(item) }
        forgetUnheld()
    }

    func removeLast(_ count: Int) {
        let items = player.items()
        for item in items.suffix(count) where item !== player.currentItem { player.remove(item) }
        forgetUnheld()
    }

    func append(_ items: [MusicEngineItem]) {
        for item in items { player.insert(make(item), after: nil) }
    }

    func setPlaying(_ on: Bool) {
        wantsPlay = on
        if on { player.play() } else { player.pause() }
    }

    func seek(to seconds: Double) {
        player.seek(to: CMTime(seconds: max(0, seconds), preferredTimescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    func stop() {
        wantsPlay = false
        player.pause()
        player.removeAllItems()
        observers.forEach { $0.invalidate() }
        observers = []
        itemObservers.values.forEach { $0.invalidate() }
        itemObservers = [:]
        notifications.forEach(NotificationCenter.default.removeObserver)
        notifications = []
        entries = [:]
        events = nil
    }

    /// A player that failed can't be used again, so the next window gets a new one.
    private func rebuild() {
        observers.forEach { $0.invalidate() }
        notifications.forEach(NotificationCenter.default.removeObserver)
        player = AVQueuePlayer()
        configure()
    }

    private func forgetUnheld() {
        let live = Set(player.items().map(ObjectIdentifier.init))
        for key in entries.keys where !live.contains(key) {
            entries[key] = nil
            itemObservers[key]?.invalidate()
            itemObservers[key] = nil
        }
    }

    private func ours(_ item: AVPlayerItem?) -> Bool { item.map { entries[ObjectIdentifier($0)] != nil } ?? false }

    private func playedToEnd(_ item: AVPlayerItem?) {
        guard let item, ours(item) else { return }
        if repeatsOne, item === player.currentItem {
            // The output holds just this entry: start it again and count a natural move, as the reducer expects.
            player.seek(to: .zero, toleranceBefore: .zero, toleranceAfter: .zero)
            if wantsPlay { player.play() }
            if let entry = entries[ObjectIdentifier(item)] { events?.engineAdvanced(to: entry, natural: true) }
            return
        }
        finished = item
        // The last held item: AVQueuePlayer has nothing to move to, so the end is reported here.
        if player.items().last === item || player.items().isEmpty {
            Task { @MainActor in
                if self.player.currentItem == nil || self.player.currentItem === item { self.finished = nil; self.events?.engineEnded() }
            }
        }
    }

    private func currentChanged() {
        let current = player.currentItem
        guard current !== lastCurrent else { return }
        let natural = finished != nil && finished === lastCurrent
        lastCurrent = current
        finished = nil
        forgetUnheld()
        guard let current, let entry = entries[ObjectIdentifier(current)] else { return }
        events?.engineAdvanced(to: entry, natural: natural)
    }

    private func failed(_ item: AVPlayerItem?, _ error: Error?) {
        guard let item, ours(item) else { return }
        let entry = entries[ObjectIdentifier(item)]
        events?.engineFailed(entry: entry, Self.classify(item: item, error: error))
    }

    /// 401 is a revoked token; a transport failure keeps the place for Play to try again; anything else skips the track.
    static func classify(item: AVPlayerItem?, error: Error?) -> MusicEngineFailure {
        let status = item?.errorLog()?.events.last?.errorStatusCode ?? 0
        if status == 401 { return .revoked }
        if let error = error as NSError? {
            if error.domain == NSURLErrorDomain, error.code != NSURLErrorFileDoesNotExist { return .network }
            if let underlying = error.userInfo[NSUnderlyingErrorKey] as? NSError, underlying.domain == NSURLErrorDomain { return .network }
            if error.domain == AVFoundationErrorDomain, [AVError.Code.contentIsUnavailable.rawValue].contains(error.code), status == 0 { return .network }
        }
        return .media
    }
}
