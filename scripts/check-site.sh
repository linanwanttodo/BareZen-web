#!/usr/bin/env bash
# Guard the static documentation site (ADR-0052, ADR-0053, ADR-0054).
#
# site/ used to be a Maven module served by the framework, and its build ran the checks below as tests. It is now
# plain files deployed by a host that runs no code of ours (GitHub Pages), so nothing executes them unless this script
# does. Run it from CI and before a deploy: a link that resolves against a route table but not against a filesystem is
# exactly the failure a static host reports as a 404 to a reader.
set -euo pipefail

cd "$(dirname "$0")/.."

python3 - <<'PY'
import pathlib
import posixpath
import re
import sys

root = pathlib.Path("site")
problems = []

if not root.is_dir():
    print(f"FAIL: no {root}/ directory", file=sys.stderr)
    sys.exit(1)

pages = sorted(root.rglob("*.html"))
if not pages:
    print("FAIL: site/ holds no HTML pages", file=sys.stderr)
    sys.exit(1)

LINK = re.compile(r'(?:href|src)="([^"]+)"')
ABSOLUTE = re.compile(r'(?:href|src)="/')
HREFLANG = re.compile(r'hreflang="(en|zh)" href="([^"]+)"')

# 1. Every link resolves on disk. A site root of "/" is legal in a hand-written page and fatal under
#    /<repo>/site/, so absolute addresses are refused here rather than fixed at deploy time.
for page in pages:
    text = page.read_text(encoding="utf-8")
    if ABSOLUTE.search(text):
        problems.append(f"{page}: links out of the tree with an absolute \"/\" address; a static host serves this "
                        "site under /<repo>/site/, so every address must be relative")
    for target in LINK.findall(text):
        if target.startswith(("http:", "https:", "mailto:", "#")):
            continue
        resolved = pathlib.Path(posixpath.normpath(str(page.parent / target)))
        if not resolved.is_file():
            problems.append(f"{page}: {target} -> {resolved} does not exist")

# 2. Every page is reachable from one of the two landing pages by following internal links.
def reachable(start):
    seen, queue = set(), [start]
    while queue:
        current = queue.pop()
        if current in seen or not current.is_file():
            continue
        seen.add(current)
        text = current.read_text(encoding="utf-8")
        for target in LINK.findall(text):
            if target.startswith(("http:", "https:", "mailto:", "#")):
                continue
            candidate = pathlib.Path(posixpath.normpath(str(current.parent / target)))
            if candidate.suffix == ".html":
                queue.append(candidate)
    return seen

for landing in (root / "index.html", root / "zh" / "index.html"):
    reached = reachable(landing)
    subtree = [p for p in pages if str(p).startswith(str(landing.parent))]
    for page in subtree:
        if page not in reached:
            problems.append(f"{page} is not reachable from {landing}")

# 3. The two languages pair up page for page, and each page links exactly its counterpart.
for page in pages:
    relative = page.relative_to(root).as_posix()
    chinese = relative.startswith("zh/")
    counterpart = root / relative[3:] if chinese else root / "zh" / relative
    expected_language = "zh" if chinese else "en"
    other_language = "en" if chinese else "zh"
    if not counterpart.is_file():
        problems.append(f"{relative} has no counterpart at {('' if chinese else 'zh/') + relative}")
        continue
    text = page.read_text(encoding="utf-8")
    targets = {language: value for language, value in HREFLANG.findall(text)}
    expected_target = posixpath.relpath(str(counterpart), str(page.parent))
    if targets.get(other_language) != expected_target:
        problems.append(f'{relative}: offers {other_language} at "{targets.get(other_language)}", '
                        f'expected "{expected_target}"')
    if expected_language in targets:
        problems.append(f"{relative}: advertises its own language as an alternative")
    expected_lang_attribute = "zh-Hans" if chinese else "en"
    if f'<html lang="{expected_lang_attribute}">' not in text:
        problems.append(f"{relative}: <html lang> is not {expected_lang_attribute}")

# 4. One stylesheet, referenced by every page.
sheets = sorted(p.relative_to(root).as_posix() for p in (root / "assets").rglob("*.css"))
if sheets != ["assets/site.css"]:
    problems.append(f"site/assets holds {sheets}; the site is documented as one stylesheet")
for page in pages:
    if 'stylesheet" href="' not in page.read_text(encoding="utf-8"):
        problems.append(f"{page}: links no stylesheet")

for problem in problems:
    print(f"FAIL: {problem}", file=sys.stderr)

print(f"checked {len(pages)} pages, {len(sheets)} stylesheet(s)")
if problems:
    sys.exit(1)
print(f"OK: the static site is self-consistent ({len(pages)} pages in two languages, every link relative)")
PY
