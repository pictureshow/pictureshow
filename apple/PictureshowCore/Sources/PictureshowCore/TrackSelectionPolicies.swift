// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
/// Reporting provenance is independent for each track type. Negotiation still
/// receives automatic indices, but reporting must not make them saved choices.
public enum TrackSelectionReporting {
    public static func index(selectedIndex: Int?, chosenByViewer: Bool) -> Int? {
        chosenByViewer ? selectedIndex : nil
    }
}
public enum TrackSelection {
    public static func unchanged(currentIndex: Int, pickedIndex: Int) -> Bool {
        currentIndex == pickedIndex
    }
}
