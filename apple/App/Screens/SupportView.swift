// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import StoreKit
import SwiftUI
#if os(tvOS)
import CoreImage.CIFilterBuiltins
#else
import UIKit
#endif

struct SupportView: View {
    @EnvironmentObject private var supporter: SupporterModel
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.purchase) private var purchaseProduct
    #if os(tvOS)
    @Environment(\.resetFocus) private var resetFocus
    @Namespace private var supportFocus
    @FocusState private var restoreFocused: Bool
    @State private var selectedPolicy: SupportPolicy?
    #else
    @State private var managementScene: UIWindowScene?
    @State private var managing = false
    @State private var showManagementInstructions = false
    #endif

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: platformValue(tv: 26, touch: 20)) {
                Text("Support Pictureshow")
                    .font(.largeTitle.bold())
                    .foregroundStyle(.primary)
                    .accessibilityAddTraits(.isHeader)
                if let code = supporter.supporterCode {
                    active(code)
                } else {
                    explanation
                    offer
                }
                if let status { Text(status) }
                restore
                policies
            }
            .frame(maxWidth: platformValue(tv: 1_200, touch: 640), alignment: .leading)
            .padding(.horizontal, platformValue(tv: 56, touch: 24))
            .padding(.vertical, platformValue(tv: 32, touch: 20))
            .frame(maxWidth: .infinity)
        }
        .pageBackground()
        #if os(tvOS)
        .focusScope(supportFocus)
        .sheet(item: $selectedPolicy) { policy in
            VStack(spacing: 28) {
                Text(policy.title).font(.title.bold()).accessibilityAddTraits(.isHeader)
                SupportPolicyQR(url: policy.url, side: 320)
                Text(policy.url.absoluteString).font(.callout)
            }
            .padding(56)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .pageBackground()
        }
        .onChange(of: supporter.offer != nil) { _, loaded in
            if loaded { resetFocus(in: supportFocus) }
        }
        .onChange(of: supporter.supporterCode != nil) { _, _ in resetFocus(in: supportFocus) }
        .onChange(of: supporter.notice == .pending) { _, _ in resetFocus(in: supportFocus) }
        #else
        .navigationBarTitleDisplayMode(.inline)
        .background(SupportManagementScene { managementScene = $0 })
        .onChange(of: supporter.supporterCode) { _, _ in showManagementInstructions = false }
        #endif
        .task { await supporter.refreshOffer() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await supporter.refreshOffer() } }
        }
    }

    private var explanation: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Pictureshow is free and open source. Supporters pay monthly to have their feature requests read and considered first.")
            noGuarantee
        }
    }

    private var noGuarantee: some View {
        Text("Requests are not guaranteed to be built or delivered by a particular date.")
            .foregroundStyle(.secondary)
    }

    private func active(_ code: String) -> some View {
        VStack(alignment: .leading, spacing: platformValue(tv: 18, touch: 14)) {
            Label("Thank you for supporting Pictureshow!", systemImage: "checkmark.seal.fill")
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
            VStack(alignment: .leading, spacing: 6) {
                Text("Your supporter code").font(.subheadline).foregroundStyle(.secondary)
                #if os(tvOS)
                Text(groupedCode(code))
                    .font(.title2.monospaced().weight(.semibold))
                    .accessibilityLabel(Text(code))
                #else
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 16) {
                        selectableCode(code)
                        copyCode(code)
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        selectableCode(code)
                        copyCode(code)
                    }
                }
                #endif
            }
            Text("Email info@legitimateapps.com with your request and supporter code.")
            noGuarantee

            #if os(tvOS)
            Text("To manage or cancel, open Settings, choose your Apple Account profile, then select Subscriptions.")
                .foregroundStyle(.secondary)
            #else
            Button("Manage or cancel in the App Store") { Task { await manageSubscriptions() } }
                .disabled(managing)
                .frame(minHeight: 44, alignment: .leading)
            if showManagementInstructions {
                Text("To manage or cancel, open Settings, tap your Apple Account name, then tap Subscriptions.")
                    .foregroundStyle(.secondary)
            }
            #endif
        }
        .modifier(SupportInformationPanel())
    }

    @ViewBuilder private var offer: some View {
        if let offer = supporter.offer {
            VStack(alignment: .leading, spacing: 8) {
                Text(offer.name).font(.subheadline)
                Text("\(offer.displayPrice) / month").font(.title2.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                Text("Renews monthly until cancelled. Cancel anytime in the App Store.")
                    .foregroundStyle(.secondary)
                if supporter.notice == .pending {
                    Label("Waiting for the App Store to confirm your payment.", systemImage: "clock")
                        .padding(.top, 8)
                }
            }
            .modifier(SupportInformationPanel())

            if supporter.notice != .pending {
                Button {
                    Task { await supporter.purchase { product in try await purchaseProduct(product) } }
                } label: {
                    if supporter.activity == .purchasing {
                        ProgressView { Text("Waiting for the App Store to confirm your payment.") }
                    } else {
                        Text("Become a supporter")
                    }
                }
                .accessibilityIdentifier("supporter.purchase")
                #if os(tvOS)
                .buttonStyle(.borderedProminent)
                .prefersDefaultFocus(true, in: supportFocus)
                #else
                .primaryAction()
                .disabled(supporter.activity != nil || supporter.loading)
                #endif
            }
        } else if let failure = supporter.offerFailure, !supporter.loading {
            Text(failure.message)
        } else {
            ProgressView { Text("Checking the App Store…") }
        }
    }

    private var restore: some View {
        Button {
            Task { await supporter.restore() }
        } label: {
            Group {
                if supporter.activity == .restoring {
                    ProgressView { Text("Restoring purchases…") }
                } else {
                    Text("Restore purchases")
                }
            }
            #if os(tvOS)
            .foregroundStyle(restoreFocused ? Color.black : Theme.accent)
            #endif
        }
        .accessibilityIdentifier("supporter.restore")
        #if os(tvOS)
        .buttonStyle(.bordered)
        .focused($restoreFocused)
        .prefersDefaultFocus(supporter.notice == .pending || supporter.offer == nil || supporter.supporterCode != nil, in: supportFocus)
        #else
        .buttonStyle(.bordered)
        .disabled(supporter.activity != nil)
        #endif
    }

    @ViewBuilder private var policies: some View {
        #if os(tvOS)
        VStack(alignment: .leading, spacing: 20) {
            ForEach(SupportPolicy.allCases) { policy in
                Button { selectedPolicy = policy } label: {
                    HStack(spacing: 28) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(policy.title).font(.headline)
                            Text(policy.url.absoluteString).font(.callout).foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        SupportPolicyQR(url: policy.url, side: 112)
                    }
                    .padding(24)
                }
                .buttonStyle(.card)
                .accessibilityIdentifier(policy.accessibilityIdentifier)
            }
        }
        #else
        VStack(alignment: .leading, spacing: 4) {
            ForEach(SupportPolicy.allCases) { policy in
                Link(policy.title, destination: policy.url)
                    .frame(minHeight: 44, alignment: .leading)
            }
        }
        #endif
    }

    #if os(tvOS)
    private func groupedCode(_ code: String) -> String {
        let digits = Array(code)
        return stride(from: 0, to: digits.count, by: 4).map { start in
            String(digits[start..<min(start + 4, digits.count)])
        }.joined(separator: " ")
    }
    #else
    private func selectableCode(_ code: String) -> some View {
        Text(code).font(.headline.monospaced()).textSelection(.enabled)
    }

    private func copyCode(_ code: String) -> some View {
        Button { UIPasteboard.general.string = code } label: {
            Label("Copy", systemImage: "doc.on.doc")
                .frame(minHeight: 32)
        }
        .buttonStyle(.bordered)
    }

    @MainActor private func manageSubscriptions() async {
        guard !managing else { return }
        managing = true
        showManagementInstructions = false
        defer { managing = false }
        guard let managementScene else {
            showManagementInstructions = true
            return
        }
        do {
            try await AppStore.showManageSubscriptions(in: managementScene)
        } catch StoreKitError.userCancelled {
            return
        } catch {
            showManagementInstructions = true
        }
    }
    #endif

    private var status: LocalizedStringKey? {
        switch supporter.notice {
        case .pending: supporter.offer == nil ? "Waiting for the App Store to confirm your payment." : nil
        case .restoredNothing: "No active Supporter subscription was found for this Apple Account."
        case .failed(let error): error.message
        case nil: nil
        }
    }
}

private struct SupportInformationPanel: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(platformValue(tv: 30, touch: 16))
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surface.opacity(0.55), in: RoundedRectangle(cornerRadius: 12))
    }
}

private enum SupportPolicy: String, CaseIterable, Identifiable {
    case terms, privacy

    var id: Self { self }
    var accessibilityIdentifier: String { "supporter.\(rawValue)" }

    var title: LocalizedStringKey {
        switch self {
        case .terms: "Terms of Use"
        case .privacy: "Privacy Policy"
        }
    }

    var url: URL {
        switch self {
        case .terms: URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!
        case .privacy: URL(string: "https://pictureshow.app/privacy/")!
        }
    }
}

#if os(tvOS)
private struct SupportPolicyQR: View {
    let url: URL
    let side: CGFloat

    var body: some View {
        if let image = Self.image(for: url) {
            Image(decorative: image, scale: 1)
                .interpolation(.none)
                .resizable()
                .frame(width: side, height: side)
                .padding(side / 7)
                .background(.white)
                .accessibilityHidden(true)
        }
    }

    private static let context = CIContext()

    private static func image(for url: URL) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(url.absoluteString.utf8)
        guard let output = filter.outputImage else { return nil }
        return context.createCGImage(output, from: output.extent)
    }
}
#else
private struct SupportManagementScene: UIViewRepresentable {
    let onChange: (UIWindowScene?) -> Void

    func makeUIView(context: Context) -> Probe { Probe(onChange: onChange) }
    func updateUIView(_ view: Probe, context: Context) { view.onChange = onChange }

    final class Probe: UIView {
        var onChange: (UIWindowScene?) -> Void

        init(onChange: @escaping (UIWindowScene?) -> Void) {
            self.onChange = onChange
            super.init(frame: .zero)
            isUserInteractionEnabled = false
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { fatalError() }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            Task { @MainActor [weak self] in
                guard let self else { return }
                onChange(window?.windowScene)
            }
        }
    }
}
#endif
