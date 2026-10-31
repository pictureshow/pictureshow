import { defineConfig } from "@playwright/test";
export default defineConfig({
    testDir: "tests",
    fullyParallel: false,
    workers: 1,
    retries: 0,
    timeout: 60000,
    reporter: [["list"]],
    use: { baseURL: "http://127.0.0.1:4174", trace: "off", screenshot: "off" },
    projects: [
        { name: "chromium", use: { browserName: "chromium" } },
        { name: "webkit", use: { browserName: "webkit" } },
        { name: "firefox", use: { browserName: "firefox" } },
    ],
    webServer: {
        command:
            "npm run build && vite preview --host 127.0.0.1 --port 4174 --strictPort",
        url: "http://127.0.0.1:4174",
        reuseExistingServer: !!process.env.PW_REUSE_SERVER,
    },
});
