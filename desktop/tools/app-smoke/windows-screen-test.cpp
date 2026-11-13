// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
// Run on an isolated Windows GitHub runner after the real save/restore probe:
// cl /EHsc /std:c++17 windows-screen-test.cpp /link user32.lib
// windows-screen-test.exe <saved-mode.bin> <disposable-output-prefix>
#define main displayHelperMain
#include "windows-screen.cpp"
#undef main

int main(int argc, char **argv) {
    const char *hosted = std::getenv("GITHUB_ACTIONS");
    if (!hosted || std::string(hosted) != "true" || argc != 3) return 2;
    auto saved = mode();
    std::ifstream input(argv[1], std::ios::binary);
    if (!input.read(reinterpret_cast<char *>(&saved), sizeof(saved))) return 1;
    auto header = saved;
    header.dmSize = static_cast<WORD>(minimumPublicModeBytes);
    header.dmDriverExtra = 0;
    if (!validSavedModeHeader(header)) return 1;
    header.dmSize = sizeof(header);
    if (!validSavedModeHeader(header)) return 1;
    int passed = 0;
    for (int scenario = 0; scenario < 4; ++scenario) {
        auto invalid = saved;
        size_t bytes = sizeof(invalid);
        const char *name;
        switch (scenario) {
            case 0: name = "public-prefix-too-short"; invalid.dmSize = static_cast<WORD>(minimumPublicModeBytes - 1); break;
            case 1: name = "public-prefix-over-capacity"; invalid.dmSize = sizeof(invalid) + 1; break;
            case 2: name = "unstored-private-data"; invalid.dmDriverExtra = 1; break;
            default: name = "truncated-receipt"; bytes -= 1; break;
        }
        std::string path = std::string(argv[2]) + "-" + name + ".bin";
        {
            std::ofstream output(path, std::ios::binary | std::ios::trunc);
            if (!output.write(reinterpret_cast<const char *>(&invalid), bytes)) return 1;
        }
        char command[] = "display-helper";
        char restoreOption[] = "--restore";
        char *arguments[] = {command, restoreOption, path.data()};
        const int result = displayHelperMain(3, arguments);
        std::cout << "{\"control\":\"" << name << "\",\"nativeExitCode\":" << result
                  << ",\"expectedExitCode\":7}" << std::endl;
        if (result != 7) return 1;
        ++passed;
    }
    std::cout << "{\"passed\":true,\"negativeControls\":" << passed
              << ",\"minimumPublicBytes\":" << minimumPublicModeBytes
              << ",\"capacityBytes\":" << sizeof(saved) << "}" << std::endl;
    return 0;
}
