// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
// Generated from spec/design/tokens.json; run apple/generate-design-tokens.py.
import SwiftUI

enum DesignTokens {
    static let background = Color(red: 0.06274510, green: 0.07450980, blue: 0.10980392, opacity: 1.00000000)
    static let muted = Color(red: 0.72549020, green: 0.75686275, blue: 0.83921569, opacity: 1.00000000)
    static let accent = Color(red: 0.72156863, green: 0.76862745, blue: 1.00000000, opacity: 1.00000000)
    static let placeholder = Color(red: 0.14901961, green: 0.17647059, blue: 0.23921569, opacity: 1.00000000)
    static let warning = Color(red: 1.00000000, green: 0.80000000, blue: 0.72156863, opacity: 1.00000000)
    static let field = Color(red: 0.12549020, green: 0.14901961, blue: 0.20784314, opacity: 1.00000000)
    static let outline = Color(red: 0.45490196, green: 0.47843137, blue: 0.55686275, opacity: 1.00000000)
    static let surface = Color(red: 0.12549020, green: 0.13725490, blue: 0.18823529, opacity: 1.00000000)
    #if os(tvOS)
    static let displaySize: CGFloat = 64.0
    static let titleSize: CGFloat = 44.0
    static let bodySize: CGFloat = 32.0
    static let labelSize: CGFloat = 32.0
    static let captionSize: CGFloat = 24.0
    #else
    static let displaySize: CGFloat = 32.0
    static let titleSize: CGFloat = 22.0
    static let bodySize: CGFloat = 16.0
    static let labelSize: CGFloat = 16.0
    static let captionSize: CGFloat = 12.0
    #endif
}

struct TokenFont: ViewModifier {
    @ScaledMetric private var size: CGFloat
    let weight: Font.Weight
    init(_ size: CGFloat, weight: Font.Weight = .regular) { _size = ScaledMetric(wrappedValue: size); self.weight = weight }
    func body(content: Content) -> some View { content.font(.system(size: size, weight: weight)) }
}
