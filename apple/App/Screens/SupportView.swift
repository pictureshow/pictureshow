// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import StoreKit
import SwiftUI

struct SupportView: View {
    @EnvironmentObject private var supporter: SupporterModel
    @Environment(\.scenePhase) private var scenePhase
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
                    Text("Thank you for supporting Pictureshow!").font(.headline)
                    LabeledContent("Your supporter code") {
                        Text(code)
                            .monospacedDigit()
                            #if os(iOS)
                            .textSelection(.enabled)
                            #endif
                    }
                    Text("Email info@legitimateapps.com with your request and supporter code.")
                    Text("Requests are not guaranteed to be built or delivered by a particular date.")
                        .foregroundStyle(.secondary)
                    management
                }
            } else {
                Section {
                    Text("Pictureshow is free and open source. Supporters pay monthly to have their feature requests read and considered first.")
                    Text("Requests are not guaranteed to be built or delivered by a particular date.")
                        .foregroundStyle(.secondary)
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
                .disabled(supporter.activity != nil)
                #if os(tvOS)
                .prefersDefaultFocus(supporter.offer == nil, in: supportFocus)
                #endif
            }

            Section {
                #if os(iOS)
                Link("Terms of Use", destination: Self.terms)
                Link("Privacy Policy", destination: Self.privacy)
                #else
                LabeledContent("Terms of Use", value: Self.terms.absoluteString)
                LabeledContent("Privacy Policy", value: Self.privacy.absoluteString)
                #endif
            }
        }
        .navigationTitle("Support Pictureshow")
        #if os(iOS)
        .scrollContentBackground(.hidden)
        .pageBackground()
        .manageSubscriptionsSheet(isPresented: $showManagement)
        #else
        .focusScope(supportFocus)
        .onChange(of: supporter.offer != nil) { _, hasOffer in
            if hasOffer { resetFocus(in: supportFocus) }
        }
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
            }
            Text("Renews monthly until cancelled. Cancel anytime in the App Store.")
                .foregroundStyle(.secondary)
            if supporter.notice != .pending {
                Button {
                    Task { await supporter.purchase() }
                } label: {
                    if supporter.activity == .purchasing {
                        ProgressView { Text("Waiting for the App Store to confirm your payment.") }
                    } else {
                        Text("Become a supporter")
                    }
                }
                .disabled(supporter.activity != nil || supporter.loading)
                #if os(tvOS)
                .prefersDefaultFocus(true, in: supportFocus)
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

    @ViewBuilder private var management: some View {
        #if os(iOS)
        Button("Manage or cancel in the App Store") { showManagement = true }
        Text("To manage or cancel, open Settings, tap your name, then tap Subscriptions.")
            .foregroundStyle(.secondary)
        #else
        Text("To manage or cancel, open Settings, choose your Apple Account profile, then select Subscriptions.")
            .focusable()
            .prefersDefaultFocus(true, in: supportFocus)
        #endif
    }

    private var status: LocalizedStringKey? {
        switch supporter.notice {
        case .pending: "Waiting for the App Store to confirm your payment."
        case .restoredNothing: "No active Supporter subscription was found for this Apple Account."
        case .failed(let error): error.message
        case nil: nil
        }
    }

    private static let terms = URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!
    private static let privacy = URL(string: "https://pictureshow.app/privacy/")!
}
