/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
export function catalogReturnRefresh(page: string): string {
    switch (page) {
        case "Home": return "HomeRows";
        case "Detail": return "TitleAndChildren";
        case "Library": case "Person": return "LoadedCards";
        case "Search": return "SearchResults";
        default: return "None";
    }
}
export function playbackReturnData(previous: Record<string, unknown>, position: number, ended: boolean) {
    return { ...previous, PlaybackPositionTicks: ended ? 0 : Math.max(0, position), Played: !!previous.Played || ended };
}
