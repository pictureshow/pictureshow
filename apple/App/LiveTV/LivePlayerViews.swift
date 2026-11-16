// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

#if os(iOS)
/// The touch player's live timeline (LIVE-05): Live at the edge, Go live when behind, a negative clock, Buffer expired,
/// and a track that only covers the time the player holds. Without time shift there is no track and no seeking.
struct LiveTimeline: View {
    @ObservedObject var controller: PlaybackController
    var interacted: () -> Void = {}
    @State private var scrubbing: Double?

    var body: some View {
        let live = controller.live
        let readout = LivePresentation.timelineReadout(window: live.window, position: live.positionMs, paused: live.paused,
                                                       expired: live.expired, canTimeShift: live.canTimeShift)
        let visual = LivePresentation.timelineVisualStatus(readout, behindMs: live.behindMs)
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                if live.atEdge && !live.paused {
                    HStack(spacing: 6) {
                        Circle().fill(LiveStatusBadge.liveRed).frame(width: 8, height: 8)
                        Text("LIVE").font(.caption.weight(.bold))
                    }
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Capsule().fill(Color.black.opacity(0.45)))
                }
                if !readout.clock.isEmpty {
                    Text(readout.clock).font(.footnote.weight(.bold).monospacedDigit()).accessibilityHidden(true)
                }
                // The pill already says Live; the status text only adds what the pill doesn't.
                if !(live.atEdge && !live.paused) {
                    Text(visual).font(.footnote.weight(.semibold).monospacedDigit()).lineLimit(1)
                }
                Spacer()
                if live.canGoLive {
                    Button { controller.goLive(); interacted() } label: { Label("Go live", systemImage: "dot.radiowaves.forward") }
                        .font(.footnote.weight(.semibold))
                        .buttonStyle(.borderedProminent)
                        .tint(Color(red: 0.78, green: 0.13, blue: 0.13)) // white label at about 6:1
                }
            }
            if live.canTimeShift, let edge = live.window.goLivePosition(), edge > 0 {
                let shown = scrubbing ?? Double(min(max(live.positionMs, 0), edge))
                Slider(value: Binding(get: { shown }, set: { scrubbing = $0 }), in: 0...Double(edge)) { editing in
                    if !editing, let target = scrubbing {
                        controller.seek(to: Double(live.window.offsetMs + Int64(target)) / 1000)
                        scrubbing = nil
                        interacted()
                    }
                }
                .tint(Theme.accent)
                .disabled(live.expired)
                .accessibilityLabel("Live position")
                .accessibilityValue(LivePresentation.timelineLabel(paused: live.paused, canTimeShift: true, expired: live.expired, behindMs: live.behindMs))
                let ends = LivePresentation.bufferEndpointLabels(window: live.window, position: live.positionMs, expired: live.expired)
                HStack {
                    Text(ends.0)
                    Spacer()
                    Text(ends.1)
                }
                .font(.caption.monospacedDigit())
                .opacity(0.85)
                .accessibilityHidden(true)
            }
        }
        .accessibilityElement(children: .contain)
    }
}
#endif
