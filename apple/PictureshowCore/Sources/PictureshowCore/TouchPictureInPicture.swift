// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// What decides whether the touch player may shrink into picture-in-picture (TOUCH-15).
public struct PictureInPictureState: Equatable, Sendable {
    public var tvInterface: Bool
    public var deviceSupports: Bool
    public var hasVideo: Bool
    public var error: Bool
    public var switching: Bool
    public var exiting: Bool
    public var atEnd: Bool
    public var handedOff: Bool

    public init(tvInterface: Bool, deviceSupports: Bool, hasVideo: Bool, error: Bool, switching: Bool, exiting: Bool, atEnd: Bool, handedOff: Bool) {
        self.tvInterface = tvInterface
        self.deviceSupports = deviceSupports
        self.hasVideo = hasVideo
        self.error = error
        self.switching = switching
        self.exiting = exiting
        self.atEnd = atEnd
        self.handedOff = handedOff
    }
}

/// One control in the picture-in-picture window, left to right in its slot.
public struct PictureInPictureAction: Equatable, Sendable {
    /// "back10", "play", "pause" or "forward10".
    public let action: String
    public let enabled: Bool
    public init(action: String, enabled: Bool) { self.action = action; self.enabled = enabled }
}

public enum PictureInPicture {
    /// The widest window shape the window allows either way round: 2.39:1 and 1:2.39.
    public static let maxRatioNumerator = 239
    public static let maxRatioDenominator = 100

    /// TOUCH-15: a touch player shrinks into picture-in-picture when the viewer leaves for Home while a video is on
    /// screen. Not on a TV interface, a device without the feature, an error or loading-next card, a player already
    /// leaving, a finished title, or a viewing handed to another screen (AirPlay, Cast, a remote TV).
    public static func allowed(_ state: PictureInPictureState) -> Bool {
        !state.tvInterface && state.deviceSupports && state.hasVideo && !state.error && !state.switching
            && !state.exiting && !state.atEnd && !state.handedOff
    }

    /// The window's actions: Back 10 seconds, Play or Pause, Forward 10 seconds. Each keeps its slot when it can't act
    /// (a live channel with no time-shift). `primary` is the player's own primary action: "play", "pause" or nil.
    public static func actions(primary: String?, canSeekBack: Bool, canSeekForward: Bool) -> [PictureInPictureAction] {
        [
            PictureInPictureAction(action: "back10", enabled: canSeekBack),
            PictureInPictureAction(action: primary == "pause" ? "pause" : "play", enabled: primary != nil),
            PictureInPictureAction(action: "forward10", enabled: canSeekForward),
        ]
    }

    /// The window takes the video's display shape, clamped to 1:2.39..2.39:1; with no known size yet it is 16:9.
    /// `pixelRatio` is the pixel aspect ratio of anamorphic sources; non-positive counts as square.
    public static func aspect(width: Int, height: Int, pixelRatio: Double = 1.0) -> (width: Int, height: Int) {
        guard width > 0, height > 0 else { return (16, 9) }
        let shownWidth = max(Int64((Double(width) * (pixelRatio > 0 ? pixelRatio : 1.0)).rounded(.towardZero)), 1)
        let ratio = Double(shownWidth) / Double(height)
        let limit = Double(maxRatioNumerator) / Double(maxRatioDenominator)
        if ratio > limit { return (maxRatioNumerator, maxRatioDenominator) }
        if ratio < 1 / limit { return (maxRatioDenominator, maxRatioNumerator) }
        let divisor = gcd(shownWidth, Int64(height))
        return (Int(shownWidth / divisor), Int(Int64(height) / divisor))
    }

    private static func gcd(_ a: Int64, _ b: Int64) -> Int64 { b == 0 ? a : gcd(b, a % b) }
}
