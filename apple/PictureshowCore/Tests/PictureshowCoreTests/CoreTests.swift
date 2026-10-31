// SPDX-License-Identifier: MPL-2.0
import XCTest
@testable import PictureshowCore

final class CoreTests: XCTestCase {
    let server = Server(url: URL(string: "https://example.test/jellyfin")!)
    var account: Account { Account(server: server, userId: "user", username: "Viewer", accessToken: "secret-token", deviceID: "account-device") }

    // Ports AddressCandidatesTest: a missing scheme must preserve HTTPS-first fallback and base paths.
    func testAddressCandidatesPreserveBasePathExplicitPortsAndIPv6() throws {
        XCTAssertEqual(try ServerURLs.candidates("host/jellyfin/").map(\.absoluteString), ["https://host/jellyfin", "https://host:8920/jellyfin", "http://host/jellyfin", "http://host:8096/jellyfin"])
        XCTAssertEqual(try ServerURLs.candidates("host:8123").map(\.absoluteString), ["https://host:8123", "http://host:8123"])
        XCTAssertEqual(try ServerURLs.candidates(" http://[::1]:8096/jellyfin/ ").map(\.absoluteString), ["http://[::1]:8096/jellyfin"])
        for input in ["", " ", "ftp://host", "http://name:password@host", "host/path?token=secret", "host/#fragment", "bad host", "http://host:invalid", "http://host:99999", "http://host:0", "http://host:"] { XCTAssertThrowsError(try ServerURLs.candidates(input), input) }
    }
    // Ports AuthorizationHeaderTest: server parsing URL-decodes quoted components, not backslash escapes.
    func testAuthorizationDelimiterAndUnicodeValuesRoundTrip() throws {
        let device = "Mom's TV, upstairs \"été\" + 100% \\ screen\r\n"
        let token = "a+b/%=,\""
        let header = AuthorizationHeader.build(deviceID: "id=42,+%\"", deviceName: device, token: token)
        XCTAssertTrue(header.allSatisfy { $0.asciiValue.map { (32...126).contains($0) } ?? false })
        let fields = header.dropFirst("MediaBrowser ".count).components(separatedBy: ", ")
        let values = Dictionary(uniqueKeysWithValues: fields.map { field -> (String,String) in
            let i = field.firstIndex(of: "=")!
            return (String(field[..<i]), String(field[field.index(after: i)...]).trimmingCharacters(in: CharacterSet(charactersIn: "\"")).removingPercentEncoding!)
        })
        XCTAssertEqual(values["Device"], device); XCTAssertEqual(values["DeviceId"], "id=42,+%\""); XCTAssertEqual(values["Token"], token)
        XCTAssertFalse(AuthorizationHeader.build(deviceID: "stable").contains("Token"))
    }
    // Ports PlayDecisionTest: stream decisions retain base path, prefer direct playback and never URL-token auth.
    func testDirectPlayDecisionAndRequiredHeaderFiltering() throws {
        var source = MediaSource(id: "source", container: "mp4", supportsDirectPlay: true)
        source.eTag = "etag"; source.requiredHttpHeaders = ["Authorization":"bad", "authorization":"bad", "ApiKey":"secret-token", "X-Emby-Token":"secret-token", "X-Test":"required"]
        let decision = try PlayDecisions.decide(account: account, itemID: "item/1", response: PlaybackInfo(mediaSources: [source], playSessionId: "session"), startTicks: 200_000_000)
        XCTAssertEqual(decision.method, .directPlay)
        XCTAssertEqual(decision.url.absoluteString, "https://example.test/jellyfin/Videos/item%2F1/stream?static=true&mediaSourceId=source&playSessionId=session&tag=etag")
        XCTAssertEqual(Set(decision.headers.keys), Set(["Authorization", "X-Test"]))
        XCTAssertTrue(decision.headers["Authorization"]!.contains("account-device"))
        XCTAssertFalse(decision.url.absoluteString.contains("secret-token"))
        XCTAssertEqual(decision.initialSeekSeconds, 20)
        XCTAssertEqual(decision.serverPositionTicks(playerSeconds: 25), 250_000_000)
    }
    func testHLSDecisionStripsEncodedKeysAndKeepsFullVODTimeline() throws {
        let source = MediaSource(id: "s", transcodingUrl: "/videos/i/master.m3u8?StartTimeTicks=300000000&%41piKey=secret-token&api_key=secret-token&VideoCodec=h264&TranscodeReasons=ContainerNotSupported%2CAudioCodecNotSupported")
        let d = try PlayDecisions.decide(account: account, itemID: "i", response: PlaybackInfo(mediaSources: [source]), startTicks: 400_000_000)
        XCTAssertEqual(d.method, .transcode); XCTAssertEqual(d.initialSeekSeconds, 40)
        XCTAssertEqual(d.startTicks, 400_000_000); XCTAssertEqual(d.serverPositionTicks(playerSeconds: 42), 420_000_000)
        XCTAssertEqual(d.transcodeReasons, ["ContainerNotSupported", "AudioCodecNotSupported"])
        XCTAssertFalse(d.url.absoluteString.contains("secret-token"))
        XCTAssertTrue(d.url.absoluteString.hasPrefix("https://example.test/jellyfin/videos/"))
        XCTAssertFalse(String(describing: d).contains("secret-token"))
        XCTAssertFalse(String(reflecting: account).contains("secret-token"))
    }
    func testHLSResumeSeeksFullTimelineWithoutInventingServerStartParameter() throws {
        let source = MediaSource(transcodingUrl: "/videos/i/master.m3u8?VideoCodec=h264")
        let d = try PlayDecisions.decide(account: account, itemID: "i", response: PlaybackInfo(mediaSources: [source]), startTicks: 300_000_000)
        XCTAssertNil(URLComponents(url: d.url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "StartTimeTicks" }))
        XCTAssertEqual(d.initialSeekSeconds, 30)
        XCTAssertEqual(d.serverPositionTicks(playerSeconds: 35), 350_000_000)
    }
    func testStreamURLsRejectCrossOriginCredentialsAndMalformedAuthorities() throws {
        for value in ["https://evil.test/v.m3u8", "//evil.test/v.m3u8", "http://example.test/v.m3u8", "https://example.test:444/v.m3u8", "https://user@example.test/v.m3u8", "file:///tmp/video", "https://example.test:0/v", "https://example.test\\@evil.test/v", "https://example.test/v\n"] {
            let source = MediaSource(transcodingUrl: value)
            XCTAssertThrowsError(try PlayDecisions.decide(account: account, itemID: "i", response: PlaybackInfo(mediaSources: [source])), value)
        }
        XCTAssertTrue(ServerURLs.isSameOrigin(URL(string: "https://EXAMPLE.test:443/")!, URL(string: "https://example.test/v")!))
        XCTAssertThrowsError(try PlayDecisions.decide(account: account, itemID: "i", response: PlaybackInfo(mediaSources: [], errorCode: "FutureError")))
        XCTAssertThrowsError(try PlayDecisions.decide(account: account, itemID: "i", response: PlaybackInfo(mediaSources: [MediaSource(id: "wrong", supportsDirectPlay: true)]), mediaSourceID: "missing"))
    }
    // Ports CatalogClientTest / RuntimeDisplayTest / ContainerCountsTest: tolerate absent optionals, constrain display only.
    func testCatalogNullDefaultsRuntimeProgressAndEmptyContainer() throws {
        var item = try JSONDecoder().decode(MediaItem.self, from: Data(#"{"Id":"i","Name":null,"Type":"Series","UserData":{"Played":true},"ChildCount":0,"Genres":null}"#.utf8))
        XCTAssertEqual(item.name, "Untitled"); XCTAssertEqual(item.genres, []); XCTAssertFalse(item.isWatched)
        item.recursiveItemCount = 3; XCTAssertTrue(item.isWatched)
        item.userData.playbackPositionTicks = 200_000_000; item.runTimeTicks = 1_000_000_000; XCTAssertEqual(item.progress, 0.2)
        item.userData.playedPercentage = 150; XCTAssertEqual(item.progress, 1)
        XCTAssertEqual(Catalog.resumeLabel(36_610_000_000), "Resume from 1:01:01")
        XCTAssertEqual(Catalog.resumeLabel(-1), "Resume from 0:00")
        XCTAssertEqual(Catalog.runtimeLabel(Int64.max), ""); XCTAssertEqual(Catalog.runtimeLabel(1), "1m")
        XCTAssertEqual(Catalog.runtimeLabel(54_000_000_000), "1h 30m")
        item.parentIndexNumber = 0; item.indexNumber = 2; XCTAssertEqual(item.episodeLabel, "Special E2")
    }
    func testHomeOrderAndOnlyMovieAndTelevisionLatestRows() {
        var movie = MediaItem(id: "m", name: "Movies"); movie.collectionType = "movies"
        var music = MediaItem(id: "a", name: "Audio"); music.collectionType = "music"
        var tv = MediaItem(id: "t", name: "TV"); tv.collectionType = "tvshows"
        let rows = Catalog.assembleHome(views: [tv, music, movie], resume: [movie], nextUp: [tv], latest: ["m":[movie], "t":[tv], "a":[music]])
        XCTAssertEqual(rows.map(\.id), ["resume", "nextup", "libraries", "latest:t", "latest:m"])
        XCTAssertTrue(rows[0].wide); XCTAssertTrue(rows[2].library)
    }
    func testTicksSaturateWithoutOverflowAndVersionFloor() {
        XCTAssertEqual(Ticks.from(seconds: 1.2345678), 12_345_678)
        XCTAssertEqual(Ticks.from(seconds: Double.greatestFiniteMagnitude), Int64.max)
        XCTAssertEqual(Ticks.from(seconds: .nan), 0)
        XCTAssertEqual(Ticks.adding(Int64.max, 1), Int64.max)
        XCTAssertTrue(JellyfinClient.supportedVersion("12.0.0")); XCTAssertTrue(JellyfinClient.supportedVersion("10.10.0"))
        XCTAssertFalse(JellyfinClient.supportedVersion("10.9.11")); XCTAssertFalse(JellyfinClient.supportedVersion("garbage"))
    }
}
