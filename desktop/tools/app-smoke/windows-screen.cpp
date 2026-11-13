// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
// Hosted smoke setup only. Dynamic settings; no registry writes or driver installs.
#define UNICODE
#define _UNICODE
#include <windows.h>
#include <cstdlib>
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
static bool fits(const MONITORINFO &monitor) {
    return monitor.rcWork.right - monitor.rcWork.left >= 1920 &&
           monitor.rcWork.bottom - monitor.rcWork.top >= 1080;
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
        auto saved = mode();
        std::ifstream input(argv[2], std::ios::binary);
        if (!input.read(reinterpret_cast<char *>(&saved), sizeof(saved)) ||
            saved.dmSize != sizeof(saved)) return 7;
        if (ChangeDisplaySettingsExW(nullptr, &saved, nullptr, 0, nullptr) != DISP_CHANGE_SUCCESSFUL) return 8;
        auto restored = mode();
        if (!EnumDisplaySettingsW(nullptr, ENUM_CURRENT_SETTINGS, &restored) ||
            restored.dmPelsWidth != saved.dmPelsWidth || restored.dmPelsHeight != saved.dmPelsHeight ||
            restored.dmBitsPerPel != saved.dmBitsPerPel || restored.dmDisplayFrequency != saved.dmDisplayFrequency) return 9;
        std::cout << "{\"restored\":true}\n";
        return 0;
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
    if (!fits(primary)) {
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
            // A 1920x1080 mode with a 48px taskbar does not fit a 1920x1080
            // window. Wait for the shell's work-area update, then measure it.
            for (int poll = 0; poll < 20; ++poll) {
                if (primaryMonitor(primary) && fits(primary)) { changed = true; break; }
                Sleep(100);
            }
            if (changed) break;
        }
        if (!changed) {
            ChangeDisplaySettingsExW(nullptr, &before, nullptr, 0, nullptr);
            std::cerr << "Hosted display offers no supported 1920x1080 usable work area\n";
            return 4;
        }
    }
    auto after = mode();
    if (!EnumDisplaySettingsW(nullptr, ENUM_CURRENT_SETTINGS, &after) ||
        !primaryMonitor(primary) || !fits(primary)) return 5;
    std::cout << "{\"beforeWidth\":" << before.dmPelsWidth << ",\"beforeHeight\":" << before.dmPelsHeight
              << ",\"width\":" << after.dmPelsWidth << ",\"height\":" << after.dmPelsHeight
              << ",\"x\":" << primary.rcMonitor.left << ",\"y\":" << primary.rcMonitor.top
              << ",\"workAreaX\":" << primary.rcWork.left << ",\"workAreaY\":" << primary.rcWork.top
              << ",\"workAreaWidth\":" << primary.rcWork.right - primary.rcWork.left
              << ",\"workAreaHeight\":" << primary.rcWork.bottom - primary.rcWork.top
              << ",\"dpi\":" << GetDpiForSystem() << "}\n";
    return 0;
}
