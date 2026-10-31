// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore
import UIKit

struct QuickAttempt: Codable {
    var server: Server
    var code: String
    var secret: String
    var deviceID: String
    var expiry: Date
    var announced: Bool
}

@MainActor final class SignInFlow: ObservableObject {
    @Published var attempt: QuickAttempt?
    @Published var error: String?
    @Published var busy = false
    @Published var showingQuick = false
    private var polling: Task<Void, Never>?
    init() {
        if let data = AccountVault.loadQuick(), let saved = try? JSONDecoder().decode(QuickAttempt.self, from: data), saved.expiry > Date() { attempt = saved; showingQuick = true }
    }
    func clear() { polling?.cancel(); polling = nil; attempt = nil; showingQuick = false; error = nil; busy = false; AccountVault.saveQuick(nil) }
    func hide() { showingQuick = false }
    private func persist() { AccountVault.saveQuick(attempt.flatMap { try? JSONEncoder().encode($0) }) }
    func begin(server: Server, model: AppModel) {
        showingQuick = true; error = nil
        if let attempt, attempt.server.url == server.url, attempt.expiry > Date() { poll(model: model); return }
        guard !busy else { return }
        polling?.cancel(); busy = true
        polling = Task {
            defer { busy = false }
            do {
                let code = try await model.client.initiateQuickConnect(server: server)
                try Task.checkCancellation()
                attempt = QuickAttempt(server: server, code: code.code, secret: code.secret, deviceID: model.client.deviceID, expiry: Date().addingTimeInterval(600), announced: false)
                persist(); busy = false; polling = nil; announce(); poll(model: model)
            } catch is CancellationError { } catch { self.error = error.localizedDescription; polling = nil }
        }
    }
    func announce() {
        guard var saved = attempt, !saved.announced else { return }
        saved.announced = true; attempt = saved; persist()
        UIAccessibility.post(notification: .announcement, argument: "Quick Connect code " + saved.code.map(String.init).joined(separator: " "))
    }
    func poll(model: AppModel) {
        guard polling == nil, let saved = attempt else { return }
        let client = model.makeClient(saved.deviceID)
        polling = Task {
            defer { polling = nil }
            do {
                while !Task.isCancelled && saved.expiry > Date() {
                    let status = try await client.quickConnectStatus(server: saved.server, secret: saved.secret)
                    try Task.checkCancellation()
                    if status.authenticated {
                        let account = try await client.authenticateQuickConnect(server: saved.server, secret: saved.secret)
                        try Task.checkCancellation(); try model.signedIn(account); return
                    }
                    try await Task.sleep(for: .seconds(3))
                }
                if !Task.isCancelled { self.error = "This code expired. Renew it below; the old code no longer works."; attempt = nil; persist() }
            } catch is CancellationError { } catch { self.error = error.localizedDescription }
        }
    }
}

struct SignInView: View {
    @EnvironmentObject private var model: AppModel
    var body: some View { SignInForm(flow: model.signInFlow) }
}

private struct SignInForm: View {
    @EnvironmentObject private var model: AppModel
    @ObservedObject var flow: SignInFlow
    @State private var address = ""
    @State private var server: Server?
    @State private var username = ""
    @State private var password = ""
    @State private var showPassword = false
    @State private var error: String?
    @State private var busy = false
    @State private var users: [PublicUser] = []
    @State private var quickEnabled: Bool?
    @State private var availabilityError: String?
    @State private var work: Task<Void, Never>?
    @FocusState private var focused: String?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Label("Pictureshow", systemImage: "play.rectangle.fill").modifier(TokenFont(DesignTokens.displaySize, weight: .bold)).accessibilityAddTraits(.isHeader)
                Text(server?.name ?? "Your Jellyfin, beautifully at home.").modifier(TokenFont(DesignTokens.titleSize)).foregroundStyle(Theme.muted)
                if model.sessionEnded { Text("Your session ended. Sign in again.").foregroundStyle(.orange).accessibilityIdentifier("sessionEnded") }
                if let server {
                    Text(server.url.host ?? "Jellyfin").foregroundStyle(Theme.muted)
                    if flow.showingQuick {
                        quick(server)
                    } else {
                        if !users.isEmpty {
                            Text("Public users").font(.headline).accessibilityAddTraits(.isHeader)
                            ScrollView(.horizontal) { HStack { ForEach(users, id: \.id) { user in Button(user.name) { username = user.name; focused = "password" }.buttonStyle(.bordered) } } }
                        }
                        TextField("Username", text: $username).textContentType(.username).autocorrectionDisabled().focused($focused, equals: "username").submitLabel(.next).onSubmit { focused = "password" }.accessibilityIdentifier("username")
                        HStack {
                            Group { if showPassword { TextField("Password", text: $password) } else { SecureField("Password", text: $password) } }
                                .textContentType(.password).focused($focused, equals: "password").submitLabel(.go).onSubmit { signIn(server) }.accessibilityIdentifier("password")
                            Button(showPassword ? "Hide" : "Show") { showPassword.toggle(); focused = "password" }.accessibilityLabel(showPassword ? "Hide password" : "Show password").buttonStyle(.bordered)
                        }
                        Button(busy ? "Signing in…" : "Sign in") { signIn(server) }.disabled(busy || username.isEmpty).accessibilityIdentifier("signIn")
                        if quickEnabled == true { Button("Use Quick Connect") { flow.begin(server: server, model: model) }.disabled(busy).accessibilityIdentifier("quickConnect") }
                        else if quickEnabled == false { Text("Quick Connect is disabled by this server. Use username and password.").foregroundStyle(Theme.muted) }
                        else if let availabilityError { Text(availabilityError).foregroundStyle(.orange); Button("Retry Quick Connect availability") { Task { await loadOptions(server) } } }
                        else { Text("Checking Quick Connect…").foregroundStyle(Theme.muted) }
                        Button("Change server") { leaveServer() }.disabled(busy)
                    }
                } else {
                    if !model.snapshot.servers.isEmpty {
                        Text("Remembered servers").font(.headline).accessibilityAddTraits(.isHeader)
                        ForEach(model.snapshot.servers, id: \.url) { saved in Button(saved.name) { address = saved.url.absoluteString; connect() }.buttonStyle(.bordered) }
                    }
                    TextField("Server address", text: $address).textContentType(.URL).autocorrectionDisabled().focused($focused, equals: "server").submitLabel(.go).onSubmit(connect)
                        .accessibilityLabel("Server address").accessibilityHint("Enter your Jellyfin server URL, including its port or path if needed").accessibilityIdentifier("serverAddress")
                        #if os(iOS)
                        .textInputAutocapitalization(.never).keyboardType(.URL)
                        #endif
                    Text("Automatic LAN discovery is unavailable in this unsigned build. Enter your server address on your network, VPN, or private server.").font(.callout).foregroundStyle(Theme.muted)
                    Button("Connect", action: connect).disabled(busy || address.isEmpty).accessibilityIdentifier("connect")
                }
                if !model.snapshot.accounts.isEmpty { Button("Remembered accounts") { work?.cancel(); flow.clear(); model.showSignIn = false }.disabled(busy) }
                if busy || flow.busy { ProgressView(busy ? "Connecting…" : "Getting a Quick Connect code…") }
                if let message = error ?? flow.error { Text(message).foregroundStyle(.orange).accessibilityIdentifier("signInError") }
            }
            #if os(iOS)
            .textFieldStyle(.roundedBorder)
            #endif
            .buttonStyle(.borderedProminent).frame(maxWidth: 720, alignment: .leading).padding(Theme.gutter).frame(maxWidth: .infinity)
        }
        .background(Theme.background.ignoresSafeArea())
        .task {
            server = model.signInServer ?? flow.attempt?.server ?? model.snapshot.servers.last; username = model.signInUsername
            if let server { await loadOptions(server); if flow.attempt != nil { flow.poll(model: model) } }
            #if DEBUG
            let env = ProcessInfo.processInfo.environment
            if let value = env["PICTURESHOW_TEST_SERVER"] { address = value }
            if let value = env["PICTURESHOW_TEST_USERNAME"] { username = value }
            if let value = env["PICTURESHOW_TEST_PASSWORD"] { password = value }
            #endif
        }
        .onDisappear { work?.cancel(); password = "" }
        #if os(tvOS)
        .onExitCommand { if flow.showingQuick { flow.hide() } else if server != nil { leaveServer() } else if !model.snapshot.accounts.isEmpty { model.showSignIn = false } }
        #endif
    }
    @ViewBuilder private func quick(_ server: Server) -> some View {
        Text("Quick Connect").font(.title2).accessibilityAddTraits(.isHeader)
        if let code = flow.attempt?.code {
            Text(code).font(.system(size: 48, weight: .bold, design: .monospaced)).accessibilityLabel("Quick Connect code " + code.map(String.init).joined(separator: " ")).accessibilityIdentifier("quickCode")
            Text("In an already signed-in Jellyfin app, open Quick Connect and enter this code. Waiting for approval…").foregroundStyle(Theme.muted)
        }
        if flow.attempt == nil || flow.error != nil { Button("Renew Quick Connect code") { flow.clear(); flow.begin(server: server, model: model) }.disabled(flow.busy) }
        Button("Use password") { flow.hide(); focused = "username" }.accessibilityIdentifier("cancelQuick")
    }
    private func connect() {
        guard !busy, !address.isEmpty else { return }; busy = true; error = nil
        work = Task {
            defer { busy = false }
            do { let found = try await model.client.discover(address: address); try Task.checkCancellation(); server = found; model.signInServer = found; await loadOptions(found); focused = "username" }
            catch is CancellationError { } catch { self.error = error.localizedDescription }
        }
    }
    private func loadOptions(_ server: Server) async {
        do { users = try await model.client.publicUsers(server: server) } catch { users = [] }
        do { quickEnabled = try await model.client.quickConnectEnabled(server: server); availabilityError = nil } catch { quickEnabled = nil; availabilityError = "Quick Connect availability could not be checked. Password sign-in is still available." }
    }
    private func signIn(_ server: Server) {
        guard !busy, !username.isEmpty else { return }; busy = true; error = nil
        work = Task {
            defer { busy = false }
            do { let account = try await model.client.signIn(server: server, username: username, password: password); try Task.checkCancellation(); try model.signedIn(account); password = "" }
            catch is CancellationError { } catch { self.error = error.localizedDescription }
        }
    }
    private func leaveServer() { work?.cancel(); flow.clear(); server = nil; model.signInServer = nil; password = ""; users = []; quickEnabled = nil; focused = "server" }
}
