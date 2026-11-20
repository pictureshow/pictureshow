// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import StoreKit
import SwiftUI

struct SupportView: View {
    @EnvironmentObject private var supporter: SupporterModel
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.purchase) private var purchaseProduct
    #if os(tvOS)
    @Environment(\.resetFocus) private var resetFocus
    @Namespace private var supportFocus
    #else
    @State private var showManagement = false
    #endif

    var body: some View {
        Form {
            if let code = supporter.supporterCode {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Label("Thank you for supporting Pictureshow!", systemImage: "checkmark.seal.fill")
                            .font(.headline)
                            .accessibilityAddTraits(.isHeader)
                        ViewThatFits(in: .horizontal) {
                            HStack(alignment: .firstTextBaseline, spacing: 12) {
                                Text("Your supporter code")
                                supporterCode(code)
                            }
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Your supporter code")
                                supporterCode(code)
                            }
                        }
                        Text("Email info@legitimateapps.com with your request and supporter code.")
                        Text("Requests are not guaranteed to be built or delivered by a particular date.")
                            .foregroundStyle(.secondary)
                        #if os(tvOS)
                        managementInstructions
                        #endif
                    }
                    #if os(tvOS)
                    .accessibilityElement(children: .combine)
                    .focusable()
                    .prefersDefaultFocus(true, in: supportFocus)
                    #else
                    .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
                    #endif
                    #if os(iOS)
                    Button("Manage or cancel in the App Store") { showManagement = true }
                    managementInstructions
                    #endif
                }
            } else {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Pictureshow is free and open source. Supporters pay monthly to have their feature requests read and considered first.")
                        Text("Requests are not guaranteed to be built or delivered by a particular date.")
                            .foregroundStyle(.secondary)
                    }
                    #if os(tvOS)
                    .accessibilityElement(children: .combine)
                    .focusable()
                    #endif
                }
                Section {
                    offer
                }
            }

            Section {
                if let status {
                    Text(status)
                        #if os(tvOS)
                        .focusable()
                        #endif
                }
                Button {
                    Task { await supporter.restore() }
                } label: {
                    if supporter.activity == .restoring {
                        ProgressView { Text("Restoring purchases…") }
                    } else {
                        Text("Restore Purchases")
                    }
                }
                #if os(tvOS)
                .prefersDefaultFocus(supporter.offer == nil || supporter.notice == .pending, in: supportFocus)
                #else
                .disabled(supporter.activity != nil)
                #endif
            }

            Section {
                #if os(iOS)
                Link("Terms of Use", destination: Self.terms)
                Link("Privacy Policy", destination: Self.privacy)
                #else
                ForEach([("Terms of Use", Self.terms), ("Privacy Policy", Self.privacy)], id: \.0) { label, url in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(LocalizedStringKey(label))
                        Text(url.absoluteString).foregroundStyle(.secondary)
                    }
                    .accessibilityElement(children: .combine)
                    .focusable()
                }
                #endif
            }
        }
        .navigationTitle("Support Pictureshow")
        #if os(iOS)
        .scrollContentBackground(.hidden)
        .frame(maxWidth: 672)
        .frame(maxWidth: .infinity)
        .pageBackground()
        .manageSubscriptionsSheet(isPresented: $showManagement)
        #else
        .focusScope(supportFocus)
        .onChange(of: supporter.offer != nil) { _, hasOffer in
            if hasOffer { resetFocus(in: supportFocus) }
        }
        .onChange(of: supporter.supporterCode != nil) { _, _ in resetFocus(in: supportFocus) }
        .onChange(of: supporter.notice == .pending) { _, _ in resetFocus(in: supportFocus) }
        #endif
        .task { await supporter.refreshOffer() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await supporter.refreshOffer() } }
        }
    }

    @ViewBuilder private var offer: some View {
        if let offer = supporter.offer {
            VStack(alignment: .leading, spacing: 4) {
                Text(offer.name).font(.subheadline)
                Text("\(offer.displayPrice) / month").font(.title2.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                Text("Renews monthly until cancelled. Cancel anytime in the App Store.")
                    .foregroundStyle(.secondary)
                if supporter.notice == .pending {
                    Label("Waiting for the App Store to confirm your payment.", systemImage: "clock")
                        .padding(.top, 6)
                }
            }
            #if os(tvOS)
            .accessibilityElement(children: .combine)
            .focusable()
            #endif
            if supporter.notice != .pending {
                Button {
                    Task {
                        await supporter.purchase { product in try await purchaseProduct(product) }
                    }
                } label: {
                    if supporter.activity == .purchasing {
                        ProgressView { Text("Waiting for the App Store to confirm your payment.") }
                    } else {
                        Text("Become a supporter")
                    }
                }
                .primaryAction()
                #if os(tvOS)
                .listRowBackground(Color.clear)
                .prefersDefaultFocus(true, in: supportFocus)
                #else
                .disabled(supporter.activity != nil || supporter.loading)
                #endif
            }
        } else if let failure = supporter.offerFailure, !supporter.loading {
            Text(failure.message)
                #if os(tvOS)
                .focusable()
                #endif
        } else {
            ProgressView { Text("Checking the App Store…") }
        }
    }

    private func supporterCode(_ code: String) -> some View {
        Text(code)
            .font(.headline.monospaced())
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            #if os(iOS)
            .textSelection(.enabled)
            #endif
    }

    @ViewBuilder private var managementInstructions: some View {
        #if os(iOS)
        Text("To manage or cancel, open Settings, tap your name, then tap Subscriptions.")
            .foregroundStyle(.secondary)
        #else
        Text("To manage or cancel, open Settings, choose your Apple Account profile, then select Subscriptions.")
            .foregroundStyle(.secondary)
        #endif
    }

    private var status: LocalizedStringKey? {
        switch supporter.notice {
        case .pending: supporter.offer == nil ? "Waiting for the App Store to confirm your payment." : nil
        case .restoredNothing: "No active Supporter subscription was found for this Apple Account."
        case .failed(let error): error.message
        case nil: nil
        }
    }

    private static let terms = URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!
    private static let privacy = URL(string: "https://pictureshow.app/privacy/")!
}
