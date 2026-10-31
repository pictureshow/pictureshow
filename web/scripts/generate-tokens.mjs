/* SPDX-License-Identifier: MPL-2.0 — Copyright (c) Legitimate LLC */
import { readFileSync,writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
const file=resolve(process.env.PICTURESHOW_SPEC??'../spec','design/tokens.json');
const tokens=JSON.parse(readFileSync(file,'utf8')).tokens;
const names=['color.app.window','color.app.accent','color.app.muted','color.app.field','color.app.artworkPlaceholder','color.tv.primary','color.tv.onSurface','color.tv.border','focus.tv.fieldRingColor','focus.tv.fieldRingWidth','radius.tv.card'];
writeFileSync('src/tokens.css','/* Generated from spec/design/tokens.json; regenerate with npm run tokens. */\n:root {\n'+names.map(name=>{const t=tokens[name];if(!t)throw new Error('Missing design token '+name);return `  --${name.replaceAll('.','-')}: ${t.value}${t.unit==='dp'?'px':''};`}).join('\n')+'\n}\n');
