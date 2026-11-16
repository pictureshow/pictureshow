// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
#if os(iOS)
import PictureshowCore
import SwiftUI

/// AUTH-15: Settings → Approve a TV sign-in. The viewer types the code a TV shows and approves it with this device's
/// own sign-in; the TV then signs in as them.
struct QuickConnectApproveView: View {
    @EnvironmentObject private var app: AppModel
    let account: Account
    @State private var code = ""
    @State private var busy = false
    @State private var outcome: QuickConnectApproval.Result?
    @FocusState private var focused: Bool

    var body: some View {
        Form {
            Section {
                Text("On the TV, choose Use Quick Connect. Enter the 6-character code it shows to sign it in as \(account.username) on \(account.server.name).")
                TextField("Code", text: $code)
                    .font(.title2.monospaced().weight(.semibold))
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .keyboardType(.asciiCapable)
                    .textContentType(.oneTimeCode)
                    .focused($focused)
                    .submitLabel(.go)
                    .onSubmit(approve)
                    .onChange(of: code) { outcome = nil }
                    .accessibilityLabel("Code")
                Button(action: approve) {
                    HStack {
                        Text(busy ? "Approving…" : "Approve")
                        if busy { Spacer(); ProgressView() }
                    }
                }
                .disabled(busy || code.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            if let outcome {
                Section {
                    Label(message(outcome), systemImage: outcome == .approved ? "checkmark.circle.fill" : "exclamationmark.triangle")
                        .foregroundStyle(outcome == .approved ? Theme.accent : Theme.warning)
                }
            }
        }
        .navigationTitle("Approve a TV sign-in")
        .navigationBarTitleDisplayMode(.inline)
        .scrollContentBackground(.hidden)
        .pageBackground()
        .onAppear { focused = true }
    }

    private func approve() {
        guard !busy else { return }
        guard QuickConnectApproval.valid(code) else { outcome = .invalidInput; return }
        guard let client = app.client else { outcome = .offline; return }
        busy = true; outcome = nil
        Task {
            let result = await client.approveQuickConnect(account: account, input: code)
            busy = false
            outcome = result
            if result == .signInRequired { app.expire(account) }
            UIAccessibility.post(notification: .announcement, argument: message(result))
        }
    }

    private func message(_ result: QuickConnectApproval.Result) -> String {
        switch result {
        case .approved: return "Approved. The TV signs in as \(account.username) in a moment."
        case .invalidInput: return "Codes are 6 letters and numbers. Check the code on the TV."
        case .wrongOrExpired: return "That code didn't work. It may have expired: the TV shows a new one by itself, so enter the code it shows now."
        case .disabled: return "Quick Connect is turned off on this server. A server administrator can turn it on in the server's Dashboard."
        case .offline: return "Pictureshow can't reach your server right now. Check your connection and try again."
        case .signInRequired: return "Your sign-in on this device has ended. Sign in again, then approve the code."
        case .failed: return "The server couldn't approve the code. Try again in a moment."
        }
    }
}
#endif
