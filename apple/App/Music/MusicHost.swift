// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import PictureshowCore

/// What a screen asks to play: its tracks in order, which one first, and the list it came from.
struct MusicPlay {
    var owner: String
    var tracks: [MusicCard]
    var start: Int?
    var context: String
    var contextName: String?
    var shuffle = false
}

struct MusicPrompt: Identifiable {
    let id = UUID()
    var play: MusicPlay
}

/// The music queue and its output for the selected viewer (MUS-03..08, QUE-01..08).
///
/// Every edit takes one road: it is reduced by the shared queue reducer, saved for this viewer, published, and only then
/// applied to the output, which holds the reducer's window of the queue (MusicQueue.window/plan). Reports go on their own
/// ordered line through the shared report reducer, so one track is one viewing however the output got there.
@MainActor final class MusicHost: ObservableObject {
    static let progressSeconds = 10
    static let positionSeconds = 5

    // What the screens read.
    @Published private(set) var account: Account?
    @Published private(set) var committed: [String: Any] = [:]
    @Published private(set) var cards: [String: MusicCard] = [:]
    @Published private(set) var contextName: String?
    @Published private(set) var isPlaying = false
    @Published private(set) var buffering = false
    @Published private(set) var outputActive = false
    @Published private(set) var seconds: Double = 0
    @Published private(set) var output: MusicOutput?
    @Published var message: String?
    @Published private(set) var canRetrySave = false
    @Published var prompt: MusicPrompt?
    @Published var showingNowPlaying = false

    // Collaborators.
    private let gateway: MusicGateway
    private let store: MusicQueueStore
    private let makeEngine: @MainActor () -> MusicEngine
    /// AUTH-18: whether this viewer may start sound now (the current viewer, past the PIN gate).
    var admitted: (Account) -> Bool = { _ in true }
    /// A 401 on a stream marks the viewer expired, as for video.
    var expire: (Account) -> Void = { _ in }
    var quality: () -> String = { MusicSettings.quality }
    var metered: () -> Bool = { false }
    var route: () -> String = { MusicAudioSession.route() }
    /// For artwork on the lock screen.
    var client: ((Account) -> JellyfinClient)?
    let nowPlaying = MusicNowPlaying()

    // The queue.
    private var queueStore: [String: Any] = [:]
    private var sequence: Int64 = 0
    private var playContext: String?
    private var session: [String: Any] = [:]
    private var binding = 0
    private var tail: Task<Void, Never>?
    private var restoring: Task<Void, Never>?

    // The output.
    private(set) var engine: MusicEngine?
    private var prepared: [String: (decision: PlayDecision, media: String)] = [:]
    private var preparing: Task<Void, Never>?
    private var ceiling = 0
    private var ticker: Task<Void, Never>?
    private var lastTicks: Int64 = 0

    // Reports.
    private var reports: [String: Any] = [:]
    private var viewings: [String: (decision: PlayDecision, media: String)] = [:]
    private var reportTail: Task<Void, Never>?

    init(gateway: MusicGateway, store: MusicQueueStore, makeEngine: @escaping @MainActor () -> MusicEngine = { @MainActor in QueuePlayerEngine() }) {
        self.gateway = gateway
        self.store = store
        self.makeEngine = makeEngine
        nowPlaying.host = self
        MusicAudioSession.host = self
    }

    // MARK: Reading the queue

    var order: [String] { committed["order"] as? [String] ?? [] }
    var current: String? { committed["current"] as? String }
    var paused: Bool { committed["paused"] as? Bool ?? true }
    var repeatMode: String { committed["repeat"] as? String ?? "off" }
    var shuffled: Bool { committed["shuffled"] as? Bool ?? false }
    var positionTicks: Int64 { Int64(committed["positionTicks"] as? String ?? "") ?? 0 }
    private var entryMedia: [String: String] {
        Dictionary((committed["entries"] as? [[String: Any]] ?? []).compactMap { e in (e["id"] as? String).map { ($0, e["media"] as? String ?? "") } }, uniquingKeysWith: { a, _ in a })
    }
    func card(entry: String?) -> MusicCard? { entry.flatMap { entryMedia[$0] }.flatMap { cards[$0] } }
    var currentCard: MusicCard? { card(entry: current) }
    var upNext: [String] { MusicQueue.upcoming(order: order, current: current) }
    var hasQueue: Bool { current != nil }
    var durationSeconds: Double? {
        if let d = engine?.duration { return d }
        return currentCard?.durationTicks.map { Double($0) / 10_000_000 }.flatMap { $0 > 0 ? $0 : nil }
    }
    var positionSeconds: Double { engine != nil ? seconds : Double(positionTicks) / 10_000_000 }
    var hasNext: Bool { !upNext.isEmpty || repeatMode != "off" }

    // MARK: The road

    private func enqueue(_ job: @escaping @MainActor () async -> Void) {
        let asked = binding, previous = tail
        tail = Task { @MainActor [weak self] in
            await previous?.value
            guard let self, asked == self.binding else { return }
            await job()
        }
    }

    /// A step asked for one viewer runs for that viewer or not at all (MUS-08 account isolation).
    private func enqueueFor(_ owner: String, _ job: @escaping @MainActor () async -> Void) {
        let previous = tail
        tail = Task { @MainActor [weak self] in
            await previous?.value
            guard let self, owner == self.account?.key else { return }
            await job()
        }
    }

    /// Waits until every queued step, preparation and report has run. For tests and the debug seam.
    func settle() async {
        for _ in 0..<50 {
            let t = tail, p = preparing, r = reportTail, s = restoring
            await t?.value; await p?.value; await r?.value; await s?.value
            if t == tail && p == preparing && r == reportTail && s == restoring { return }
        }
    }

    // MARK: Accounts

    /// The selected viewer changed (or signed out, or got a new token). `known` are every viewer still saved here.
    func bind(_ next: Account?, known: Set<String>) {
        let previous = account
        if previous?.key == next?.key {
            account = next
            if let previous, let next, previous.accessToken != next.accessToken { enqueue { await self.renewed() } }
            return
        }
        if previous != nil {
            if session["intent"] as? String ?? "stopped" != "stopped" { applySession("sign-out") }
            teardown()
            if let previous, !known.contains(previous.key) { store.delete(previous.key) }
        }
        store.retain(known)
        account = next
        binding += 1
        restoring?.cancel()
        reports = [:]; viewings = [:]
        session = [:]
        playContext = nil; contextName = nil
        sequence = 0
        cards = [:]
        message = nil; prompt = nil; canRetrySave = false; showingNowPlaying = false
        queueStore = ["committed": ["server": next?.server.serverId ?? "", "viewer": next?.userId ?? ""]]
        committed = (try? ControlReducers.reduce(suite: "queue-edit", operation: "store", state: queueStore, event: ["op": "retry-persist"]))?["committed"] as? [String: Any] ?? [:]
        guard let next else { return }
        let bound = binding, empty = committed
        // The server check runs off the road, so Play and Stop never wait on it.
        restoring = Task { [weak self] in await self?.restore(next, bound: bound, empty: empty) }
    }

    private func restore(_ account: Account, bound: Int, empty: [String: Any]) async {
        guard let saved = store.load(account.key), saved.queue["server"] as? String == account.server.serverId,
              saved.queue["viewer"] as? String == account.userId else { return }
        let media = Array(NSOrderedSet(array: (saved.queue["entries"] as? [[String: Any]] ?? []).compactMap { $0["media"] as? String })) as? [String] ?? []
        var accessible = Set(media)
        do { accessible = try await gateway.accessible(account, ids: media) }
        catch { accessible = Set(media) } // One failed request keeps every saved track (QUE-07).
        guard !Task.isCancelled else { return }
        enqueue { [weak self] in
            guard let self, self.binding == bound, self.account?.key == account.key, NSDictionary(dictionary: self.committed).isEqual(to: empty) else { return }
            await self.applyRestore(account, saved: saved, accessible: accessible)
        }
    }

    private func applyRestore(_ account: Account, saved: MusicSnapshot, accessible: Set<String>) async {
        let ids = (saved.queue["entries"] as? [[String: Any]] ?? []).filter { accessible.contains($0["media"] as? String ?? "") }.compactMap { $0["id"] as? String }
        guard let restored = try? ControlReducers.reduce(suite: "queue-edit", operation: "store", state: queueStore, event: ["op": "restore", "saved": saved.queue, "accessible": ids]),
              restored["effect"] as? String == "persist" else { return }
        queueStore = restored
        cards = Dictionary(saved.cards.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        playContext = saved.context
        contextName = saved.contextName
        sequence = saved.sequence
        let removed = (restored["removed"] as? [String] ?? []).count
        _ = await commitPending(account)
        if removed > 0 { message = "\(removed) saved \(removed == 1 ? "track is" : "tracks are") no longer available." }
    }

    private func renewed() async {
        preparing?.cancel()
        prepared = [:]
        guard let engine else { return }
        let at = ticks(engine)
        engine.replace([], at: 0)
        if current != nil { await commit(["op": "position", "ticks": String(at)]) }
        sync()
    }

    // MARK: What screens and the OS call

    func request(_ play: MusicPlay) {
        enqueueFor(play.owner) {
            guard self.admittedNow() else { return }
            switch MusicQueue.playDecision(context: play.context, queueEntries: self.order.count, queueContext: self.playContext) {
            case "play": await self.start(play)
            case "jump": await self.jumpOrStart(play)
            default: self.prompt = MusicPrompt(play: play)
            }
        }
    }

    /// "Replace queue" in the confirmation.
    func confirm() {
        guard let play = prompt?.play else { return }
        prompt = nil
        enqueueFor(play.owner) { await self.start(play) }
    }

    func dismissPrompt() { prompt = nil }

    private func jumpOrStart(_ play: MusicPlay) async {
        let media = play.start.flatMap { play.tracks.indices.contains($0) ? play.tracks[$0].id : nil }
        let entry = (committed["entries"] as? [[String: Any]] ?? []).first { $0["media"] as? String == media }?["id"] as? String
        guard !play.shuffle, let entry else { return await start(play) }
        activate()
        applySession("viewer-play")
        await commit(["op": "jump", "entry": entry])
        if paused { await commit(["op": "play"]) }
    }

    private func start(_ play: MusicPlay) async {
        guard play.owner == account?.key, admittedNow() else { return }
        let limited = Array(play.tracks.prefix(MusicCatalog.queueLimit))
        guard !limited.isEmpty, let entries = try? MusicQueue.entries(limited.map(\.id), firstSequence: sequence) else { return }
        sequence += Int64(entries.count)
        for card in limited { cards[card.id] = card }
        playContext = play.context
        contextName = play.contextName
        let ids = entries.compactMap { $0["id"] }
        let selected = play.start.flatMap { ids.indices.contains($0) ? ids[$0] : nil }
        var event: [String: Any] = ["op": "play-now", "entries": entries]
        if let selected { event["entry"] = selected }
        if play.shuffle {
            let shuffled = MusicQueue.permutation(ids, seed: Self.seed())
            // A chosen first track stays first, so Shuffle from a row plays that row.
            event["permutation"] = selected.map { s in [s] + shuffled.filter { $0 != s } } ?? shuffled
        }
        activate()
        applySession("viewer-play")
        await commit(event)
        if limited.count < play.tracks.count { message = "Playing the first \(MusicCatalog.queueLimit) tracks." }
    }

    func playNext(owner: String, _ tracks: [MusicCard]) { add(owner, tracks, op: "play-next") }
    func append(owner: String, _ tracks: [MusicCard]) { add(owner, tracks, op: "append") }

    private func add(_ owner: String, _ tracks: [MusicCard], op: String) {
        enqueueFor(owner) {
            let room = max(0, MusicCatalog.queueLimit - self.order.count)
            let limited = Array(tracks.prefix(room))
            guard !limited.isEmpty else { if !tracks.isEmpty { self.message = "The queue is full." }; return }
            guard let entries = try? MusicQueue.entries(limited.map(\.id), firstSequence: self.sequence) else { return }
            self.sequence += Int64(entries.count)
            for card in limited { self.cards[card.id] = card }
            // The first thing added to an empty queue is ready to play, and adding never starts it by itself.
            let added: Bool
            if self.current == nil {
                added = await self.commit(["op": "play-now", "entries": entries]) ? await self.commit(["op": "pause"]) : false
            } else {
                added = await self.commit(["op": op, "entries": entries])
            }
            guard added else { return }
            self.message = limited.count == 1 ? "Added \(limited[0].title) to the queue." : "Added \(limited.count) tracks to the queue."
        }
    }

    func remove(_ entry: String) {
        enqueue {
            await self.commit(["op": "remove", "entry": entry])
            if self.current == nil { await self.endOutput("stop-clear") }
        }
    }

    func move(_ entry: String, to index: Int) { enqueue { await self.commit(["op": "move", "entry": entry, "index": max(0, index)]) } }

    func clearUpcoming() { enqueue { await self.commit(["op": "clear-upcoming"]) } }

    func clear() {
        enqueue {
            self.applySession("queue-clear")
            await self.commit(["op": "clear-all"])
            self.teardown()
            self.playContext = nil
            self.contextName = nil
            self.showingNowPlaying = false
        }
    }

    func jump(_ entry: String) {
        enqueue {
            guard self.admittedNow() else { return }
            self.activate()
            await self.commit(["op": "jump", "entry": entry])
        }
    }

    func play() {
        guard admittedNow() else { return }
        enqueue {
            // The lock screen, a headset or Control Center can't start music behind the profile lock (AUTH-18).
            guard self.admittedNow(), self.current != nil else { return }
            self.applySession("viewer-play")
            self.activate()
            await self.commit(["op": "play"])
            self.engine?.setPlaying(true)
        }
    }

    func pause() {
        enqueue {
            self.applySession("viewer-pause")
            self.savePosition()
            await self.commit(["op": "pause"])
        }
    }

    func toggle() { if isPlaying || (outputActive && !paused) { pause() } else { play() } }

    func next() {
        enqueue {
            if !self.paused { self.activate() }
            await self.commit(["op": "next", "completion": self.committed["completion"] ?? "0"])
        }
    }

    func previous() {
        enqueue {
            if let engine = self.engine { await self.commit(["op": "position", "ticks": String(self.ticks(engine))]) }
            let before = self.current
            await self.commit(["op": "previous"])
            if self.current == before { self.engine?.seek(to: 0); self.seconds = 0 }
        }
    }

    func seek(to seconds: Double) {
        enqueue {
            let target = max(0, seconds)
            if let engine = self.engine { engine.seek(to: target); self.seconds = target }
            else { await self.commit(["op": "position", "ticks": String(Int64(target * 10_000_000))]) }
        }
    }

    func skip(by delta: Double) { seek(to: positionSeconds + delta) }

    func cycleRepeat() {
        enqueue {
            let next = ["off": "all", "all": "one"][self.repeatMode] ?? "off"
            await self.commit(["op": "repeat", "repeat": next])
        }
    }

    func setShuffle(_ on: Bool) {
        enqueue {
            guard on != self.shuffled else { return }
            if on {
                let visited = Set(self.committed["visited"] as? [String] ?? [])
                let upcoming = self.upNext.filter { !visited.contains($0) }
                await self.commit(["op": "shuffle", "permutation": MusicQueue.permutation(upcoming, seed: Self.seed())])
            } else {
                await self.commit(["op": "unshuffle"])
            }
        }
    }

    func retrySave() {
        enqueue {
            guard let active = self.account,
                  let retried = try? ControlReducers.reduce(suite: "queue-edit", operation: "store", state: self.queueStore, event: ["op": "retry-persist"]),
                  retried["effect"] as? String == "persist" else { return }
            self.queueStore = retried
            _ = await self.commitPending(active)
        }
    }

    /// Stop ends the sound and the lock-screen controls; the queue stays, ready to play.
    func stop() { enqueue { await self.endOutput("stop") } }

    /// AUTH-18: the profile locked again. The sound and its controls end; the place is saved and the queue kept.
    func lockOut() { enqueue { if self.engine != nil { await self.endOutput("stop") } } }

    /// A video is opening (MUS-11): music stops once, before it, and keeps its queue for a deliberate return.
    func beforeVideo() {
        guard engine != nil else { return }
        let asked = binding
        enqueue { if asked == self.binding { await self.endOutput("video-start") } }
    }

    private func admittedNow() -> Bool { account.map(admitted) ?? false }

    // MARK: Commit

    @discardableResult private func commit(_ partial: [String: Any]) async -> Bool {
        guard let active = account else { return false }
        var edit = partial
        edit["revision"] = committed["revision"] ?? "0"
        edit["generation"] = committed["generation"] ?? "0"
        let outcome = (try? ControlReducers.reduce(suite: "queue-edit", operation: "queue", state: committed, event: edit))?["outcome"] as? String
        guard let next = try? ControlReducers.reduce(suite: "queue-edit", operation: "store", state: queueStore, event: ["op": "edit", "edit": edit]),
              next["effect"] as? String == "persist" else { return false }
        queueStore = next
        guard await commitPending(active) else { return false }
        switch outcome {
        // The queue ran out: Play starts its last track again rather than landing on its final second.
        case "stopped": await endOutput("stop", ticks: 0)
        case "exhausted":
            await endOutput("stop")
            message = "None of the tracks left in this queue could play."
        case "skipped-unavailable": message = "Skipped a track that couldn't play."
        default: break
        }
        return true
    }

    private func commitPending(_ active: Account) async -> Bool {
        guard let pending = queueStore["pending"] as? [String: Any] else { return false }
        let saved = store.save(active.key, snapshot(pending))
        guard let acknowledged = try? ControlReducers.reduce(suite: "queue-edit", operation: "store", state: queueStore,
                                                             event: ["op": saved ? "persisted" : "persist-failed", "ticket": queueStore["ticket"] ?? "0"]) else { return false }
        queueStore = acknowledged
        guard saved else {
            message = "Couldn't save the queue on this device, so that change didn't apply."
            canRetrySave = true
            return false
        }
        canRetrySave = false
        committed = acknowledged["committed"] as? [String: Any] ?? committed
        sync()
        return true
    }

    private func snapshot(_ queue: [String: Any]) -> MusicSnapshot {
        let media = Set((queue["entries"] as? [[String: Any]] ?? []).compactMap { $0["media"] as? String })
        return MusicSnapshot(queue: queue, context: playContext, contextName: contextName, sequence: sequence,
                             cards: media.compactMap { cards[$0] }.sorted { $0.id < $1.id }, position: nil)
    }

    private static func seed() -> Int64 { Int64.random(in: 1..<Int64(Int32.max)) }

    // MARK: Output

    /// The output exists from the first Play until Stop.
    private func activate() {
        guard engine == nil else { return }
        let created = makeEngine()
        created.events = self
        engine = created
        outputActive = true
        MusicAudioSession.activate()
        nowPlaying.attach()
        ticker = Task { [weak self] in await self?.tick() }
        publishOutput()
    }

    private func sync() {
        preparing?.cancel()
        guard let engine else { publishOutput(); return }
        engine.repeatsOne = MusicQueue.outputRepeats(order: order, current: current, repeatMode: repeatMode)
        engine.setPlaying(!paused)
        preparing = Task { [weak self] in await self?.prepareWindow() }
        publishOutput()
    }

    private func prepareWindow() async {
        guard let active = account else { return }
        let failed = Set(committed["failed"] as? [String] ?? [])
        let target = MusicQueue.window(order: order, current: current, repeatMode: repeatMode, failed: failed)
        guard !target.isEmpty else { return }
        let wanted = MusicQuality.ceiling(quality: quality(), metered: metered())
        if wanted != ceiling {
            // A quality change applies from what hasn't been opened yet.
            let playing = engine?.playing
            prepared = prepared.filter { $0.key == playing }
            ceiling = wanted
        }
        let media = entryMedia
        prepared = prepared.filter { target.contains($0.key) }
        for (index, entry) in target.enumerated() {
            if prepared[entry] == nil {
                guard let id = media[entry] else { return }
                do {
                    let decision = try await gateway.prepare(active, media: id, ceiling: ceiling)
                    guard !Task.isCancelled else { return }
                    prepared[entry] = (decision, id)
                } catch {
                    guard !Task.isCancelled else { return }
                    if index > 0 { return applyWindow(target) }
                    return negotiationFailed(error)
                }
            }
            applyWindow(target)
        }
    }

    private func negotiationFailed(_ error: Error) {
        switch error as? ClientError {
        case .invalidCredentials: enqueue { await self.revoked() }
        case .server(let status) where status == 403 || status == 404: enqueue { await self.failCurrent() }
        case .noCompatibleSource, .serverRefused, .sourceNotFound, .unsafeStreamURL, .invalidResponse: enqueue { await self.failCurrent() }
        default:
            enqueue {
                self.engine?.setPlaying(false)
                await self.commit(["op": "pause"])
                self.message = "Can't reach the server. Your queue is saved: press Play to try again."
            }
        }
    }

    private func failCurrent() async {
        await commit(["op": "error", "completion": committed["completion"] ?? "0", "music": true])
    }

    private func revoked() async {
        let active = account
        await endOutput("revoked")
        if let active { expire(active) }
        message = "This account needs to sign in again. Your queue is saved for when it does."
    }

    private func applyWindow(_ target: [String]) {
        guard let engine else { return }
        let ready = Array(target.prefix { prepared[$0] != nil })
        guard !ready.isEmpty else { return }
        let plan = MusicQueue.plan(held: engine.held, playing: engine.playing, target: ready)
        let items = { (ids: [String]) in ids.compactMap { id in self.prepared[id].map { MusicEngineItem(entry: id, url: $0.decision.url) } } }
        if plan.replace {
            engine.replace(items(plan.add), at: Double(positionTicks) / 10_000_000)
            seconds = Double(positionTicks) / 10_000_000
        } else {
            if plan.removeAfter > 0 { engine.removeLast(plan.removeAfter) }
            if plan.removeBefore > 0 { engine.removeFirst(plan.removeBefore) }
            if !plan.add.isEmpty { engine.append(items(plan.add)) }
        }
        engine.setPlaying(!paused)
        updateOutputLabel()
    }

    private func updateOutputLabel() {
        guard let entry = engine?.playing, let track = prepared[entry] else { return }
        let source = track.decision.source
        let codec = source.mediaStreams.first { $0.type == "Audio" }?.codec
        output = MusicQuality.output(codec: codec, container: source.container, method: track.decision.method.rawValue, route: route(),
                                     ceiling: ceiling, reasons: track.decision.transcodeReasons)
    }

    private func publishOutput() {
        isPlaying = engine?.isPlaying ?? false
        buffering = engine?.buffering ?? false
        outputActive = engine != nil
        nowPlaying.refresh(force: true)
    }

    private func ticks(_ engine: MusicEngine) -> Int64 { Int64((engine.seconds * 10_000_000).rounded()) }

    private func tick() async {
        var sinceReport = 0, sincePosition = 0
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled, let engine else { return }
            seconds = engine.seconds
            guard engine.isPlaying else { continue }
            lastTicks = ticks(engine)
            sinceReport += 1; sincePosition += 1
            if sinceReport >= Self.progressSeconds { sinceReport = 0; report(["op": "progress", "ticks": String(lastTicks)]) }
            if sincePosition >= Self.positionSeconds { sincePosition = 0; savePosition() }
            nowPlaying.refresh()
        }
    }

    private func savePosition() {
        guard let active = account, let engine else { return }
        store.savePosition(active.key, entry: engine.playing, ticks: ticks(engine))
    }

    private func report(_ event: [String: Any]) {
        guard let active = account, let next = try? MusicReducers.report(state: reports, event: event) else { return }
        reports = next
        reports["effects"] = nil
        for effect in next["effects"] as? [[String: Any]] ?? [] { send(active, effect) }
    }

    private func send(_ active: Account, _ effect: [String: Any]) {
        let serial = effect["serial"] as? String ?? ""
        let kind = effect["report"] as? String ?? ""
        let viewing = viewings[serial]
        if kind == "stopped" { viewings[serial] = nil }
        guard let decision = viewing?.decision else { return }
        let ticks = Int64(effect["ticks"] as? String ?? "") ?? 0, paused = effect["paused"] as? Bool ?? false, failed = effect["failed"] as? Bool ?? false
        let previous = reportTail, gateway = gateway
        // Reports leave in the order they happened, on a line of their own, so a slow server can't hold the player.
        reportTail = Task { @MainActor in
            await previous?.value
            await gateway.report(active, kind: kind == "start" ? "start" : kind == "progress" ? "progress" : "stopped", decision: decision, ticks: ticks, paused: paused, failed: failed)
        }
    }

    private func applySession(_ op: String) {
        if let next = try? MusicReducers.session(state: session, event: ["op": op]) { session = next }
    }

    /// The OS paused us (a call, Siri, an alarm) or let us go on.
    func interruption(began: Bool, shouldResume: Bool) {
        if began {
            applySession("interrupted")
            if session["effect"] as? String == "pause" { engine?.setPlaying(false) }
        } else {
            applySession("interruption-ended")
            if shouldResume, session["effect"] as? String == "play", !paused { engine?.setPlaying(true) }
        }
        publishOutput()
    }

    /// Headphones came out: pause, and stay paused when they come back (MUS-04).
    func outputLost() {
        enqueue {
            guard self.engine != nil, !self.paused else { return }
            self.applySession("noisy")
            self.savePosition()
            await self.commit(["op": "pause"])
        }
    }

    private func endOutput(_ cause: String, ticks: Int64? = nil) async {
        applySession(cause)
        if let engine, let active = account {
            let at = ticks ?? self.ticks(engine)
            store.savePosition(active.key, entry: current, ticks: at)
            await commit(["op": "pause"])
            await commit(["op": "position", "ticks": String(at)])
        }
        teardown()
    }

    private func teardown() {
        preparing?.cancel(); preparing = nil
        ticker?.cancel(); ticker = nil
        guard let running = engine else { publishOutput(); return }
        report(["op": "stop", "ticks": String(ticks(running))])
        engine = nil
        running.stop()
        prepared = [:]
        output = nil
        nowPlaying.detach()
        MusicAudioSession.deactivate()
        isPlaying = false; buffering = false; outputActive = false
    }
}

extension MusicHost: MusicEngineEvents {
    func engineAdvanced(to entry: String, natural: Bool) {
        let previous = reports["viewing"] as? [String: Any]
        let leaving = (previous?["media"] as? String).flatMap { cards[$0]?.durationTicks } ?? 0
        if let track = prepared[entry] {
            report(["op": "load", "entry": entry, "media": track.media, "ticks": String(lastTicks), "natural": natural, "durationTicks": String(leaving)])
            if let serial = (reports["viewing"] as? [String: Any])?["serial"] as? String { viewings[serial] = track }
            if engine?.isPlaying == true { report(["op": "playing", "ticks": "0"]) }
        }
        lastTicks = 0
        seconds = 0
        if natural { enqueue { await self.commit(["op": "end", "completion": self.committed["completion"] ?? "0"]) } }
        updateOutputLabel()
        nowPlaying.refresh(force: true)
    }

    func engineEnded() {
        let asked = engine
        enqueue {
            guard self.engine === asked, asked != nil else { return }
            await self.commit(["op": "end", "completion": self.committed["completion"] ?? "0"])
        }
    }

    func engineFailed(entry: String?, _ failure: MusicEngineFailure) {
        switch failure {
        case .revoked: enqueue { await self.revoked() }
        case .network:
            enqueue {
                self.savePosition()
                self.engine?.setPlaying(false)
                await self.commit(["op": "pause"])
                self.message = "Lost the connection. Your place is saved: press Play to try again."
            }
        case .media:
            enqueue {
                guard let entry, entry == self.current else { return }
                if let engine = self.engine { self.report(["op": "stop", "ticks": String(self.ticks(engine)), "failed": true]) }
                await self.failCurrent()
            }
        }
    }

    func engineChanged() {
        let was = isPlaying
        publishOutput()
        guard reports["viewing"] != nil, let engine, was != engine.isPlaying else { return }
        let at = ticks(engine)
        seconds = engine.seconds
        report(engine.isPlaying ? ["op": "playing", "ticks": String(at)] : ["op": "progress", "ticks": String(at), "paused": true])
        if !engine.isPlaying { savePosition() }
    }
}

/// The local Music quality choice (MUS-06), kept on this device.
enum MusicSettings {
    static let key = "music.quality.v1"
    static var quality: String {
        get { UserDefaults.standard.string(forKey: key).flatMap { MusicQuality.choices.contains($0) ? $0 : nil } ?? "auto" }
        set { UserDefaults.standard.set(newValue, forKey: key) }
    }
    static func label(_ choice: String) -> String {
        switch choice {
        case "original": return "Original"
        case "256": return "256 kbps"
        case "128": return "128 kbps"
        default: return "Auto (256 kbps on cellular)"
        }
    }
}

import Network

/// Whether the network is metered (cellular, or a hotspot the system calls expensive), for Auto music quality.
final class MusicNetwork: @unchecked Sendable {
    static let shared = MusicNetwork()
    private let monitor = NWPathMonitor()
    private let lock = NSLock()
    private var expensive = false
    var metered: Bool { lock.lock(); defer { lock.unlock() }; return expensive }
    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.lock.lock(); self.expensive = path.isExpensive || path.isConstrained; self.lock.unlock()
        }
        monitor.start(queue: DispatchQueue(label: "app.pictureshow.music.network"))
    }
}
