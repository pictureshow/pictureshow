import { defineConfig } from "vitest/config";
export default defineConfig({
    base: "./",
    server: { port: 4174, strictPort: true },
    test: {
        include: ["core/**/*.test.ts"],
        exclude: ["core/src/vectors.test.ts"],
    },
});
