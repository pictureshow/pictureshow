// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

/// Step 1: choose or type a server.
struct SignInFlowView: View {
    @EnvironmentObject private var app: AppModel
    var isAddingAccount = false
    @Environment(\.dismiss) private var dismiss
    @State private var address = ""
    @State private var connecting = false
    @State private var error: String?
    @State private var server: Server?
    @FocusState private var addressFocused: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                header
                if !app.accounts.isEmpty && !isAddingAccount { savedAccounts }
                if !app.servers.isEmpty { rememberedServers }
                addressForm
            }
            .frame(maxWidth: 640, alignment: .leading)
            .padding(.horizontal, Theme.gutter)
            .padding(.vertical, 40)
            .frame(maxWidth: .infinity)
        }
        .pageBackground()
        .navigationDestination(item: $server) { server in UserPickerView(server: server) }
        .toolbar {
            if isAddingAccount {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            Wordmark(size: platformValue(tv: 64, touch: 40))
            Text(isAddingAccount ? "Add an account" : "Watch your Jellyfin library.")
                .font(.title2.weight(.semibold))
            Text("Enter the address of your Jellyfin server. It can be on your home network, a VPN, or a domain you run.")
                .font(.body).foregroundStyle(Theme.muted)
        }
    }

    /// Every saved viewer. A signed-in one opens straight away; a signed-out one keeps its card (AUTH-10)
    /// and asks only for the password.
    private var savedAccounts: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "Your accounts")
            ForEach(app.accounts, id: \.key) { account in
                Button { app.select(account) } label: {
                    HStack(spacing: 16) {
                        Image(systemName: "person.crop.circle").font(.title3).foregroundStyle(Theme.accent).frame(width: 36)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(account.username).font(.headline)
                            Text(account.isSignedIn ? account.server.name : "\(account.server.name) · Signed out · Sign in again").font(.caption).foregroundStyle(Theme.muted).lineLimit(2)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(Theme.muted)
                    }
                    .padding(platformValue(tv: 20, touch: 14))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: Theme.corner, style: .continuous).fill(Theme.surface))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .contextMenu {
                    if account.isSignedIn { Button("Sign out", role: .destructive) { app.signOut(account) } }
                    Button("Remove from this device", role: .destructive) { app.remove(account) }
                }
            }
        }
    }

    private var rememberedServers: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "Your servers")
            ForEach(app.servers, id: \.url) { server in
                Button { self.server = server } label: {
                    HStack(spacing: 16) {
                        Image(systemName: "server.rack").font(.title3).foregroundStyle(Theme.accent).frame(width: 36)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(server.name).font(.headline)
                            Text(server.url.absoluteString).font(.caption).foregroundStyle(Theme.muted).lineLimit(1)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(Theme.muted)
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity)
                    #if !os(tvOS)
                    .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.surface))
                    #endif
                    .contentShape(Rectangle())
                }
                #if os(tvOS)
                .buttonStyle(.card)
                #else
                .buttonStyle(.plain)
                #endif
                .contextMenu {
                    Button(role: .destructive) { app.forget(server) } label: { Label("Forget server", systemImage: "trash") }
                }
            }
        }
    }

    private var addressForm: some View {
        VStack(alignment: .leading, spacing: 14) {
            SectionHeader(title: app.servers.isEmpty ? "Server address" : "Another server")
            TextField("jellyfin.example.com or its IP address and port", text: $address)
                .textContentType(.URL)
                #if os(iOS)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Theme.surface))
                #endif
                .autocorrectionDisabled()
                .focused($addressFocused)
                .submitLabel(.go)
                .onSubmit { Task { await connect() } }
                .accessibilityLabel("Server address")
            if let error {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.callout).foregroundStyle(Theme.warning)
                    .accessibilityAddTraits(.isStaticText)
            }
            Button { Task { await connect() } } label: {
                HStack(spacing: 10) {
                    if connecting { ProgressView().tint(Theme.onAccent) }
                    Text(connecting ? "Connecting…" : "Connect")
                }
                .frame(maxWidth: platformValue(tv: 420, touch: .infinity))
            }
            .primaryAction()
            .disabled(address.nilIfBlank == nil || connecting)
        }
    }

    private func connect() async {
        guard let text = address.nilIfBlank, !connecting else { return }
        connecting = true; error = nil
        defer { connecting = false }
        do {
            let found = try await AppModel.makeClient(deviceID: DeviceIdentity.make()).discover(address: text)
            app.remember(found)
            server = found
        } catch {
            self.error = error.localizedDescription
        }
    }
}

extension Server: @retroactive Hashable, @retroactive Identifiable {
    public var id: URL { url }
    public func hash(into hasher: inout Hasher) { hasher.combine(url) }
}

/// Step 2: who's watching on this server.
struct UserPickerView: View {
    @EnvironmentObject private var app: AppModel
    let server: Server
    @State private var users: [PublicUser] = []
    @State private var quickConnect = false
    @State private var loaded = false
    @State private var credentialsUser: String?
    @State private var showCredentials = false
    @State private var deviceID = DeviceIdentity.make()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Who's watching?").font(.largeTitle.weight(.bold))
                    Text(server.name).font(.headline).foregroundStyle(Theme.muted)
                }
                VStack(alignment: .leading, spacing: 14) {
                    if quickConnect {
                        NavigationLink { QuickConnectView(server: server, deviceID: deviceID) } label: {
                            Label("Use Quick Connect", systemImage: "qrcode").frame(maxWidth: platformValue(tv: 520, touch: .infinity))
                        }
                        .primaryAction()
                    }
                    Button { credentialsUser = nil; showCredentials = true } label: {
                        Label("Sign in with username", systemImage: "person.badge.key").frame(maxWidth: platformValue(tv: 520, touch: .infinity))
                    }
                    .secondaryAction()
                }
                if !users.isEmpty {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: platformValue(tv: 220, touch: 104)), spacing: platformValue(tv: 48, touch: 20))], alignment: .leading, spacing: 28) {
                        ForEach(users) { user in
                            Button { credentialsUser = user.name; showCredentials = true } label: {
                                UserAvatar(server: server, user: user)
                            }
                            #if os(tvOS)
                            .buttonStyle(.borderless)
                            #else
                            .buttonStyle(.plain)
                            #endif
                        }
                    }
                }
                if loaded && users.isEmpty {
                    Text("This server doesn't list its users. Sign in with your username, or approve this device with Quick Connect from another app where you're signed in.")
                        .font(.callout).foregroundStyle(Theme.muted)
                }
            }
            .frame(maxWidth: platformValue(tv: 1500, touch: 900), alignment: .leading)
            .padding(.horizontal, Theme.gutter).padding(.vertical, 32)
            .frame(maxWidth: .infinity)
        }
        .pageBackground()
        .navigationDestination(isPresented: $showCredentials) {
            PasswordView(server: server, username: credentialsUser ?? "", deviceID: deviceID)
        }
        .task {
            let client = AppModel.makeClient(deviceID: deviceID)
            async let users = try? client.publicUsers(server: server)
            async let qc = try? client.quickConnectEnabled(server: server)
            self.users = await users ?? []
            self.quickConnect = await qc ?? false
            loaded = true
        }
    }
}

struct UserAvatar: View {
    let server: Server
    let user: PublicUser
    var body: some View {
        VStack(spacing: 12) {
            ZStack {
                Circle().fill(Theme.surface)
                Text(String(user.name.prefix(1)).uppercased())
                    .font(.system(size: platformValue(tv: 72, touch: 36), weight: .semibold, design: .rounded))
                    .foregroundStyle(Theme.accent)
            }
            .frame(width: platformValue(tv: 180, touch: 84), height: platformValue(tv: 180, touch: 84))
            #if os(tvOS)
            .contentShape(.hoverEffect, Circle())
            .hoverEffect(.highlight)
            #endif
            Text(user.name).font(.callout.weight(.medium)).lineLimit(1)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Sign in as \(user.name)")
    }
}

/// Step 3a: username and password.
struct PasswordView: View {
    @EnvironmentObject private var app: AppModel
    let server: Server
    @State var username: String
    let deviceID: String
    /// The name picked from the user list; nil when the viewer types one, so the field stays editable.
    private let pickedName: String?
    @State private var password = ""
    @State private var working = false
    @State private var error: String?
    @FocusState private var field: Field?
    enum Field { case username, password }

    init(server: Server, username: String, deviceID: String) {
        self.server = server
        self._username = State(initialValue: username)
        self.deviceID = deviceID
        self.pickedName = username.isEmpty ? nil : username
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text(pickedName.map { "Sign in as \($0)" } ?? "Sign in").font(.largeTitle.weight(.bold))
                Text(server.name).font(.headline).foregroundStyle(Theme.muted)
                if pickedName == nil {
                    TextField("Username", text: $username)
                        .accessibilityLabel("Username")
                        .textContentType(.username)
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        .padding(14).background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Theme.surface))
                        #endif
                        .focused($field, equals: .username)
                        .submitLabel(.next)
                        .onSubmit { field = .password }
                }
                SecureField("Password", text: $password)
                    .accessibilityLabel("Password")
                    .textContentType(.password)
                    #if os(iOS)
                    .padding(14).background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Theme.surface))
                    #endif
                    .focused($field, equals: .password)
                    .submitLabel(.go)
                    .onSubmit { Task { await signIn() } }
                if let error {
                    Label(error, systemImage: "exclamationmark.triangle.fill").font(.callout).foregroundStyle(Theme.warning)
                }
                Button { Task { await signIn() } } label: {
                    HStack(spacing: 10) {
                        if working { ProgressView().tint(Theme.onAccent) }
                        Text(working ? "Signing in…" : "Sign in")
                    }
                    .frame(maxWidth: platformValue(tv: 420, touch: .infinity))
                }
                .primaryAction()
                .disabled(username.nilIfBlank == nil || working)
            }
            .frame(maxWidth: 640, alignment: .leading)
            .padding(.horizontal, Theme.gutter).padding(.vertical, 32)
            .frame(maxWidth: .infinity)
        }
        .pageBackground()
        .onAppear { field = username.isEmpty ? .username : .password }
    }

    private func signIn() async {
        guard let name = username.nilIfBlank, !working else { return }
        working = true; error = nil
        defer { working = false }
        do {
            let account = try await AppModel.makeClient(deviceID: deviceID).signIn(server: server, username: name, password: password)
            app.signedIn(account)
        } catch ClientError.invalidCredentials {
            error = "That username and password didn't work. Check them and try again."
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// Step 3b: Quick Connect. The code is approved from another signed-in Jellyfin app.
struct QuickConnectView: View {
    @EnvironmentObject private var app: AppModel
    let server: Server
    let deviceID: String
    @State private var code: String?
    @State private var error: String?

    var body: some View {
        VStack(spacing: 28) {
            Text("Quick Connect").font(.largeTitle.weight(.bold))
            if let code {
                Text(code.map(String.init).joined(separator: " "))
                    .font(.system(size: platformValue(tv: 120, touch: 56), weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(Theme.accent)
                    .accessibilityLabel("Code \(code.map(String.init).joined(separator: " "))")
                    #if os(iOS)
                    .textSelection(.enabled)
                    #endif
                Text("On a device where you're already signed in to \(server.name), open Settings, choose Quick Connect and enter this code.")
                    .font(.body).foregroundStyle(Theme.muted).multilineTextAlignment(.center).frame(maxWidth: 640)
                ProgressView().padding(.top, 8)
                Text("Waiting for approval…").font(.callout).foregroundStyle(Theme.muted)
            } else if let error {
                MessageView(symbol: "exclamationmark.triangle", title: "Quick Connect isn't available", message: error)
            } else {
                ProgressView()
            }
        }
        .padding(Theme.gutter)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .pageBackground()
        .task { await run() }
    }

    private func run() async {
        let client = AppModel.makeClient(deviceID: deviceID)
        do {
            let state = try await client.initiateQuickConnect(server: server)
            code = state.code
            while !Task.isCancelled {
                try await Task.sleep(for: .seconds(3))
                let status = try await client.quickConnectStatus(server: server, secret: state.secret)
                if status.authenticated {
                    let account = try await client.authenticateQuickConnect(server: server, secret: state.secret)
                    app.signedIn(account)
                    return
                }
            }
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// Picks a value per platform without scattering `#if` through layout code.
func platformValue<T>(tv: T, touch: T) -> T {
    #if os(tvOS)
    return tv
    #else
    return touch
    #endif
}
