# Shared Android TV settings components

Android TV settings use the components in `app/src/main/kotlin/app/pictureshow/TvSettingsComponents.kt`. They are internal composables in the `app.pictureshow` package and depend on AndroidX TV Material, rather than any playback preference model.

- `TvSettingsChoiceRow` shows a label, current value and chevron. OK opens `TvSettingsChoicePicker` on the current value; selecting an option commits once and closes. Back closes without changing the value and restores focus to the opening row. Left and Right also change the value for existing main-page shortcut behavior.
- `TvSettingsToggleRow` gives the whole row one focus target and exposes the actual checked state to Android accessibility. Its trailing switch is an indicator, rather than a second focus target.
- `TvSettingsActionRow` provides a single action target, with an optional navigation chevron. `TvSettingsSectionHeader` aligns its text with the row labels.
- `TvSettingsPanel` provides the full-height right-side panel, Back handling and initial focus. The caller attaches its supplied `FocusRequester` to the first setting. A picker instead attaches focus to its current value. There is no Done row competing for initial focus.
- `TvSettingsList` supplies vertical scrolling, conditional edge fades and a position indicator. Supporting text belongs under the row it explains. Keep current values short enough for the trailing column; put qualifications and consequences in supporting text or the picker.

Pass viewer-facing labels through the `label` callback, rather than displaying enum names. Keep values and persistence in the owning screen. Keep supporting text beside the choice that causes its consequence.

`TvSettingsComponentsTest` checks row interaction, current-value focus, selection, Back restoration, panel focus and geometry. `TvSettingsAccessibilityTest` checks the native Android checked-state export and measured radio contrast with native rendering. These tests accompany the reusable component implementation; feature screens retain their own persistence and navigation tests.

SyncPlay reuses the native rows and list with a pinned footer. `TvSettingsPanel` accepts optional `footer`, `contentFocusKey` and `panelWidth`; all defaults preserve existing settings callers. The footer stays outside the scroll region and its fades. `contentFocusKey` changes only when the usable content/primary changes, never on playback clock ticks. Native `onFocusChanged` supplies actual row focus across content replacement, including command rows with separate truthful parent semantics. Leading passive state marks are nonfocusable; their meaning is also included in the headline/supporting text. Original switch, radio, contrast, geometry and opener tests remain unchanged.
