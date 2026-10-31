// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

struct SignInView: View {
    @EnvironmentObject private var model: AppModel
    @State private var address = ""
    @State private var server: Server?
    @State private var username = ""
    @State private var password = ""
    @State private var error: String?
    @State private var busy = false
    @State private var quick: QuickConnectState?
    @State private var quickTask: Task<Void, Never>?
    @FocusState private var focused: String?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Label("Pictureshow", systemImage: "play.rectangle.fill").modifier(TokenFont(DesignTokens.displaySize, weight: .bold)).accessibilityAddTraits(.isHeader)
                Text(server?.name ?? "Your Jellyfin, beautifully at home.").modifier(TokenFont(DesignTokens.titleSize)).foregroundStyle(Theme.muted)
                if let server {
                    Text(server.url.host ?? "Jellyfin").modifier(TokenFont(DesignTokens.captionSize)).foregroundStyle(Theme.muted)
                    if let quick {
                        Text("Quick Connect").modifier(TokenFont(DesignTokens.titleSize, weight: .semibold)).accessibilityAddTraits(.isHeader)
                        Text(quick.code).font(.system(size: 48, weight: .bold, design: .monospaced))
                            .accessibilityLabel("Quick Connect code \(quick.code.map(String.init).joined(separator: " "))").accessibilityIdentifier("quickCode")
                        Text("In an already signed-in Jellyfin app, open Quick Connect and enter this code. Waiting for approval…").foregroundStyle(Theme.muted)
                        Button("Cancel Quick Connect") { cancelQuick() }.accessibilityIdentifier("cancelQuick")
                    } else {
                        TextField("Username", text: $username).textContentType(.username).autocorrectionDisabled().focused($focused, equals: "username").accessibilityIdentifier("username")
                        SecureField("Password", text: $password).textContentType(.password).focused($focused, equals: "password").accessibilityIdentifier("password")
                        Button("Sign in") { run { try model.signedIn(await model.client.signIn(server: server, username: username, password: password)) } }
                            .disabled(busy || username.isEmpty).accessibilityIdentifier("signIn")
                        Button("Use Quick Connect") { beginQuick(server) }.disabled(busy).accessibilityIdentifier("quickConnect")
                        Button("Change server") { cancelQuick(); self.server = nil; focused = "server" }.disabled(busy)
                    }
                } else {
                    TextField("Server address", text: $address).textContentType(.URL).autocorrectionDisabled().focused($focused, equals: "server")
                        .accessibilityLabel("Server address").accessibilityHint("Enter your Jellyfin server URL, including its port or path if needed").accessibilityIdentifier("serverAddress")
                    #if os(iOS)
                        .textInputAutocapitalization(.never).keyboardType(.URL)
                    #endif
                    Text("Connect to your own server on your network, VPN, or private server address.").font(.callout).foregroundStyle(Theme.muted)
                    Button("Connect") { run { server = try await model.client.discover(address: address); focused = "username" } }.disabled(busy || address.isEmpty).accessibilityIdentifier("connect")
                }
                if busy { ProgressView("Connecting…") }
                if let error { Text(error).foregroundStyle(.orange).accessibilityIdentifier("signInError").accessibilityAddTraits(.updatesFrequently) }
            }
            #if os(iOS)
            .textFieldStyle(.roundedBorder)
            #endif
            .buttonStyle(.borderedProminent)
            .frame(maxWidth: 680, alignment: .leading)
            .padding(Theme.gutter)
            .frame(maxWidth: .infinity, minHeight: 500)
        }
        .background(Theme.background.ignoresSafeArea())
        .onDisappear { cancelQuick() }
        #if DEBUG
        .onAppear {
            let env = ProcessInfo.processInfo.environment
            if let input = env["PICTURESHOW_TEST_SERVER"] { address = input }
            if let input = env["PICTURESHOW_TEST_USERNAME"] { username = input }
            if let input = env["PICTURESHOW_TEST_PASSWORD"] { password = input }
        }
        #endif
    }
    private func run(_ work: @escaping @MainActor () async throws -> Void) {
        error = nil; busy = true
        Task { do { try await work() } catch { self.error = error.localizedDescription }; busy = false }
    }
    private func cancelQuick() { quickTask?.cancel(); quickTask = nil; quick = nil }
    private func beginQuick(_ server: Server) {
        cancelQuick(); error = nil; busy = true
        quickTask = Task {
            defer { busy = false }
            do {
                let pending = try await model.client.initiateQuickConnect(server: server)
                quick = pending; busy = false
                let deadline = Date().addingTimeInterval(600)
                while !Task.isCancelled && Date() < deadline {
                    try await Task.sleep(for: .seconds(3))
                    let status = try await model.client.quickConnectStatus(server: server, secret: pending.secret)
                    if status.authenticated {
                        try Task.checkCancellation()
                        let account = try await model.client.authenticateQuickConnect(server: server, secret: pending.secret)
                        try Task.checkCancellation()
                        try model.signedIn(account)
                        return
                    }
                }
                if !Task.isCancelled { error = "This Quick Connect code expired. Try again."; quick = nil }
            } catch is CancellationError { } catch { self.error = error.localizedDescription; quick = nil }
        }
    }
}
