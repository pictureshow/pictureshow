#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Permit Weston's test-seat module to run without its in-process test runner.

Only the diagnostic module is patched; compositor/color handling is unchanged.
Use exclusively in an isolated, finite test compositor.
"""
from pathlib import Path
import sys
p=Path(sys.argv[1])/'tests/harness/weston-test.c'
s=p.read_text()
s=s.replace('wl_list_for_each_safe(bp, tmp, &tsd->breakpoints.list, link) {',
            'if (!tsd) return; /* External diagnostic test seat. */\n\twl_list_for_each_safe(bp, tmp, &tsd->breakpoints.list, link) {')
s=s.replace('assert(tsd->wl_client);', 'if (!tsd) return;\n\tassert(tsd->wl_client);')
s=s.replace('assert(!tsd->wl_client);\n\ttsd->wl_client = client;',
            'if (tsd) { assert(!tsd->wl_client); tsd->wl_client = client; }')
s=s.replace('data->wl_client = NULL;', 'if (data) data->wl_client = NULL;')
p.write_text(s)
