# Pictureshow Web

A static React/TypeScript Jellyfin viewer. The `@pictureshow/core` package in `core/` ports the Kotlin slice's address discovery, authorization, version checks, catalog/detail resume projection, formatting, playback negotiation and reports. Native browser adapters supply codec probes, storage, focus navigation and media playback. No server component, Pictureshow account, administrator credential or hosted media relay is required.

## Run and build

Use Node 22.12+ (Node 24 LTS recommended) and npm. Dependencies are locked.

```sh
npm ci
npm run dev
npm run build
```

`dist/` contains only static HTML, CSS and JavaScript. Assets use relative paths, including the lazy HLS chunk. Serve the whole directory at the host root or a trailing-slash `/pictureshow/` location. No history fallback is required: navigation is in memory, so refresh returns to Home after validating the current browser-session sign-in. Nothing is deployed by these commands.

The user enters the API's own server URL independently of the app location, including any Jellyfin BaseUrl. A server installed under `/jellyfin` should be entered as `https://media.example/jellyfin`; media paths returned by Jellyfin retain that base path. Serving `/pictureshow/` does not itself enable Jellyfin to serve arbitrary files: the owner must configure their static server or reverse proxy, preserving `/web/` for administration.

## Private server access

- **Same origin:** serve the bundle alongside the API under `/pictureshow/`. Retain the server's official admin client. HTTP on a trusted LAN works as a basic viewer; no service worker or offline media caching is installed.
- **Cross-origin HTTPS:** Jellyfin and any owner proxy must allow the exact app origin, the Authorization and Content-Type headers, OPTIONS, video byte ranges, and long media requests. Use an owner endpoint with a browser-trusted certificate. A VPN supplies reachability, not certificate trust or CORS.
- **HTTPS app → HTTP LAN:** conditional Chromium support only. API, MSE HLS, image and subtitle fetches declare a recognized private/loopback `targetAddressSpace`; the browser owns permission prompts. Select Connect from a user gesture and grant Local Network access if requested. Permission denial, unsupported browsers and mixed-content policies can prevent this mode. Use HTTPS for the server or install the app on the server's own origin when it fails. Do not infer LAN-HTTP support from a successful loopback dev test.

API calls use the viewer's Authorization header, omit ambient cookies and reject redirects. Native `<video src>` cannot set custom headers, so direct files and native HLS use canonical `ApiKey` query authentication with the **viewer login token**. Every URL is checked against the selected server origin first, and token aliases supplied in a returned URL are replaced. MSE requests also validate each manifest/segment/key URL and reject redirects through the fetch loader. Images and text subtitles are fetched with viewer authentication and rendered as short-lived Blob URLs. Required third-party media headers are rejected rather than falsely claiming native playback can send them.

Viewer tokens are held in `sessionStorage`; passwords are not saved. The install's device ID lives in `localStorage`. Sign out revokes the viewer session. Native media query tokens are visible in the browser's network panel and can reach the owner's access logs; configure the proxy to redact sensitive query parameters. `Referrer-Policy: no-referrer` is set in the document, and fetches explicitly omit referrers. Native media redirects and nested native HLS requests are controlled by the browser and trusted owner server: this client cannot independently validate those redirects. Never print raw accounts, PlaybackInfo DTOs or playback URLs to diagnostics; use `redactUrl`.

## Playback and controls

Known container and codec support is independently checked with `canPlayType` before direct play. Unsupported containers, non-default embedded audio choices and conservative capability limits use Jellyfin HLS. Safari/WebKit uses native HLS when available; other supported engines use hls.js/MSE. The server receives a browser-specific device profile, never the Android device's decoder claims. A playback failure offers an explicit HLS retry. The browser may require a second Play gesture after asynchronous negotiation. A direct file that emits a media error or never starts within eight seconds automatically retries once through server HLS; a MIME probe is evidence of likely support, not a guarantee that a particular file is valid.

Start is reported after the media element actually starts playing. Progress reports run every ten seconds and on pause/resume. Back waits for stop reporting before refreshing detail state. Replacing audio/subtitle streams stops only that session's encoding, preserves the content position and renegotiates. Direct seeking uses the file's byte ranges; HLS seeking renegotiates at the requested content position and keeps report/subtitle offsets in the original timeline. Subtitle Off uses index -1. Text subtitles are requested as WebVTT; bitmap subtitles request server burn-in. A pagehide stop uses keepalive as a best effort; browser/process termination and network outages cannot guarantee delivery.

Keyboard and TV D-pad:

- Arrows move between visible controls; Enter activates the focused button.
- In text fields, Left/Right edit the text and Up/Down move to other controls.
- In native select fields, Up/Down select values and Left/Right move to other controls.
- In sliders, Left/Right adjust values and Up/Down move between controls.
- On the video, Enter/Space toggles playback and Left/Right seeks ten seconds; Up/Down returns to controls.
- Escape goes Back. Backspace goes Back outside text/select editing. Tab/Shift+Tab also work.
- Back restores the initiating item or play button after asynchronous data refresh.

Screens use landmarks, headings, labeled forms, native buttons/selects/sliders, lists, status/error announcements and visible focus. Artwork is decorative because the item name is already text. Reduced motion and system font scaling are retained. Automated accessibility checks supplement, rather than replace, a human screen-reader pass.

## Verification

```sh
npm test
npm run build
npx playwright install chromium webkit firefox
npm run test:e2e
# Run shared vectors after spec/ is integrated, or point at its worktree:
PICTURESHOW_SPEC=/path/to/spec npm run test:vectors
```

E2E fixture media is generated locally by `ffmpeg` from test signals. Tests use actual MP4 and HLS media bytes, password and Quick Connect flows, movie/series/season/episode navigation, keyboard-only activation, resume, session reports, audio/subtitle negotiation and focus restoration. No live credentials are required. The browser tests run serially with no retries. `PW_REUSE_SERVER=1` explicitly allows reuse of an already-running local dev server.

The vector runner currently covers the operations this slice has ported: address, authorization, sameOrigin, resolve, stripApiKey, ticks, reports and resume-start (47 vectors at the initial parity-spec revision). The remaining Android hardware profile/compatibility vectors are not browser decoder claims; full settings, queueing, segments, search, multi-account management and player extras remain outside this vertical slice. Web-specific vectors should cover MIME capability refusals, query-token replacement, redirect rejection, native/MSE timelines, stream-switch report ordering and delayed focus restoration. Core tests document the matching Kotlin test families.

On a host whose Firefox audio backend stalls, `npm run test:firefox:linux` runs the same suite in a temporary Linux Playwright container capped at two CPUs and 2 GB. It changes no host audio settings and removes the container afterward. This is an additional browser qualification route; a Linux pass does not establish host hardware audio output.
