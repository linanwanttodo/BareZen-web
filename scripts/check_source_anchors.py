#!/usr/bin/env python3
import pathlib
import re
import sys


ANCHOR = re.compile(r"\[([^\]]+)\]\(([^)]+\.java)#L(\d+)\)")
BRACE_ONLY = {"{", "}", "};", "},", ");"}


def check(root):
    problems = []
    count = 0
    for directory in ("docs", "tutorial-docs", "site"):
        base = root / directory
        if not base.is_dir():
            continue
        for document in sorted(base.rglob("*")):
            if document.suffix not in {".md", ".html"}:
                continue
            for document_line, text in enumerate(document.read_text(encoding="utf-8").splitlines(), 1):
                for match in ANCHOR.finditer(text):
                    count += 1
                    label, target, line_number = match.group(1), match.group(2), int(match.group(3))
                    source = (document.parent / target).resolve()
                    location = f"{document.relative_to(root)}:{document_line}"
                    if not source.is_relative_to(root):
                        problems.append(f"{location}: {target} points outside the repository")
                        continue
                    if not source.is_file():
                        problems.append(f"{location}: {source.relative_to(root)} does not exist")
                        continue
                    lines = source.read_text(encoding="utf-8").splitlines()
                    if line_number < 1 or line_number > len(lines):
                        problems.append(
                            f"{location}: {source.relative_to(root)}:{line_number} is out of range"
                        )
                        continue
                    label_line = re.search(r":(\d+)$", label)
                    if label_line and int(label_line.group(1)) != line_number:
                        problems.append(
                            f"{location}: label says line {label_line.group(1)} but link says line {line_number}"
                        )
                        continue
                    actual = lines[line_number - 1].strip()
                    if not actual:
                        problems.append(f"{location}: {source.relative_to(root)}:{line_number} is a blank line")
                    elif actual.startswith(("/*", "*", "//")):
                        problems.append(f"{location}: {source.relative_to(root)}:{line_number} is a comment line")
                    elif actual in BRACE_ONLY:
                        problems.append(f"{location}: {source.relative_to(root)}:{line_number} is a brace-only line")
    return count, problems


def main():
    root = pathlib.Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parent.parent
    count, problems = check(root)
    for problem in problems:
        print(f"FAIL: {problem}", file=sys.stderr)
    noun = "anchor" if count == 1 else "anchors"
    print(f"checked {count} Java source {noun}")
    if problems:
        return 1
    print("OK: every Java source anchor resolves to a code line")
    return 0


if __name__ == "__main__":
    sys.exit(main())
