# Android design contract

`tokens.json` records the current Android designs, observed 2026-10-31. The reference commits are embedded in the file. `tv` means the Android TV UI on `main`; `touch` means the phone/tablet additions on `phone`. A port selects the appropriate mode for its input surface. The differences between the two are intentional facts of the reference, including their primary colors and body-small letter spacing.

Each entry under `tokens` has a stable dotted name, `type`, `value`, explicit `unit` where needed, and `sources`. A generator can turn `color.app.window` into `--color-app-window` for CSS, `ColorTokens.appWindow` for Swift, or a Kotlin constant. Values are resolved, so no alias evaluator or Android dependency is necessary. Some equal-valued semantic tokens document their related token in `description`; generate their literal values normally.

| Kind | Interpretation |
| --- | --- |
| `color` | sRGB `#RRGGBBAA`, including alpha. Android source literals use `#AARRGGBB`; the JSON has already reordered them. For Swift, divide each byte by 255. For CSS, the JSON string is directly usable. |
| `dimension` / `dp` | Logical dimensions. Map to CSS px or Apple points at base scale, then apply the platform's responsive layout, safe areas and density conventions. These are not physical pixels. |
| `typography` / `sp` | Font size, line height and letter spacing at Android font scale 1; font weight is the numeric weight. Use the platform's sans-serif system font and preserve user text scaling. The explicit form-input style inherits its line height and tracking instead of introducing new values; its `"inherited"` properties mean omit those generated properties and let the native text context provide them. |
| `duration` / `ms` | Milliseconds. `timing.*` entries are visibility/debounce behavior, distinct from motion animation. |
| `cubicBezier` | `[x1,y1,x2,y2]` control points. |
| `spring` | Damping ratio and stiffness from the pinned Material3 standard motion scheme. A spring has no constant duration; do not replace it with an invented millisecond token. |
| `ratio` | Width divided by height. Poster = 2/3; landscape = 16/9. |
| `percentage` | Radius as percent of the smaller dimension; 50 makes a capsule/circle. |
| `number` | Unitless scale or multiplier. |

The file separates semantic roles (`space.*`, `size.*`, `radius.*`, `focus.*`, `motion.*`) from the complete inventory of explicit application dp literals (`dimension.dp*`). The primitive inventory includes every observed noncomment dp literal in the TV app and `Touch*.kt` additions, with each occurrence's source location. It includes component sizes and breakpoints as well as spacing. Use semantic roles for design intent; use the inventory to resolve a component-specific dimension that has no shared role. Unspecified widget geometry continues to come from the pinned native component library, rather than a newly invented global spacing scale.

The current TV focus treatment has several distinct forms:

- Catalog cards use an 8dp radius, a 3dp `#9A95A1` focus border, and 1.1 focus scale. A selected season has a 2dp `#B8C4FF` resting border; the focus border replaces it. The card also draws a 3dp background-colored inner keyline to separate artwork from the outline. Draw one focus border at the card's scaled geometry.
- TV fields use a 3dp accent border when focused and a 1dp resting border. Search keeps the accent border at rest; sign-in fields use the muted resting outline.
- Long read-only text has a 2dp theme-border focus indicator and 4dp internal padding.
- TV buttons communicate focus through their filled foreground/background state and 1.1 scale. Player buttons reduce focus scale to 1 while an assistive service reads or operates the screen so their geometry remains inside the native screen-reader outline. There is no universal accent-colored focus ring applied to every control.
- Touch widgets use their native Material3 interaction treatment and system accessibility focus indication. Keyboard/D-pad ports still need a visible focus indicator; do not infer that touch's native outline is the TV card outline.

TV surface animations use the pinned library's 300ms focus, 500ms unfocus, 120ms press and 300ms release tweens, all with cubic-bezier `(0,0,.2,1)`. The application declares no separate page-transition tween. Phone widgets inherit Material3 interaction animations; its standard spring parameters are included without pretending they are fixed-duration transitions. Native system transitions and accessibility reduced-motion preferences remain platform-controlled.

Subtitle rendering follows system caption styling when the setting is System. The fixed size alternatives multiply the native subtitle base size by .75, 1, 1.25 or 1.5. The Box background has alpha 204/255 black. System font scale, embedded caption styling and accessibility preferences may change effective appearance; the token file does not claim a single hard-coded subtitle font or size.

`tv:` and `touch:` source markers refer to repository-relative paths at the embedded commits. `tvMaterial:` and `material3:` refer to paths or declarations inside the exact Google Maven source artifacts recorded in `sources`, with source-JAR SHA-256 hashes. The Android app overrides only TV `border`; its remaining TV theme colors and typography are the pinned TV Material defaults. The touch app overrides primary, secondary, background, surface and surfaceContainer; the other touch roles and its typography are the pinned Material3 defaults resolved through Compose BOM 2026.09.00. These library sources are primary evidence for inherited defaults; no client implementation code has been copied.

When the Android theme, dimensions or dependency versions change, update the relevant tokens and source commit together. Compare the effective mode-specific role values and component states during visual parity review. Parsing JSON alone validates its structure, not a rendered port; focus clipping, large text, compact landscape windows, system bars and safe areas still require platform screenshots and input/accessibility checks.
