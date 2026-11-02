/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
/** Pure viewing policies; milliseconds are absolute source positions. */
export const seekStepTarget = (
    positionMs: number,
    pendingMs: number | null | undefined,
    deltaMs: number,
    durationMs: number,
) => {
    const duration = Math.max(0, durationMs);
    return Math.max(
        0,
        Math.min(
            duration,
            Math.max(0, Math.min(duration, pendingMs ?? positionMs)) + deltaMs,
        ),
    );
};
export const unchangedTrack = (currentIndex: number, pickedIndex: number) =>
    currentIndex === pickedIndex;
export const reportTrackIndex = (
    selectedIndex: number | null | undefined,
    chosenByViewer: boolean,
): number | null => (chosenByViewer ? (selectedIndex ?? null) : null);
export const externalSubtitleRetryDelay = (errorCount: number) =>
    Math.min(10000, Math.max(1, errorCount) * 1000);
export const externalSubtitleOffset = (
    fullTimeline: boolean,
    serverStartMs: number,
) => (fullTimeline ? serverStartMs : 0);
export const canSelectSubtitleLocally = (
    transcoding: boolean,
    subtitle: boolean,
    currentLocal: boolean,
    nextLocal: boolean,
) => !transcoding || (subtitle && currentLocal && nextLocal);
export function playbackReportFields(
    kind: string,
    positionTicks: number,
    audio: number | null | undefined,
    subtitle: number | null | undefined,
    audioViewer: boolean,
    subtitleViewer: boolean,
) {
    if (!["start", "progress", "stop"].includes(kind))
        throw new RangeError("Unknown report kind");
    return `kind=${kind} positionMs=${Math.trunc(positionTicks / 10000)} audio=${kind === "stop" ? "omitted" : (audio ?? "omitted")} subtitle=${kind === "stop" ? "omitted" : (subtitle ?? "omitted")} audioOrigin=${audioViewer ? "viewer" : "automatic"} subtitleOrigin=${subtitleViewer ? "viewer" : "automatic"}`;
}
export type PlaybackHttpOutcome = {
    status: number | null;
    failure:
        "Http" | "Timeout" | "Network" | "InvalidResponse" | "Cancelled" | null;
    elapsedMs: number;
};
/** Ready is a transition on the same held control, never a later focus visit. */
export class DetailReadyFeedback {
    private wasPresented = false;
    private previousFocus: string | null = null;
    update(
        held: boolean,
        focused: string | null,
        resumed: boolean,
        usable = true,
        options: {
            presented?: boolean;
            cancelled?: boolean;
            submitted?: boolean;
            nativePresented?: string | null;
        } = {},
    ): string | null {
        const sameFocus = focused !== null && focused === this.previousFocus;
        if (!resumed || options.cancelled || !sameFocus)
            this.wasPresented = false;
        if (
            resumed &&
            !options.cancelled &&
            focused !== null &&
            ((held && options.presented === true) ||
                options.nativePresented === focused)
        )
            this.wasPresented = true;
        const cue =
            this.wasPresented &&
            !held &&
            usable &&
            sameFocus &&
            resumed &&
            !options.cancelled
                ? "Ready"
                : null;
        if (cue && options.submitted !== false) this.wasPresented = false;
        this.previousFocus = focused;
        return cue;
    }
}
export class DetailReadLifecycle {
    private hasResumed = false;
    resumed() {
        const refresh = this.hasResumed;
        this.hasResumed = true;
        return refresh;
    }
}
export const detailActionDescription = (
    currentState: string | null,
    available: boolean,
) => (available ? currentState : null);
export const detailHeldActionLabel = (target: string) =>
    (
        ({
            primary: "Play",
            "from-start": "Play from start",
            played: "Watched",
        }) as Record<string, string>
    )[target] ?? "Favorite";
export const detailUnavailableActionLabel = (target: string) =>
    target === "from-start"
        ? "Start unavailable"
        : `${detailHeldActionLabel(target)} unavailable`;
export const detailFailureKey = (
    readRecovery: boolean,
    retrying: boolean,
    writeAttempt: number | null,
) =>
    readRecovery
        ? retrying
            ? null
            : "read"
        : writeAttempt != null
          ? `write:${writeAttempt}`
          : null;
export type DetailFailurePresentation = { key: string; message: string };
export const presentDetailFailure = (
    previous: DetailFailurePresentation | null,
    key: string | null,
    message: string | null,
): DetailFailurePresentation | null =>
    key === null || message === null
        ? null
        : previous?.key === key
          ? previous
          : { key, message };
export const detailRequestFeedbackIsUrgent = (
    newlyAccepted: boolean,
    requested: boolean,
    available: boolean,
) => newlyAccepted && requested && available;
export const catalogArrivalUsesAnimation = (
    accessibilityEnabled: boolean,
    sourceIsPlayback: boolean,
    destinationIsCatalog: boolean,
) => !accessibilityEnabled || sourceIsPlayback || !destinationIsCatalog;
export const detailArrivalAwaitsAction = (
    loading: boolean,
    contentRevealed: boolean,
    returningFromPlayback: boolean,
) => loading && !contentRevealed && !returningFromPlayback;
export const detailMetadataAwaitsHandoff = (
    hasActions: boolean,
    handoffFinished: boolean,
    returningFromPlayback: boolean,
    failed: boolean,
) => hasActions && !handoffFinished && !returningFromPlayback && !failed;
export const detailSuppressesEmptyHostFocus = (
    ordinaryArrivalWithActions: boolean,
    handoffFinished: boolean,
    returningFromPlayback: boolean,
    recoveryHandoff: boolean,
    failed: boolean,
) =>
    recoveryHandoff ||
    (!handoffFinished &&
        (returningFromPlayback || (ordinaryArrivalWithActions && !failed)));
export const detailArrivalStillPending = (
    wasPending: boolean,
    handoffFinished: boolean,
) => wasPending && !handoffFinished;
export const detailReadCanReplaceCompletion = (
    completionItem: string,
    completionRead: number,
    item: string,
    read: number,
    succeeded: boolean,
) => succeeded && completionItem === item && read > completionRead;
export const detailNeedsPendingControls = (
    loading: boolean,
    firstArrival: boolean,
    canQueue: boolean,
    returningFromPlayback = false,
) => loading && firstArrival && canQueue && !returningFromPlayback;
export const detailRequestedActionLabel = (
    target: string,
    newlyAccepted: boolean,
) =>
    newlyAccepted
        ? target === "primary"
            ? "Play requested"
            : target === "from-start"
              ? "Start requested"
              : `${detailHeldActionLabel(target)} change requested`
        : `${detailHeldActionLabel(target)} already requested`;
export const catalogRevisionNeedsRefresh = (
    previous: number | null,
    current: number,
) => previous === null || previous !== current;
export const detailReadBandText = (retrying: boolean, reason: string) =>
    retrying ? "Loading item. Please wait." : reason;
export const detailSessionNeedsRead = (
    newRevision: boolean,
    refreshed: boolean,
    container: boolean,
    alreadyConfirmed: boolean,
) =>
    newRevision && (refreshed || container) && !(refreshed && alreadyConfirmed);
export const detailReadHasFailure = (
    readFailed: boolean,
    reason: string | null,
    mutationReady: boolean,
) => reason !== null && (readFailed || !mutationReady);
export const detailRequestCueRemainsActive = (
    active: boolean,
    readerLeftAction: boolean,
    requested = true,
    presented = true,
) => active && requested && (!presented || !readerLeftAction);
export const detailHasConfirmedLeafRead = (
    readSucceeded: boolean,
    sameUserData: boolean,
    container: boolean,
) => readSucceeded && sameUserData && !container;
export const detailReadRequiresDependents = (type: string) =>
    ["Series", "Season"].includes(type);
export const showDetailEmptyState = (
    itemType: string | null,
    loading: boolean,
    readSucceeded: boolean,
    readFailed: boolean,
    episodeCount: number,
    seasonCount: number,
) =>
    ["Series", "Season"].includes(itemType ?? "") &&
    !loading &&
    readSucceeded &&
    !readFailed &&
    episodeCount === 0 &&
    seasonCount === 0;
