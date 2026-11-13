// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

private struct Removal: Identifiable { let id = UUID(); var account: Account?; var server: Server?; var count: Int; var opener: String
    var question: String { if let account { return "Remove \(account.username) from \(account.server.name) on this device? Other accounts will stay signed in." }; return "Remove \(server?.name ?? "this server") and its \(count) saved account\(count == 1 ? "" : "s") from this device? Other servers will stay saved." }
}

struct AccountsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var removal: Removal?
    @State private var error: String?
    @FocusState private var focused: String?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text("Remembered accounts").font(.title.bold()).accessibilityAddTraits(.isHeader).accessibilityIdentifier("accountsTitle")
                if let error { Text(error).foregroundStyle(.orange) }
                ForEach(model.snapshot.servers, id: \.url) { server in
                    Text(ServerLabels.label(server, all: model.snapshot.servers)).font(.title2.bold()).accessibilityAddTraits(.isHeader)
                    ForEach(model.snapshot.accounts.filter { $0.server.url == server.url }, id: \.localKey) { account in
                        VStack(alignment: .leading, spacing: 12) {
                            Button { Task { await model.select(account); if model.account != nil { dismiss() } } } label: { Label(account.username + (account.accessToken.isEmpty ? " · Sign in again" : account.localKey == model.account?.localKey ? " · Current account" : ""), systemImage: account.localKey == model.account?.localKey ? "person.crop.circle.fill.badge.checkmark" : "person.crop.circle").minimumTouchTarget() }.accessibilityIdentifier("account-\(account.userId)")
                            Button(role: .destructive) { removal = Removal(account: account, count: 1, opener: "remove-\(account.localKey)") } label: { Text("Remove \(account.username)").minimumTouchTarget() }.tint(.red).touchSecondaryInk(destructive: true).focused($focused, equals: "remove-\(account.localKey)").accessibilityIdentifier("removeAccount-\(account.userId)")
                        }
                    }
                    Button { model.beginSignIn(server: server); dismiss() } label: { Text("Sign in another user on \(ServerLabels.label(server, all: model.snapshot.servers))").minimumTouchTarget() }
                    Button(role: .destructive) { removal = Removal(server: server, count: model.snapshot.accounts.filter { $0.server.url == server.url }.count, opener: "server-\(server.url.absoluteString)") } label: { Text("Remove server").minimumTouchTarget() }.tint(.red).touchSecondaryInk(destructive: true).focused($focused, equals: "server-\(server.url.absoluteString)")
                    Divider()
                }
                if model.account != nil {
                    Button { Task { await model.signOut() } } label: { Text("Sign out").minimumTouchTarget() }
                        .accessibilityIdentifier("signOut")
                }
                Button { model.beginSignIn(); dismiss() } label: { Label("Add a server", systemImage: "plus").minimumTouchTarget() }.accessibilityIdentifier("addServer")
            }.secondaryActionStyle().padding(Theme.gutter).frame(maxWidth: 900, alignment: .leading).frame(maxWidth: .infinity)
        }.pictureshowNavigationTitle("Accounts").background(Theme.background.ignoresSafeArea())
            .sheet(item: $removal, onDismiss: { }) { request in RemovalConfirmation(request: request, cancel: { focused = request.opener; removal = nil }, commit: {
                do { try model.remove(account: request.account, server: request.server); removal = nil; error = nil }
                catch { self.error = error.localizedDescription; throw error }
            }) }
    }
}

private struct RemovalConfirmation: View {
    let request: Removal
    var cancel: () -> Void
    var commit: () throws -> Void
    @State private var committing = false
    @State private var error: String?
    @FocusState private var cancelFocused: Bool
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 24) {
            Text(request.question).font(.title2).fixedSize(horizontal: false, vertical: true)
            if let error { Text(error).foregroundStyle(.orange) }
            Button(action: cancel) { Text("Cancel").minimumTouchTarget() }.focused($cancelFocused).disabled(committing).accessibilityHint(request.question).accessibilityIdentifier("cancelRemoval")
            Button(role: .destructive) { committing = true; do { try commit() } catch { self.error = error.localizedDescription; committing = false } } label: { Text("Remove").minimumTouchTarget() }.touchSecondaryInk(destructive: true).disabled(committing).accessibilityIdentifier("confirmRemoval")
        }.secondaryActionStyle().padding(Theme.gutter).frame(maxWidth: 720, alignment: .leading).frame(maxWidth: .infinity) }
            .background(Theme.background.ignoresSafeArea())
            .interactiveDismissDisabled(committing).onAppear { cancelFocused = true }
            #if os(tvOS)
            .onExitCommand { if !committing { cancel() } }
            #endif
    }
}
