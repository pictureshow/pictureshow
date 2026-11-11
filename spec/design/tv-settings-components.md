# Shared Android TV settings components

Android TV settings use the components in `app/src/main/kotlin/app/pictureshow/TvSettingsComponents.kt`. They are internal composables in the `app.pictureshow` package and depend on AndroidX TV Material, rather than any playback preference model.

- `TvSettingsChoiceRow` shows a label, current value and chevron. OK opens `TvSettingsChoicePicker` on the current value; selecting an option commits once and closes. Back closes without changing the value and restores focus to the opening row. Left and Right also change the value for existing main-page shortcut behavior.
- `TvSettingsToggleRow` gives the whole row one focus target and exposes the actual checked state to Android accessibility. Its trailing switch is an indicator, rather than a second focus target.
- `TvSettingsActionRow` provides a single action target, with an optional navigation chevron. `TvSettingsSectionHeader` aligns its text with the row labels.
- `TvSettingsPanel` provides the full-height right-side panel, Back handling and initial focus. The caller attaches its supplied `FocusRequester` to the first setting. A picker instead attaches focus to its current value. There is no Done row competing for initial focus.
- `TvSettingsList` supplies vertical scrolling, conditional edge fades and a position indicator. Supporting text belongs under the row it explains. Keep current values short enough for the trailing column; put qualifications and consequences in supporting text or the picker.

Pass viewer-facing labels through the `label` callback, rather than displaying enum names. Keep values and persistence in the owning screen. Keep supporting text beside the choice that causes its consequence.

`TvSettingsComponentsTest` checks row interaction, current-value focus, selection, Back restoration, panel focus and geometry. `TvSettingsAccessibilityTest` checks the native Android checked-state export and measured radio contrast with native rendering. These tests accompany the reusable component implementation; feature screens retain their own persistence and navigation tests.
