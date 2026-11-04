# Pictureshow parity contract

Android is the behavioral reference. Apple and web consume this directory to implement the same user outcomes,
protocol decisions and visual roles in their native stacks. This is the contract for a port, not a claim that any
port or every Android acceptance check already passes.

The baseline is Android `main` at `fce5d6dfa63c6b41c0ea1c75919d4a981d836c5e` (2026-10-31), plus the touch additions
on `phone` at `c46bc87c8e3c8a5ff5e432a565a4144ef1bb1e70`. These commits diverge: take the touch layout and interactions
from `phone` and the later playback, focus and recovery corrections from `main`. Do not replace main's logic with
the older phone snapshot. Later source moves made to execute the vectors retain the reference behavior.

## What to consume

- [features](features/README.md) groups screens, actions, settings and acceptance criteria by screen family. Stable
  IDs identify behaviors across platform checklists. Follow its shared focus, Back, speech and recovery rules as well
  as the screen's individual requirements. It records known gaps and unrun checks separately from intended acceptance.
- [vectors](vectors/README.md) contains language-neutral JSON fixtures with exact expected outputs, request payloads,
  response inputs and source provenance. Run these JSON files unchanged against each platform's implementation.
- [design/tokens.json](design/tokens.json) contains stable, typed dotted names, resolved values, units and source
  provenance. [The design guide](design/README.md) explains TV/touch modes and platform generation.

A native feature can use platform controls and accessibility conventions while preserving its observable contract.
A platform's codec, container, HDR, subtitle and audio route capabilities are measured inputs. They are not copied
from an Android device or inferred from an OS name. Present unsupported features honestly, with a supported fallback
or an explicit reason, as the feature manifest specifies. Android-specific Media3 transport workarounds remain
identified as such; a platform that needs a different profile documents the deviation and tests its own capability
inputs. Never claim passthrough, Dolby Vision, direct play, LAN discovery, voice input or billing support without
that platform providing it.

## Executing the logic contract

Each platform creates a thin test adapter mapping `operation` and `input` to production functions. It compares the
entire `expected` JSON value structurally: object member order is irrelevant; arrays, enum/string spellings, null,
field omission, booleans and integers are significant. The adapter must fail unknown schema versions, suites and
operations, duplicate IDs and empty suites. Discover every JSON file recursively; an unhandled fixture is a failing
contract, never a silently skipped test. Keep test expectations independent of implementation output: no automatic
snapshot update is part of the runner.

Kotlin packages `spec/vectors/` as `parity-vectors/` test resources. `ReferenceParityVectorsTest` discovers all JSON
fixtures, verifies the global ID set and dispatches the reference suite; `ApplicationParityVectorsTest` executes the
application suite, and `RecoveryParityVectorsTest` executes the recovery suite. All three aggregate case failures so one failure does not conceal the others. They run in ordinary
`:core:jvmTest` and therefore in the existing CI `check` task:

```sh
./gradlew :core:jvmTest
./gradlew check assembleDebug
```

In the development workspace, use the project's remote Gradle wrapper with the current checkout as `PS_SRC` and a
unique `PS_REMOTE`. The wrapper is maintained outside the public repository; it serializes capped builds and copies
reports into that checkout's `build-out/`. A port can implement the same adapters in Swift Testing/XCTest or its web
test runner; consuming only the Kotlin test source is insufficient because the fixtures are the shared inputs and
expected results.

There is no network or credential dependency: server names, users, tokens and addresses in vectors are invented;
HTTP responses are supplied explicitly to the production clients through a mock transport. These fixtures verify
logic and protocol behavior. They do not prove native decoder output, a visible focus target, spoken reading order,
real server version compatibility or a billing transaction. Use the feature acceptance criteria and actual native
screen/input/playback checks for those outcomes.

## Generating design code

Select the `tv` or `touch` role where a token has a mode-specific name, transform dotted names into each platform's
identifier style and emit the literal typed value. CSS can use `--color-tv-primary`; Swift can use a namespaced
constant; Android can generate Kotlin properties. Colors are sRGB `#RRGGBBAA`, not Android's alpha-first literal
order. Map logical dp to base CSS pixels or Apple points and sp to text scaled by the user's preference. Respect safe
areas, platform font metrics and responsive breakpoints. Preserve the distinct focused, selected, pressed, disabled
and accessibility states; the current Android focus border is not universally the accent color.

The token file resolves inherited Material values from pinned primary source artifacts. It records springs as
springs rather than inventing durations. A generator need not import Android, evaluate aliases or fetch a dependency
at runtime. Screen review still checks focus clipping, contrast, large text, screen-reader geometry and touch targets.

## Keeping parity over time

**A logic fix lands with a vector.** Add a case that distinguishes the previous behavior from the corrected one,
keep the expected value explicit, and run it against the Android reference. If Android fails a vector, record a
finding, identify whether the expectation or reference contradicts the established behavior, and resolve it with
evidence. Do not change an expected value just to obtain green tests. A consciously accepted behavior change updates
the feature contract and vector together, so implementations that have not ported the fix fail until they do.

A new screen, action, setting or recovery path updates its feature family and acceptance criteria. A theme or
component-library change updates token values and provenance. Record platform capability exceptions explicitly;
unspecified omissions are parity gaps. Keep IDs stable, add new IDs rather than renaming unrelated cases, and review
the source paths/commit pins when moving code. A coordinated release includes vector results and relevant rendered,
input and screen-reader checks from every first-wave platform, with any remaining gaps visible to its reviewer.
