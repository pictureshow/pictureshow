// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
#if os(iOS)
import AVKit
import OSLog
import PictureshowCore

private let log = Logger(subsystem: "app.pictureshow", category: "PictureInPicture")

/// TOUCH-15 on iPhone and iPad: the system picture-in-picture window over the player's own video layer.
///
/// Going Home starts it on its own while the gate allows (`canStartPictureInPictureAutomaticallyFromInline`), More
/// starts it on demand, expanding returns to the full player with the same viewing, and closing the window stops the
/// viewing through the controller's normal Stop report. Never built on Apple TV.
@MainActor final class PictureInPictureHost: NSObject, ObservableObject {
    /// The window is on screen (starting counts).
    @Published private(set) var active = false
    /// AVKit can start a window for this layer right now.
    @Published private(set) var possible = false

    weak var controller: PlaybackController?
    /// Called as the window opens, so the player can close its sheets and menus and hide its controls.
    var onEnter: () -> Void = {}
    /// AUTH-18: whether the window is up and its viewing plays (rate above 0), reported on every change, so the
    /// relock timer runs while a window sits paused or finished.
    var onUse: (_ active: Bool, _ playing: Bool) -> Void = { _, _ in }
    /// The viewing plays (the player's rate is above 0).
    private(set) var playing = false
    private var rateObservation: NSKeyValueObservation?

    private(set) var system: AVPictureInPictureController?
    private var possibleObservation: NSKeyValueObservation?
    private var allowed = false
    private var linear = false
    /// Set when the viewer expands the window, so the stop that follows isn't taken as closing it.
    private var restoring = false

    static var deviceSupports: Bool { AVPictureInPictureController.isPictureInPictureSupported() }

    #if DEBUG
    /// Simulator captures only: show More's entry on the gate alone (see PlayerContent).
    static let preview = ProcessInfo.processInfo.environment["PICTURESHOW_PIP_PREVIEW"] == "1"
    #endif

    /// What the window may do, from the controller's gate. Live without time-shift can't skip, so the window doesn't either.
    struct Settings: Equatable {
        var allowed: Bool
        var linear: Bool
    }

    /// Settings for a viewing right now: TOUCH-15's gate on a touch interface, plus whether skipping is possible.
    static func settings(for controller: PlaybackController) -> Settings {
        let state = controller.pictureInPictureState(tvInterface: false, deviceSupports: deviceSupports)
        let actions = controller.pictureInPictureActions
        return Settings(allowed: PictureInPicture.allowed(state), linear: !actions[0].enabled && !actions[2].enabled)
    }

    func attach(_ layer: AVPlayerLayer) {
        log.info("attach: supported=\(Self.deviceSupports, privacy: .public)")
        guard system == nil, Self.deviceSupports else { return }
        let system = AVPictureInPictureController(contentSource: .init(playerLayer: layer))
        system.delegate = self
        self.system = system
        possibleObservation = system.observe(\.isPictureInPicturePossible, options: [.initial, .new]) { [weak self] pip, _ in
            let possible = pip.isPictureInPicturePossible
            log.info("possible=\(possible, privacy: .public)")
            Task { @MainActor in self?.possible = possible }
        }
        if let player = layer.player {
            rateObservation = player.observe(\.rate, options: [.initial, .new]) { [weak self] player, _ in
                let rate = player.rate
                Task { @MainActor in self?.rateChanged(rate) }
            }
        }
        apply()
    }

    /// The viewing's rate changed (play, pause, the end): reported while the window is up.
    func rateChanged(_ rate: Float) {
        let playing = rate > 0
        guard playing != self.playing else { return }
        self.playing = playing
        if active { onUse(true, playing) }
    }

    func update(_ settings: Settings) {
        allowed = settings.allowed
        linear = settings.linear
        apply()
    }

    /// More's "Picture in picture". False when the gate or AVKit says no.
    @discardableResult func start() -> Bool {
        #if DEBUG
        // The simulator has no window: the preview shows the screen left behind, so its wording can be checked.
        if Self.preview, system == nil || system?.isPictureInPicturePossible == false { willStart(); return true }
        #endif
        guard allowed, let system, system.isPictureInPicturePossible else { return false }
        system.startPictureInPicture()
        return true
    }

    private func apply() {
        guard let system else { return }
        // Leaving for Home shrinks the player only while there is something to keep watching.
        if system.canStartPictureInPictureAutomaticallyFromInline != allowed { system.canStartPictureInPictureAutomaticallyFromInline = allowed }
        if system.requiresLinearPlayback != linear { system.requiresLinearPlayback = linear }
    }

    // MARK: Window events (called by AVKit on the main thread; also driven directly by tests)

    func willStart() {
        active = true
        restoring = false
        onEnter()
        onUse(true, playing)
    }

    func failedToStart() {
        log.error("failed to start")
        active = false
        onUse(false, false)
    }

    /// The viewer expanded the window: the full player is still presented underneath, with the same viewing.
    func restore() { restoring = true }

    func didStop() {
        active = false
        onUse(false, false)
        defer { restoring = false }
        // Closing the window (its X) stops the viewing as Stop would: the stopped report, then the player leaves.
        if !restoring { controller?.close() }
    }
}

extension PictureInPictureHost: AVPictureInPictureControllerDelegate {
    nonisolated func pictureInPictureControllerWillStartPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        MainActor.assumeIsolated { willStart() }
    }

    nonisolated func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        MainActor.assumeIsolated { failedToStart() }
    }

    nonisolated func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        MainActor.assumeIsolated { restore() }
        completionHandler(true)
    }

    nonisolated func pictureInPictureControllerDidStopPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        MainActor.assumeIsolated { didStop() }
    }
}
#endif
