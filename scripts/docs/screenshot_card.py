#!/usr/bin/env python3
"""Regenerates docs/images/rendered-card.png: a screenshot of one generated control card as a reader sees it in HTML.

explico itself writes Markdown only. This script renders the card from docs/sample-output with a CommonMark renderer, wraps it in the docs site's
stylesheet and screenshots it with headless Chrome. It is a manual, occasional step (the image is NOT drift-checked by CI): run it after
`./gradlew generateSampleDocs` changes the card, then review the picture.

  python3 -m venv /tmp/shot && /tmp/shot/bin/pip install markdown-it-py pillow mkdocs==1.6.1 mkdocs-material==9.7.7
  python3 scripts/docs/prepare.py . build/site-src && /tmp/shot/bin/mkdocs build -d build/site   # the site's stylesheet
  /tmp/shot/bin/python scripts/docs/screenshot_card.py build/site docs/images/rendered-card.png [path-to-chrome]

Needs markdown-it-py and Pillow. The card is the one whose anchor is rel-001 in release-approvals.md.
"""
import glob, re, subprocess, sys, tempfile
from pathlib import Path

from markdown_it import MarkdownIt
from PIL import Image, ImageChops

site = Path(sys.argv[1])
out = Path(sys.argv[2])
chrome = sys.argv[3] if len(sys.argv) > 3 else "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

source = Path("docs/sample-output/release-approvals.md").read_text()
start = source.index('<a id="rel-001"></a>')
card = source[start:].split("\n---", 1)[0].replace('<a id="rel-001"></a>\n', "")
body = MarkdownIt("commonmark").enable("table").render(card)

page = (site / "sample-output/release-approvals/index.html").read_text()
css = "".join(re.findall(r'<link[^>]+stylesheet[^>]*>', page))
nested = ".md-typeset ul ul{margin-left:1.6em !important;list-style:circle}.md-typeset ul ul li{margin-bottom:.15em}"
html = (f'<!doctype html><html><head><meta charset="utf-8">{css}<style>body{{background:#fff}}.wrap{{width:880px;padding:28px 36px}}{nested}</style></head>'
        f'<body><div class="wrap md-typeset">{body}</div></body></html>')

# The stylesheet links are relative to the page's own directory, so the temporary page sits beside it.
shot_page = site / "sample-output/release-approvals/_card.html"
shot_page.write_text(html)
raw = Path(tempfile.mkdtemp()) / "raw.png"
try:
    subprocess.run([chrome, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=2", "--window-size=960,4200",
                    f"--screenshot={raw}", shot_page.resolve().as_uri()], check=True, capture_output=True)
finally:
    shot_page.unlink()

image = Image.open(raw).convert("RGB")
left, top, right, bottom = ImageChops.difference(image, Image.new("RGB", image.size, (255, 255, 255))).getbbox()
image = image.crop((max(left - 40, 0), max(top - 30, 0), min(right + 40, image.width), min(bottom + 40, image.height)))
image = image.resize((image.width * 3 // 4, image.height * 3 // 4), Image.LANCZOS)
out.parent.mkdir(parents=True, exist_ok=True)
image.save(out, optimize=True)
print(f"wrote {out} ({image.width}x{image.height})")
