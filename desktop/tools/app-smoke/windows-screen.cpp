// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
// Hosted smoke setup only. Dynamic settings; no registry writes or driver installs.
#define UNICODE
#define _UNICODE
#include <windows.h>
#include <cstdlib>
#include <cstddef>
#include <fstream>
#include <iostream>
#include <vector>
#include <algorithm>

static DEVMODEW mode() {
    DEVMODEW result{};
    result.dmSize = sizeof(result);
    return result;
}
static bool primaryMonitor(MONITORINFO &result) {
    result = {};
    result.cbSize = sizeof(result);
    return GetMonitorInfoW(MonitorFromPoint(POINT{0, 0}, MONITOR_DEFAULTTOPRIMARY), &result);
}
static bool fits(const RECT &area) {
    return area.right - area.left >= 1920 && area.bottom - area.top >= 1080;
}
// EnumDisplaySettings may return an older public DEVMODE prefix than this
// SDK allocates. Preserve that returned size; require every field we compare
// and reject private bytes that our fixed receipt does not store.
static constexpr size_t minimumPublicModeBytes = offsetof(DEVMODEW, dmDisplayFrequency) + sizeof(DWORD);
static bool validSavedModeHeader(const DEVMODEW &saved) {
    return saved.dmSize >= minimumPublicModeBytes && saved.dmSize <= sizeof(saved) && saved.dmDriverExtra == 0;
}
static bool sameMode(const DEVMODEW &a, const DEVMODEW &b) {
    return a.dmPelsWidth == b.dmPelsWidth && a.dmPelsHeight == b.dmPelsHeight &&
           a.dmBitsPerPel == b.dmBitsPerPel && a.dmDisplayFrequency == b.dmDisplayFrequency;
}
static void writeMode(const DEVMODEW &value) {
    std::cout << "{\"width\":" << value.dmPelsWidth << ",\"height\":" << value.dmPelsHeight
              << ",\"bits\":" << value.dmBitsPerPel << ",\"frequency\":" << value.dmDisplayFrequency << "}";
}
// Flushed, value-free stage names plus numeric API/header facts survive a
// native crash. Keep paths and opaque driver data out of published receipts.
static void traceStage(const char *stage) {
    std::cerr << "{\"stage\":\"" << stage << "\"}" << std::endl;
}
static void traceResult(const char *stage, LONG result) {
    std::cerr << "{\"stage\":\"" << stage << "\",\"result\":" << result << "}" << std::endl;
}
static void traceMode(const char *stage, const DEVMODEW &value, bool observed) {
    std::cerr << "{\"stage\":\"" << stage << "\",\"observed\":" << (observed ? "true" : "false")
              << ",\"dmSize\":" << value.dmSize << ",\"dmDriverExtra\":" << value.dmDriverExtra
              << ",\"width\":" << value.dmPelsWidth << ",\"height\":" << value.dmPelsHeight
              << ",\"bits\":" << value.dmBitsPerPel << ",\"frequency\":" << value.dmDisplayFrequency << "}" << std::endl;
}
static int restore(DEVMODEW saved) {
    auto current = mode();
    traceStage("restore-observe-before");
    const bool observedBefore = EnumDisplaySettingsW(nullptr, ENUM_CURRENT_SETTINGS, &current);
    traceMode("restore-observed-before", current, observedBefore);
    const bool alreadyOriginal = observedBefore && sameMode(current, saved);
    LONG changeResult = DISP_CHANGE_SUCCESSFUL, resetResult = DISP_CHANGE_SUCCESSFUL;
    bool resetAttempted = false;
    if (!alreadyOriginal) {
        traceStage("restore-explicit-change-start");
        changeResult = ChangeDisplaySettingsExW(nullptr, &saved, nullptr, 0, nullptr);
        traceResult("restore-explicit-change-returned", changeResult);
        // A dynamic mode may already be back at its original value. Observe
        // before making another request, even if the driver returned failure.
        traceStage("restore-observe-after-explicit-change");
        const bool observedChanged = EnumDisplaySettingsW(nullptr, ENUM_CURRENT_SETTINGS, &current);
        traceMode("restore-observed-after-explicit-change", current, observedChanged);
        if (!observedChanged || !sameMode(current, saved)) {
            // Win32 documents NULL mode + zero flags as restoring the registry
            // default after a dynamic change. Verify that it is our saved mode.
            resetAttempted = true;
            traceStage("restore-default-reset-start");
            resetResult = ChangeDisplaySettingsExW(nullptr, nullptr, nullptr, 0, nullptr);
            traceResult("restore-default-reset-returned", resetResult);
        }
    }
    auto actual = mode();
    traceStage("restore-observe-final");
    const bool observedAfter = EnumDisplaySettingsW(nullptr, ENUM_CURRENT_SETTINGS, &actual);
    traceMode("restore-observed-final", actual, observedAfter);
    const bool restored = observedAfter && sameMode(actual, saved);
    std::cout << "{\"restored\":" << (restored ? "true" : "false")
              << ",\"alreadyOriginal\":" << (alreadyOriginal ? "true" : "false")
              << ",\"changeAttempted\":" << (!alreadyOriginal ? "true" : "false")
              << ",\"changeResult\":" << changeResult
              << ",\"defaultResetAttempted\":" << (resetAttempted ? "true" : "false")
              << ",\"defaultResetResult\":" << resetResult
              << ",\"observedAfter\":" << (observedAfter ? "true" : "false") << ",\"expected\":";
    writeMode(saved); std::cout << ",\"actual\":"; writeMode(actual); std::cout << "}" << std::endl;
    const int exitCode = restored ? 0 : ((changeResult != DISP_CHANGE_SUCCESSFUL || (resetAttempted && resetResult != DISP_CHANGE_SUCCESSFUL)) ? 8 : 9);
    traceResult("restore-complete", exitCode);
    return exitCode;
}
int main(int argc, char **argv) {
    const char *hosted = std::getenv("GITHUB_ACTIONS");
    if (!hosted || std::string(hosted) != "true") {
        std::cerr << "Display setup requires an isolated GitHub runner\n";
        return 2;
    }
    SetProcessDPIAware();
    // The receipt is private binary DEVMODE data, used only by this helper's
    // finally step. No user-controlled dimensions are reconstructed from JSON.
    if (argc == 3 && std::string(argv[1]) == "--restore") {
        traceStage("restore-read-start");
        auto saved = mode();
        std::ifstream input(argv[2], std::ios::binary);
        const bool opened = input.is_open();
        input.read(reinterpret_cast<char *>(&saved), sizeof(saved));
        const auto bytesRead = input.gcount();
        const bool complete = static_cast<bool>(input);
        const bool exactLength = complete && input.peek() == std::char_traits<char>::eof() && !input.bad();
        std::cerr << "{\"stage\":\"restore-read-result\",\"opened\":" << (opened ? "true" : "false")
                  << ",\"complete\":" << (complete ? "true" : "false") << ",\"exactLength\":" << (exactLength ? "true" : "false") << ",\"bytesRead\":" << bytesRead
                  << ",\"expectedBytes\":" << sizeof(saved) << ",\"dmSize\":" << saved.dmSize
                  << ",\"dmDriverExtra\":" << saved.dmDriverExtra
                  << ",\"minimumPublicBytes\":" << minimumPublicModeBytes
                  << ",\"publicHeaderValid\":" << (validSavedModeHeader(saved) ? "true" : "false") << "}" << std::endl;
        if (!exactLength || !validSavedModeHeader(saved)) {
            std::cout << "{\"restored\":false,\"stage\":\"read-saved-mode\",\"exitCode\":7,\"opened\":" << (opened ? "true" : "false")
                      << ",\"bytesRead\":" << bytesRead << ",\"expectedBytes\":" << sizeof(saved)
                      << ",\"exactLength\":" << (exactLength ? "true" : "false")
                      << ",\"dmSize\":" << saved.dmSize << ",\"dmDriverExtra\":" << saved.dmDriverExtra
                      << ",\"minimumPublicBytes\":" << minimumPublicModeBytes
                      << ",\"publicHeaderValid\":" << (validSavedModeHeader(saved) ? "true" : "false") << "}" << std::endl;
            traceResult("restore-complete", 7);
            return 7;
        }
        return restore(saved);
    }
    if (argc != 3 || std::string(argv[1]) != "--save") return 2;
    auto before = mode();
    if (!EnumDisplaySettingsW(nullptr, ENUM_CURRENT_SETTINGS, &before)) return 3;
    {
        std::ofstream output(argv[2], std::ios::binary | std::ios::trunc);
        if (!output.write(reinterpret_cast<const char *>(&before), sizeof(before))) return 7;
    }
    MONITORINFO primary{};
    if (!primaryMonitor(primary)) return 6;
    // A display-sized topmost borderless window can cover the taskbar. Do
    // not request an unsupported larger mode merely to enlarge rcWork.
    bool modeChanged = false;
    if (!fits(primary.rcMonitor)) {
        std::vector<DEVMODEW> candidates;
        for (DWORD i = 0;; ++i) {
            auto candidate = mode();
            if (!EnumDisplaySettingsW(nullptr, i, &candidate)) break;
            if (candidate.dmPelsWidth >= 1920 && candidate.dmPelsHeight >= 1080 && candidate.dmBitsPerPel >= 32)
                candidates.push_back(candidate);
        }
        std::sort(candidates.begin(), candidates.end(), [](const auto &a, const auto &b) {
            return static_cast<unsigned long long>(a.dmPelsWidth) * a.dmPelsHeight <
                   static_cast<unsigned long long>(b.dmPelsWidth) * b.dmPelsHeight;
        });
        bool changed = false;
        for (auto candidate : candidates) {
            if (ChangeDisplaySettingsExW(nullptr, &candidate, nullptr, CDS_TEST, nullptr) != DISP_CHANGE_SUCCESSFUL) continue;
            if (ChangeDisplaySettingsExW(nullptr, &candidate, nullptr, 0, nullptr) != DISP_CHANGE_SUCCESSFUL) continue;
            for (int poll = 0; poll < 20; ++poll) {
                if (primaryMonitor(primary) && fits(primary.rcMonitor)) { changed = true; modeChanged = true; break; }
                Sleep(100);
            }
            if (changed) break;
        }
        if (!changed) {
            std::cerr << "Hosted display offers no supported 1920x1080 full frame\n";
            return 4;
        }
    }
    auto after = mode();
    if (!EnumDisplaySettingsW(nullptr, ENUM_CURRENT_SETTINGS, &after) ||
        !primaryMonitor(primary) || !fits(primary.rcMonitor)) return 5;
    std::cout << "{\"savedBytes\":" << sizeof(before) << ",\"savedDmSize\":" << before.dmSize
              << ",\"savedDmDriverExtra\":" << before.dmDriverExtra
              << ",\"beforeWidth\":" << before.dmPelsWidth << ",\"beforeHeight\":" << before.dmPelsHeight
              << ",\"width\":" << after.dmPelsWidth << ",\"height\":" << after.dmPelsHeight
              << ",\"x\":" << primary.rcMonitor.left << ",\"y\":" << primary.rcMonitor.top
              << ",\"workAreaX\":" << primary.rcWork.left << ",\"workAreaY\":" << primary.rcWork.top
              << ",\"workAreaWidth\":" << primary.rcWork.right - primary.rcWork.left
              << ",\"workAreaHeight\":" << primary.rcWork.bottom - primary.rcWork.top
              << ",\"modeChanged\":" << (modeChanged ? "true" : "false")
              << ",\"requiresFullscreen\":" << (!fits(primary.rcWork) ? "true" : "false")
              << ",\"dpi\":" << GetDpiForSystem() << "}\n";
    return 0;
}
