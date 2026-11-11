// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
// Hosted smoke setup only. Dynamic settings; no registry writes or driver installs.
#define UNICODE
#define _UNICODE
#include <windows.h>
#include <cstdlib>
#include <iostream>
#include <vector>
#include <algorithm>

static DEVMODEW mode() {
    DEVMODEW result{};
    result.dmSize = sizeof(result);
    return result;
}
int main() {
    const char *hosted = std::getenv("GITHUB_ACTIONS");
    if (!hosted || std::string(hosted) != "true") {
        std::cerr << "Display setup requires an isolated GitHub runner\n";
        return 2;
    }
    SetProcessDPIAware();
    auto before = mode();
    if (!EnumDisplaySettingsW(nullptr, ENUM_CURRENT_SETTINGS, &before)) return 3;
    if (before.dmPelsWidth < 1920 || before.dmPelsHeight < 1080) {
        std::vector<DEVMODEW> candidates;
        for (DWORD i = 0;; ++i) {
            auto candidate = mode();
            if (!EnumDisplaySettingsW(nullptr, i, &candidate)) break;
            if (candidate.dmPelsWidth >= 1920 && candidate.dmPelsHeight >= 1080 && candidate.dmBitsPerPel >= 32)
                candidates.push_back(candidate);
        }
        std::sort(candidates.begin(), candidates.end(), [](const auto &a, const auto &b) {
            return a.dmPelsWidth * a.dmPelsHeight < b.dmPelsWidth * b.dmPelsHeight;
        });
        bool changed = false;
        for (auto candidate : candidates) {
            if (ChangeDisplaySettingsExW(nullptr, &candidate, nullptr, CDS_TEST, nullptr) != DISP_CHANGE_SUCCESSFUL) continue;
            if (ChangeDisplaySettingsExW(nullptr, &candidate, nullptr, 0, nullptr) == DISP_CHANGE_SUCCESSFUL) {
                changed = true;
                break;
            }
        }
        if (!changed) {
            std::cerr << "Hosted display offers no supported 1920x1080 mode\n";
            return 4;
        }
    }
    auto after = mode();
    if (!EnumDisplaySettingsW(nullptr, ENUM_CURRENT_SETTINGS, &after) ||
        after.dmPelsWidth < 1920 || after.dmPelsHeight < 1080) return 5;
    MONITORINFO primary{};
    primary.cbSize = sizeof(primary);
    if (!GetMonitorInfoW(MonitorFromPoint(POINT{0, 0}, MONITOR_DEFAULTTOPRIMARY), &primary)) return 6;
    std::cout << "{\"beforeWidth\":" << before.dmPelsWidth << ",\"beforeHeight\":" << before.dmPelsHeight
              << ",\"width\":" << after.dmPelsWidth << ",\"height\":" << after.dmPelsHeight
              << ",\"x\":" << primary.rcMonitor.left << ",\"y\":" << primary.rcMonitor.top
              << ",\"dpi\":" << GetDpiForSystem() << "}\n";
    return 0;
}
