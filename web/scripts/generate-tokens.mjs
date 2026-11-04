/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
const file = resolve(
    process.env.PICTURESHOW_SPEC ?? "../spec",
    "design/tokens.json",
);
const tokens = JSON.parse(readFileSync(file, "utf8")).tokens;
const names = Object.keys(tokens).filter((name) =>
    ["color", "dimension", "number", "duration"].includes(tokens[name].type),
);
writeFileSync(
    "src/tokens.css",
    "/* Generated from spec/design/tokens.json; regenerate with npm run tokens. */\n:root {\n" +
        names
            .map((name) => {
                const t = tokens[name];
                if (!t) throw new Error("Missing design token " + name);
                return `  --${name.replaceAll(".", "-")}: ${t.value}${t.unit === "dp" ? "px" : t.unit === "ms" ? "ms" : ""};`;
            })
            .join("\n") +
        "\n}\n",
);
