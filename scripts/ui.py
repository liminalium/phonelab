#!/usr/bin/env python3
"""Find a view in a uiautomator XML dump (stdin) and print the centre of its bounds.

Usage: ui.py text EXACT | contains PART | id RESOURCE_ID_SUFFIX
Exits 1 when nothing matches.
"""
import re
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    mode, needle = sys.argv[1], sys.argv[2]
    try:
        root = ET.fromstring(sys.stdin.read())
    except ET.ParseError:
        return 1
    for node in root.iter("node"):
        text = node.get("text") or node.get("content-desc") or ""
        rid = node.get("resource-id") or ""
        if (
            (mode == "text" and text == needle)
            or (mode == "contains" and needle in text)
            or (mode == "id" and rid.endswith(needle))
        ):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
            print((x1 + x2) // 2, (y1 + y2) // 2)
            return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
