// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import AVFoundation
import CryptoKit
import OSLog
import UIKit
import VideoToolbox
import PictureshowCore

/// Process-local calibration. Pending, busy and unsupported probes advertise AVC.
/// A short reference qualifies its tested point, not every stream or decoder maximum.
actor AppleHEVCQualification {
    static let shared = AppleHEVCQualification()
    private static let logger = Logger(subsystem: "app.pictureshow", category: "HEVCQualification")
    private var started = false
    private var finished = false
    private var measurement: AppleHEVCDecodeMeasurement?

    func warmUp() { startIfNeeded() }
    func capabilities() async -> DeviceCapabilities? {
        startIfNeeded()
        let display = await MainActor.run { Self.currentDisplay() }
        let state = measurement != nil ? "measured" : finished ? "unavailable" : "pending"
        guard let display else {
            Self.logger.notice("Capability measurement=\(state, privacy: .public) display=unknown eligible=false")
            return nil
        }
        let capabilities = measurement.flatMap {
            AppleHEVCQualificationPolicy.capabilities(measured: $0, displayWidth: display.width, displayHeight: display.height, displayRefreshRate: display.rate)
        }
        Self.logger.notice("Capability measurement=\(state, privacy: .public) displayWidth=\(display.width, privacy: .public) displayHeight=\(display.height, privacy: .public) displayRefreshRate=\(display.rate, privacy: .public) eligible=\(capabilities != nil, privacy: .public)")
        return capabilities
    }
    private func startIfNeeded() {
        guard !started else { return }
        started = true
        Self.logger.notice("Reference measurement=pending")
        Task {
            let result = await Task.detached(priority: .utility) { await Self.measureReference() }.value
            measurement = result
            finished = true
            if let result {
                Self.logger.notice("Reference measurement=measured sourceFrames=\(result.sourceFrameCount, privacy: .public) decodedFrames=\(result.decodedFrameCount, privacy: .public) elapsedSeconds=\(result.elapsedSeconds, privacy: .public) hardware=\(result.hardwareDecoder, privacy: .public)")
            } else {
                Self.logger.notice("Reference measurement=unavailable reason=unknown")
            }
        }
    }
    private struct Display: Sendable { let width: Int; let height: Int; let rate: Double }
    @MainActor private static func currentDisplay() -> Display? {
        let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .filter { $0.activationState == .foregroundActive }.flatMap(\.windows)
        guard let screen = windows.first(where: \.isKeyWindow)?.screen,
              let mode = screen.currentMode, mode.size.width > 0, mode.size.height > 0,
              screen.maximumFramesPerSecond > 0 else { return nil }
        return Display(width: max(Int(mode.size.width), Int(mode.size.height)), height: min(Int(mode.size.width), Int(mode.size.height)), rate: Double(screen.maximumFramesPerSecond))
    }
    private static func configuration(_ format: CMFormatDescription) -> Data? {
        let atoms = CMFormatDescriptionGetExtension(format, extensionKey: kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms) as? [String: Any]
        return atoms?["hvcC"] as? Data
    }
    /// This path reads only the bundled generated calibration clip. It never
    /// touches a server, account, household device or the active AVPlayer.
    static func measureReference() async -> AppleHEVCDecodeMeasurement? {
        guard !Task.isCancelled, let url = Bundle.main.url(forResource: "hevc-main8-1080p30", withExtension: "mp4"),
              let bytes = try? Data(contentsOf: url),
              SHA256.hash(data: bytes).map({ String(format: "%02x", $0) }).joined() == "49c05630450bf2f3b6ed25e8e377bdd0a1ab669e1e862f3361cbaa8856d4bb8e" else { return nil }
        do {
            let asset = AVURLAsset(url: url)
            let tracks = try await asset.loadTracks(withMediaType: .video)
            guard tracks.count == 1, let track = tracks.first else { return nil }
            let formats = try await track.load(.formatDescriptions)
            let duration = try await asset.load(.duration)
            let rate = Double(try await track.load(.nominalFrameRate))
            guard formats.count == 1, let format = formats.first,
                  CMFormatDescriptionGetMediaSubType(format) == kCMVideoCodecType_HEVC,
                  CMTimeCompare(duration, CMTime(value: 2, timescale: 1)) == 0, rate == 30,
                  let record = configuration(format) else { return nil }
            let dimensions = CMVideoFormatDescriptionGetDimensions(format)
            guard AppleHEVCQualificationPolicy.matchesReference(configurationRecord: record, width: Int(dimensions.width), height: Int(dimensions.height)) else { return nil }
            let primaries = CMFormatDescriptionGetExtension(format, extensionKey: kCMFormatDescriptionExtension_ColorPrimaries) as? String
            let transfer = CMFormatDescriptionGetExtension(format, extensionKey: kCMFormatDescriptionExtension_TransferFunction) as? String
            let matrix = CMFormatDescriptionGetExtension(format, extensionKey: kCMFormatDescriptionExtension_YCbCrMatrix) as? String
            guard primaries == (kCMFormatDescriptionColorPrimaries_ITU_R_709_2 as String),
                  transfer == (kCMFormatDescriptionTransferFunction_ITU_R_709_2 as String),
                  matrix == (kCMFormatDescriptionYCbCrMatrix_ITU_R_709_2 as String) else { return nil }
            let output = HEVCReferenceOutput()
            var callback = VTDecompressionOutputCallbackRecord(decompressionOutputCallback: { refcon, _, status, flags, image, pts, _ in
                guard let refcon else { return }
                Unmanaged<HEVCReferenceOutput>.fromOpaque(refcon).takeUnretainedValue().record(status: status, flags: flags, image: image, pts: pts)
            }, decompressionOutputRefCon: Unmanaged.passUnretained(output).toOpaque())
            var decoder: VTDecompressionSession?
            let specification = [kVTVideoDecoderSpecification_RequireHardwareAcceleratedVideoDecoder: true] as CFDictionary
            let attributes = [kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange] as CFDictionary
            guard VTDecompressionSessionCreate(allocator: kCFAllocatorDefault, formatDescription: format, decoderSpecification: specification, imageBufferAttributes: attributes, outputCallback: &callback, decompressionSessionOut: &decoder) == noErr,
                  let decoder else { return nil }
            defer { VTDecompressionSessionInvalidate(decoder) }
            func usesHardware() -> Bool {
                var value: CFTypeRef?
                return VTSessionCopyProperty(decoder, key: kVTDecompressionPropertyKey_UsingHardwareAcceleratedVideoDecoder, allocator: kCFAllocatorDefault, valueOut: &value) == noErr && (value as? Bool) == true
            }
            guard usesHardware() else { return nil }
            let reader = try AVAssetReader(asset: asset)
            let compressed = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
            compressed.alwaysCopiesSampleData = false
            guard reader.canAdd(compressed) else { return nil }; reader.add(compressed)
            guard reader.startReading() else { return nil }
            defer { reader.cancelReading() }
            var count = 0
            let startedAt = ProcessInfo.processInfo.systemUptime
            while let sample = compressed.copyNextSampleBuffer() {
                guard !Task.isCancelled, count < 60, CMSampleBufferGetNumSamples(sample) == 1,
                      let sampleFormat = CMSampleBufferGetFormatDescription(sample), configuration(sampleFormat) == record,
                      CMVideoFormatDescriptionGetDimensions(sampleFormat).width == 1920,
                      CMVideoFormatDescriptionGetDimensions(sampleFormat).height == 1080,
                      CMTimeCompare(CMSampleBufferGetPresentationTimeStamp(sample), CMTime(value: Int64(count), timescale: 30)) == 0,
                      CMTimeCompare(CMSampleBufferGetDuration(sample), CMTime(value: 1, timescale: 30)) == 0 else { return nil }
                var flags = VTDecodeInfoFlags()
                // With both asynchronous and temporal flags clear, VT calls the
                // output callback before returning; every frame is accounted for.
                guard VTDecompressionSessionDecodeFrame(decoder, sampleBuffer: sample, flags: [], frameRefcon: nil, infoFlagsOut: &flags) == noErr,
                      !flags.contains(.frameDropped) else { return nil }
                count += 1
                guard ProcessInfo.processInfo.systemUptime - startedAt <= 2 else { return nil }
            }
            let elapsed = ProcessInfo.processInfo.systemUptime - startedAt
            let observed = output.snapshot()
            guard reader.status == .completed, count == 60, observed.frames == 60, observed.errors == 0, observed.dropped == 0,
                  elapsed.isFinite, elapsed > 0, elapsed <= 2, usesHardware() else { return nil }
            return AppleHEVCDecodeMeasurement(measuredConfigurationRecord: record, width: 1920, height: 1080,
                                              sourceFrameCount: count, decodedFrameCount: observed.frames, droppedFrameCount: observed.dropped, decodeErrorCount: observed.errors,
                                              elapsedSeconds: elapsed, sourceDurationSeconds: CMTimeGetSeconds(duration), sourceFrameRate: rate,
                                              hardwareDecoder: true, colorPrimaries: primaries, transferFunction: transfer, yCbCrMatrix: matrix)
        } catch { return nil }
    }
}

private final class HEVCReferenceOutput: @unchecked Sendable {
    private let lock = NSLock()
    private var timestamps = Set<Int>()
    private var errors = 0
    private var dropped = 0
    func record(status: OSStatus, flags: VTDecodeInfoFlags, image: CVImageBuffer?, pts: CMTime) {
        lock.lock(); defer { lock.unlock() }
        if flags.contains(.frameDropped) { dropped += 1 }
        guard status == noErr, !flags.contains(.frameDropped), let image,
              CVPixelBufferGetWidth(image) == 1920, CVPixelBufferGetHeight(image) == 1080,
              CVPixelBufferGetPixelFormatType(image) == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
              pts.isNumeric else { errors += 1; return }
        let tick = CMTimeGetSeconds(pts) * 30
        guard tick.isFinite, tick >= 0, tick < 60, abs(tick - tick.rounded()) < 0.000001,
              timestamps.insert(Int(tick.rounded())).inserted else { errors += 1; return }
    }
    func snapshot() -> (frames: Int, errors: Int, dropped: Int) {
        lock.lock(); defer { lock.unlock() }; return (timestamps.count, errors, dropped)
    }
}
