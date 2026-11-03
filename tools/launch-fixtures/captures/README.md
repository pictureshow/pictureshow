# Redacted runtime receipts

Captured on2026-11-03 from three isolated synthetic Jellyfin fixtures. IDs are capture-local aliases, never reusable server IDs. Credentials, media URLs, filesystem paths, endpoints and server names are omitted. Source content is CC0; receipt format/code is MPL-2.0.

| Version | Transport | Recordings | Audio conversion | WebSocket authentication | Android reverse |
|---|---|---|---|---|---|
|10.10.7|[transport](transport-10.10.7.json)|[recordings](recordings-10.10.7.json)|[audio](audio-10.10.7.json)|[ws-auth](ws-auth-10.10.7.json)|[adb](adb-10.10.7.json)|
|10.11.11|[transport](transport-10.11.11.json)|[recordings](recordings-10.11.11.json)|[audio](audio-10.11.11.json)|[ws-auth](ws-auth-10.11.11.json)|[adb](adb-10.11.11.json)|
|12.4|[transport](transport-12.4.json)|[recordings](recordings-12.4.json)|[audio](audio-12.4.json)|[ws-auth](ws-auth-12.4.json)|[adb](adb-12.4.json)|

The check named playlist-repeat-runtime-receipt asserts that a response was observed; its repeatPreserved/distinctEntryIds fields are the result, not a promise of duplicate semantics. Growing negotiation retains initial empty-source errors and the later ready source. Completed recordings have independent download/ffprobe/decode receipts. Close404 classification is tested locally; runtime Close statuses were204. No native renderer, offline package, receiver or drift acceptance is implied.

`websocketEvents` retains nested SyncPlayGroupUpdate/SyncPlayCommand and session Play/Playstate/GeneralCommand/Sessions envelopes. Ignore absolute capture times when building deterministic vectors. The scheduled Unpause check matches the current group/entry and the same When on both viewers.
