/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
/** Lifecycle policy input is explicit: a browser may permit background playback. */
export const visibilityPlay = (
    requested: boolean,
    foregroundRequired: boolean,
    foreground: boolean,
    pauseLatched: boolean,
) => requested && (!foregroundRequired || foreground) && !pauseLatched;

export const detailRetryDescription = (reason: string) =>
    `Retry. Loading failed. ${reason.trim().replace(/\.$/, "")}`;
export const detailQuietDepartingChange = (
    handoff: boolean,
    action: boolean,
    onlyLabelChanged: boolean,
) => handoff && action && onlyLabelChanged;
export const detailQuietRequestRediscovery = (
    action: boolean,
    onlyLabelChanged: boolean,
    rediscovery: boolean,
) => action && onlyLabelChanged && rediscovery;

/** Eligibility does not consume feedback: only an accepted submission does. */
export class DetailRequestOnceFeedback {
    private consumed: number | null = null;
    update(request: number | null, eligible: boolean, submitted: boolean) {
        const cue = request !== null && request !== this.consumed && eligible;
        if (cue && submitted) this.consumed = request;
        return cue;
    }
}
export const detailHeldLabelPresentation = (
    selectedWhileHeld: boolean,
    heldLabelRequested: boolean,
) => selectedWhileHeld && heldLabelRequested;
export const detailFeedbackSource = (
    currentPage: boolean,
    selectedAction: boolean,
) => currentPage && selectedAction;
export const detailPendingPresentationLabel = (
    held: boolean,
    requested: boolean,
    available: boolean,
    plain: string,
    pending: string,
) => (held || requested || !available ? pending : plain);
export const detailCompletionPresentedPlayed = (
    played: boolean,
    completionIntent: boolean,
) => played || completionIntent;
export const detailPlayedRequestNotice = (wanted: boolean, current: boolean) =>
    wanted === current
        ? `Nothing changed. This item is already ${current ? "watched" : "unwatched"}.`
        : null;
export const detailReaderFocusClear = (
    currentPage: boolean,
    ownedHeader: boolean,
    sameNativeNode: boolean,
) => currentPage && ownedHeader && sameNativeNode;
export const detailRetryAttemptSucceeded = (
    baseline: number | null,
    attempt: number,
    loading: boolean,
    failed: boolean,
) => baseline !== null && attempt > baseline && !loading && !failed;
export const detailRetryFailureCue = (
    baseline: number | null,
    attempt: number,
    loading: boolean,
    failed: boolean,
    resumed: boolean,
) => baseline !== null && attempt > baseline && !loading && failed && resumed;
/** Structural equality is on raw UserData, never its provisional presentation. */
export function detailRawReadAcknowledgement(
    raw: Record<string, unknown> | null,
    acknowledged: Record<string, unknown>,
): boolean {
    const equal = (a: unknown, b: unknown): boolean => {
        if (a === b) return true;
        if (!a || !b || typeof a !== "object" || typeof b !== "object")
            return false;
        if (Array.isArray(a) !== Array.isArray(b)) return false;
        const left = Object.keys(a),
            right = Object.keys(b);
        return (
            left.length === right.length &&
            left.every(
                (key) =>
                    Object.hasOwn(b, key) &&
                    equal(
                        (a as Record<string, unknown>)[key],
                        (b as Record<string, unknown>)[key],
                    ),
            )
        );
    };
    return raw !== null && equal(raw, acknowledged);
}
export const detailPendingPresentationVisible = (
    waiting: boolean,
    resumed: boolean,
    published: boolean,
) => waiting && resumed && published;
export const detailCompletionIntentRetained = (
    reportFailed: boolean,
    reportPending: boolean,
    rawReadSucceeded: boolean,
) => !reportFailed && (reportPending || !rawReadSucceeded);
export const detailRetryCueCanWait = (
    accessibilityEnabled: boolean,
    elapsedMillis: number,
) => accessibilityEnabled && elapsedMillis < 30_000;
