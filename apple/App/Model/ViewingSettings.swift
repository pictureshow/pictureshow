// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

/// Device choices for continuous viewing (PLAY-30), the photo slideshow (PHOTO-02) and the TV screensaver (SAVER-01).
/// Kept apart from `Preferences` so older saved preferences decode unchanged.
@MainActor final class ViewingSettings: ObservableObject {
    static let shared = ViewingSettings()

    static let countdownChoices = [0, 5, 10, 15, 20, 30]

    /// Seconds the next-episode card counts down before playing; 0 waits for the viewer.
    @Published var countdownSeconds: Int { didSet { defaults.set(countdownSeconds, forKey: "viewing.countdown") } }
    @Published var stillWatching: StillWatching { didSet { defaults.set(stillWatching.rawValue, forKey: "viewing.stillWatching") } }
    @Published var slideshowSeconds: Int { didSet { defaults.set(slideshowSeconds, forKey: "viewing.slideshow") } }
    /// Minutes without input before the screensaver; 0 is Off.
    @Published var screensaverMinutes: Int { didSet { defaults.set(screensaverMinutes, forKey: "viewing.screensaver") } }
    /// Highest age rating the screensaver shows; nil is any rating.
    @Published var screensaverMaxAge: Int? { didSet { defaults.set(screensaverMaxAge ?? -1, forKey: "viewing.screensaverAge") } }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let countdown = defaults.object(forKey: "viewing.countdown") as? Int
        countdownSeconds = countdown.flatMap { Self.countdownChoices.contains($0) ? $0 : nil } ?? 10
        stillWatching = defaults.string(forKey: "viewing.stillWatching").flatMap(StillWatching.init(rawValue:)) ?? .threeEpisodes
        let slideshow = defaults.object(forKey: "viewing.slideshow") as? Int
        slideshowSeconds = slideshow.flatMap { Discovery.slideshowSeconds.contains($0) ? $0 : nil } ?? 5
        let saver = defaults.object(forKey: "viewing.screensaver") as? Int
        screensaverMinutes = saver.flatMap { Discovery.screensaverMinutes.contains($0) ? $0 : nil } ?? 5
        let age = defaults.object(forKey: "viewing.screensaverAge") as? Int
        screensaverMaxAge = age.flatMap { Discovery.screensaverAges.contains($0) ? $0 : nil }
    }

    static func countdownTitle(_ seconds: Int) -> String { seconds == 0 ? "Off" : "\(seconds) seconds" }

    static func countdownDetail(_ seconds: Int) -> String {
        seconds == 0 ? "The next episode waits for you to choose Play now." : "How long the next episode card counts down before it plays."
    }

    static func stillWatchingTitle(_ policy: StillWatching) -> String {
        policy == .off ? "Never" : "After \(policy.episodes) episodes"
    }

    static func stillWatchingDetail(_ policy: StillWatching) -> String {
        guard policy != .off else { return "Episodes keep playing until you stop them." }
        let hours = policy.minutes / 60
        let time = policy.minutes % 60 == 30 ? "\(hours)½ hours" : hours == 1 ? "1 hour" : "\(hours) hours"
        return "Also asks after \(time) without \(Self.inputPhrase), before the next episode starts."
    }

    #if os(tvOS)
    static let inputPhrase = "a button press"
    #else
    static let inputPhrase = "a touch"
    #endif

    static func screensaverTitle(_ minutes: Int) -> String { minutes == 0 ? "Off" : "After \(minutes) minutes" }
    static func ageTitle(_ age: Int?) -> String { age.map { "Ages \($0) and under" } ?? "Any rating" }
}

/// When the viewer last pressed or touched anything, and how many episodes started by themselves since (PLAY-30).
/// The screensaver (SAVER-01) waits on the same clock.
@MainActor final class ViewerInput {
    static let shared = ViewerInput()
    #if DEBUG
    /// Test seam: PICTURESHOW_INPUT_AGE_MIN=<minutes> starts the app as if the last press was that long ago.
    private(set) var lastInput = Date().addingTimeInterval(-60 * (Double(ProcessInfo.processInfo.environment["PICTURESHOW_INPUT_AGE_MIN"] ?? "") ?? 0))
    #else
    private(set) var lastInput = Date()
    #endif
    private(set) var automaticStarts = 0
    /// Called for every touch or press anywhere in the app.
    var onInput: (() -> Void)?

    func record() {
        lastInput = Date()
        automaticStarts = 0
        onInput?()
    }

    func startedByItself() { automaticStarts += 1 }

    /// Whether the episode ending now should ask instead of starting the next one.
    func stillWatchingDue(policy: StillWatching, now: Date = Date()) -> Bool {
        Discovery.stillWatchingDue(policy: policy, episodesInARow: automaticStarts + 1, minutesSinceInput: Int64(now.timeIntervalSince(lastInput) / 60))
    }

    #if DEBUG
    func reset(lastInput: Date = Date(), automaticStarts: Int = 0) { self.lastInput = lastInput; self.automaticStarts = automaticStarts }
    #endif
}

/// Watches every touch and remote press in a window without taking any of them.
final class InputWatcher: UIGestureRecognizer, UIGestureRecognizerDelegate {
    init() {
        super.init(target: nil, action: nil)
        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
        delegate = self
        allowedPressTypes = (0...6).map { NSNumber(value: $0) }
    }

    private func seen() {
        MainActor.assumeIsolated { ViewerInput.shared.record() }
        state = .failed
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) { seen() }
    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent) { seen() }
    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
}

/// Puts one `InputWatcher` on the window that hosts it.
struct InputWatcherInstaller: UIViewRepresentable {
    final class Probe: UIView {
        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard let window, !(window.gestureRecognizers ?? []).contains(where: { $0 is InputWatcher }) else { return }
            window.addGestureRecognizer(InputWatcher())
        }
    }
    func makeUIView(context: Context) -> Probe { let view = Probe(); view.isUserInteractionEnabled = false; return view }
    func updateUIView(_ view: Probe, context: Context) {}
}
