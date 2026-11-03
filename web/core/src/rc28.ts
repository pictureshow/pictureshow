/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
/** Lifecycle policy input is explicit: a browser may permit background playback. */
// Hidden tabs keep concerts, talk shows and music videos playing. Native page
// stops are represented independently by the retained pause latch.
export const WEB_FOREGROUND_REQUIRED = false;
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

/** A later accepted viewer choice owns played state over this report. */
export const detailCompletionMayApply = (
    reportChoiceRevision: number | null,
    currentChoiceRevision: number,
) =>
    reportChoiceRevision !== null &&
    reportChoiceRevision === currentChoiceRevision;

/** Plain focus text uses authoritative state when known and a neutral state otherwise. */
export const detailPendingPlainLabel = (
    target: string,
    known: boolean,
    played: boolean,
    favorite: boolean,
) => {
    if (target === "favorite") return favorite ? "Unfavorite" : "Favorite";
    if (known) return played ? "Mark unwatched" : "Mark watched";
    return played ? "Watched" : "Unwatched";
};

/**
 * One announcement is eligible only for the same focused action held through
 * the grace period. A failed live-region submission remains eligible for a
 * later attempt; fast completion, pause, cancellation or a focus change clears it.
 */
export function detailPendingFeedback(
    steps: Array<{
        waiting: boolean;
        graceExpired: boolean;
        focused: string | null;
        resumed: boolean;
        cancelled: boolean;
        submitted?: boolean;
    }>,
) {
    let held: string | null = null;
    let cue: string | null = null;
    let consumed = false;
    return steps.map((step) => {
        if (!step.waiting || !step.resumed || step.cancelled) {
            held = null;
            cue = null;
            consumed = false;
            return null;
        }
        if (!step.graceExpired) {
            held = step.focused;
            cue = null;
            consumed = false;
            return null;
        }
        if (step.focused !== held) {
            held = null;
            cue = null;
            consumed = false;
            return null;
        }
        if (cue === null && !consumed && held !== null) cue = held;
        if (cue === null) return null;
        const eligible = cue;
        if (step.submitted !== false) {
            consumed = true;
            cue = null;
        }
        return eligible;
    });
}
