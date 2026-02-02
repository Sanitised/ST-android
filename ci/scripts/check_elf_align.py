#!/usr/bin/env python3
import re
import subprocess
import sys

if len(sys.argv) < 3:
    print("Usage: check_elf_align.py <llvm-readelf> <elf> [<elf>...]")
    sys.exit(1)

readelf = sys.argv[1]
files = sys.argv[2:]
min_align = 0x4000
pattern = re.compile(r"^\s*LOAD\s+.*\s(0x[0-9a-fA-F]+)\s*$")

for path in files:
    out = subprocess.check_output([readelf, "-l", path], text=True)
    for line in out.splitlines():
        m = pattern.match(line)
        if not m:
            continue
        align = int(m.group(1), 16)
        if align < min_align:
            print(f"ERROR: {path} has LOAD align {hex(align)} (< 0x4000)")
            sys.exit(1)
