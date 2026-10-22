#!/usr/bin/env python3
"""Render the launcher icon vector drawables to the 512x512 F-Droid store icon.

The output is a deterministic export of the existing artwork (ic_launcher_background
plus ic_launcher_foreground, unmasked); nothing is redesigned. Requires cairosvg:

    python3 -m venv /tmp/icon-venv && /tmp/icon-venv/bin/pip install cairosvg
    /tmp/icon-venv/bin/python -I scripts/render_fdroid_icon.py
"""
import pathlib
import sys
import xml.etree.ElementTree as ET

import cairosvg

ANDROID = "{http://schemas.android.com/apk/res/android}"
ROOT = pathlib.Path(__file__).resolve().parent.parent
DRAWABLES = ROOT / "app/src/main/res/drawable"
OUT = ROOT / "fastlane/metadata/android/en-US/images/icon.png"


def attr(node, name, default=None):
    return node.get(ANDROID + name, default)


def path_svg(node):
    fill = attr(node, "fillColor")
    stroke = attr(node, "strokeColor")
    parts = [f'd="{attr(node, "pathData")}"', f'fill="{fill or "none"}"']
    if stroke:
        parts.append(f'stroke="{stroke}" stroke-width="{attr(node, "strokeWidth", "1")}"')
        for android, svg in (("strokeLineCap", "stroke-linecap"), ("strokeLineJoin", "stroke-linejoin")):
            if attr(node, android):
                parts.append(f'{svg}="{attr(node, android)}"')
    return f"<path {' '.join(parts)}/>"


def element_svg(node):
    if node.tag == "path":
        return path_svg(node)
    if node.tag == "group":
        transform = (
            f'translate({attr(node, "translateX", "0")} {attr(node, "translateY", "0")}) '
            f'scale({attr(node, "scaleX", "1")} {attr(node, "scaleY", "1")})'
        )
        return f'<g transform="{transform}">{"".join(element_svg(c) for c in node)}</g>'
    sys.exit(f"Unsupported vector element: {node.tag}")


def drawable_svg(name):
    return "".join(element_svg(c) for c in ET.parse(DRAWABLES / name).getroot())


svg = (
    '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">'
    + drawable_svg("ic_launcher_background.xml")
    + drawable_svg("ic_launcher_foreground.xml")
    + "</svg>"
)
OUT.parent.mkdir(parents=True, exist_ok=True)
cairosvg.svg2png(bytestring=svg.encode(), write_to=str(OUT), output_width=512, output_height=512)
print(f"Wrote {OUT.relative_to(ROOT)}")
