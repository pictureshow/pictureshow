// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

private struct SignOutConfirmation: ViewModifier {
    @EnvironmentObject private var app: AppModel
    @Binding var account: Account?
    func body(content: Content) -> some View {
        content.confirmationDialog("Sign out of \(account?.username ?? "this account")?", isPresented: Binding(
            get: { account != nil }, set: { if !$0 { account = nil } }
        ), titleVisibility: .visible) {
            Button("Cancel", role: .cancel) { account = nil }
            Button("Sign out", role: .destructive) {
                if let account { app.signOut(account) }
                account = nil
            }
        } message: {
            Text("You'll need your password or Quick Connect to sign in again. Your account stays saved on this device. Its downloads will be removed.")
        }
    }
}

extension View {
    func confirmSignOut(account: Binding<Account?>) -> some View { modifier(SignOutConfirmation(account: account)) }
}
