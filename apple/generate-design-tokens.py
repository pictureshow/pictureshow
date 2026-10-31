#!/usr/bin/env python3
"""Generate the Apple palette and type roles from spec/design/tokens.json.
Usage: python3 apple/generate-design-tokens.py [path/to/tokens.json]
Only token values are emitted; private worktree paths never enter generated source.
"""
import json, pathlib, sys
root = pathlib.Path(__file__).resolve().parent
source = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else root.parent / 'spec/design/tokens.json'
tokens = json.loads(source.read_text())['tokens']
colors = {'background':'color.app.window','muted':'color.app.muted','accent':'color.app.accent','placeholder':'color.app.artworkPlaceholder','warning':'color.app.warning','field':'color.app.field','outline':'color.app.fieldOutline','surface':'color.touch.card'}
lines = ['// SPDX-License-Identifier: MPL-2.0', '// Copyright (c) Legitimate LLC.', '// Generated from spec/design/tokens.json; run apple/generate-design-tokens.py.', 'import SwiftUI', '', 'enum DesignTokens {']
for name,key in colors.items():
    h = tokens[key]['value'].removeprefix('#')
    rgba = [int(h[i:i+2], 16)/255 for i in (0,2,4,6)]
    lines.append(f'    static let {name} = Color(red: {rgba[0]:.8f}, green: {rgba[1]:.8f}, blue: {rgba[2]:.8f}, opacity: {rgba[3]:.8f})')
for role,key in [('display','headlineLarge'),('title','titleLarge'),('body','bodyLarge'),('label','titleMedium'),('caption','bodySmall')]:
    v=tokens['type.touch.'+key]['value']
    lines.append(f'    static let {role}Size: CGFloat = {v["fontSize"]}')
lines += ['}', '', 'struct TokenFont: ViewModifier {', '    @ScaledMetric private var size: CGFloat', '    let weight: Font.Weight', '    init(_ size: CGFloat, weight: Font.Weight = .regular) { _size = ScaledMetric(wrappedValue: size); self.weight = weight }', '    func body(content: Content) -> some View { content.font(.system(size: size, weight: weight)) }', '}', '']
(root/'App/DesignTokens.swift').write_text('\n'.join(lines))
