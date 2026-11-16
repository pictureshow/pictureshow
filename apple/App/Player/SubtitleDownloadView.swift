// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import PictureshowCore
import SwiftUI

/// SUB-DL-01..04: search the server's subtitle providers for this title and download one. From the player, the new
/// track is switched on at the current position (`onAdopted`); from a title page the panel just confirms.
struct SubtitleDownloadView: View {
    let client: JellyfinClient
    let account: Account
    let target: RemoteSubtitleTarget
    var onAdopted: ((AdoptedSubtitle, [MediaStream]) -> Void)?
    @Environment(\.dismiss) private var dismiss
    @State private var search: RemoteSubtitleSearch?

    var body: some View {
        NavigationStack {
            Group {
                if let search { content(search) } else { Color.clear }
            }
            #if os(tvOS)
            // tvOS draws a navigation title in its dimmed grey, and the sheet's translucent glass let bright video
            // show through behind it, so the title was barely readable. The TV panel draws its own header and an
            // opaque page-colour background instead.
            .safeAreaInset(edge: .top, spacing: 24) { tvHeader }
            .toolbar(.hidden, for: .navigationBar)
            #else
            .navigationTitle("Download subtitles")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
            #endif
        }
        #if os(tvOS)
        // A TV sheet sizes to its content; give the list and the long messages room to read from the couch.
        .frame(width: 1240, height: 880)
        .presentationBackground(Theme.background)
        #endif
        .task {
            guard search == nil else { return }
            let started = RemoteSubtitleSearch(client: client, account: account, deviceLanguage: Locale.current.language.languageCode?.identifier)
            search = started
            started.open(target)
        }
        .onDisappear { search?.close() }
    }

    #if os(tvOS)
    private var tvHeader: some View {
        HStack {
            Text("Download subtitles").font(.title2.weight(.bold)).foregroundStyle(.primary)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            Button("Close") { dismiss() }.secondaryAction()
        }
        .padding(.horizontal, 40).padding(.top, 40)
        .focusSection()
    }
    #endif

    @ViewBuilder private func content(_ search: RemoteSubtitleSearch) -> some View {
        switch search.phase {
        case .checking: progress("Checking what your server can do…")
        case .blocked: message(blockedText(search.access), retry: search.access == .unavailable ? nil : { search.retry() })
        case .added: message(onAdopted == nil ? "Added. Choose it from Subtitles when you play this title." : "Added. Subtitles switched to \(search.languageName).", retry: nil)
        case .notListed: message("The server downloaded the file but hasn't listed it yet. It will appear under Subtitles once the server finishes scanning the title.", retry: nil)
        case .searching, .results, .downloading: list(search)
        }
    }

    private func list(_ search: RemoteSubtitleSearch) -> some View {
        List {
            Section {
                Menu {
                    ForEach(search.languages, id: \.self) { culture in
                        Button(culture.displayName) { search.choose(language: culture.threeLetter ?? "") }
                    }
                } label: {
                    LabeledContent("Language: \(search.languageName)") { Text("Choose a language").foregroundStyle(.secondary) }
                }
                .disabled(search.phase == .downloading || search.languages.isEmpty)
                .accessibilityLabel("Language: \(search.languageName). Choose a language")
            }
            Section {
                switch search.phase {
                case .searching:
                    HStack(spacing: 12) { ProgressView(); Text("Searching for \(search.languageName) subtitles…") }
                case .downloading:
                    HStack(spacing: 12) { ProgressView(); Text("Downloading \(search.chosen.map(RemoteSubtitlePolicy.title) ?? "")…") }
                default:
                    if search.empty != .none {
                        Text(emptyText(search.empty, language: search.languageName)).foregroundStyle(.secondary)
                        if search.empty != .noMatches { Button("Try again") { search.retry() } }
                    }
                }
                ForEach(search.results) { result in
                    Button { search.download(result) { adopted, streams in onAdopted?(adopted, streams) } } label: { row(result) }
                        .disabled(search.phase == .downloading)
                }
            }
        }
    }

    private func row(_ result: RemoteSubtitle) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(RemoteSubtitlePolicy.title(result)).lineLimit(2)
            let details = RemoteSubtitlePolicy.details(result).map(detailText).joined(separator: " · ")
            if !details.isEmpty { Text(details).font(.footnote).foregroundStyle(.secondary) }
        }
        .padding(.vertical, 2)
    }

    private func detailText(_ token: String) -> String {
        switch token {
        case "hash-match": return "Made for this video"
        case "hearing-impaired": return "Hearing impaired"
        case "forced": return "Forced"
        case "machine-translated": return "Machine translated"
        default:
            let parts = token.split(separator: ":", maxSplits: 1).map(String.init)
            guard parts.count == 2 else { return token }
            if parts[0] == "downloads", let n = Int(parts[1]) { return n == 1 ? "1 download" : "\(n.formatted()) downloads" }
            return parts[1]
        }
    }

    private func blankText(_ text: String) -> some View { Text(text).multilineTextAlignment(.center) }

    private func progress(_ text: String) -> some View {
        VStack(spacing: 16) { ProgressView(); blankText(text) }.frame(maxWidth: .infinity, maxHeight: .infinity).padding()
    }

    private func message(_ text: String, retry: (() -> Void)?) -> some View {
        VStack(spacing: 20) {
            blankText(text).frame(maxWidth: 640)
            HStack(spacing: 16) {
                if let retry { Button("Try again", action: retry).tvSecondaryAction() }
                Button("Close") { dismiss() }.tvSecondaryAction()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity).padding()
    }

    private func blockedText(_ access: RemoteSubtitleAccess) -> String {
        switch access {
        case .notAllowed: return emptyText(.notAllowed, language: "")
        case .noProvider: return emptyText(.noProvider, language: "")
        case .unavailable, .allowed: return "Subtitles can only be downloaded for titles played from your server."
        }
    }

    private func emptyText(_ empty: RemoteSubtitleEmpty, language: String) -> String {
        switch empty {
        case .none: return ""
        case .noMatches: return "No \(language) subtitles were found for this title. Try another language."
        case .notAllowed: return "Your account isn't allowed to download subtitles. A server admin can turn on \u{201C}Allow subtitle management\u{201D} for you in Jellyfin's Dashboard, Users."
        case .noProvider: return "Your server has no subtitle provider, so it can't search for subtitles. A server admin can install one, such as the OpenSubtitles plugin, from Dashboard, Plugins, Catalog in Jellyfin."
        case .failed: return "The server couldn't finish that. Try again, or pick another result."
        case .unreachable: return "Couldn't reach your server. Check your connection and try again."
        }
    }
}

private extension View {
    /// tvOS's system bordered button fills and letters itself in the app tint, so an unfocused one showed no words.
    @ViewBuilder func tvSecondaryAction() -> some View {
        #if os(tvOS)
        secondaryAction()
        #else
        self
        #endif
    }
}

/// Item types whose title page offers "Find subtitles" (SUB-DL-01).
enum SubtitleDownloadOffer {
    static func offered(_ item: MediaItem) -> Bool {
        ["Movie", "Episode", "Video", "MusicVideo"].contains(item.type) && !item.isMissing && !item.mediaSources.isEmpty
    }
}
