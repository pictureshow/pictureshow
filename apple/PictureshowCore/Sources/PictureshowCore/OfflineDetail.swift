// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

public func offlineDetailPlaySource(ready: Bool, server: OfflineServerReach, explicitStream: Bool) -> OfflineDetailSource {
    if !explicitStream && ready { return .local }
    return server == .unreachable ? .unavailable : .stream
}
public func offlineDetailStartTicks(fromStart: Bool, outbox: OfflineOutboxEntry?, lastKnown: OfflineUserData?, server: OfflineUserData?) -> Int64 {
    if fromStart { return 0 }
    let unsent = outbox.map { $0.phase != "synced" && $0.phase != "conflict" } ?? false
    if !unsent, let server { return server.played ? 0 : max(0, server.positionTicks) }
    return offlineResumeTicks(entry: outbox, lastKnown: lastKnown)
}
