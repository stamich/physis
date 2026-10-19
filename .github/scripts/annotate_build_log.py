"""Turn the interesting parts of a failed Gradle build log into GitHub annotations.

Collects compiler errors, failed tests, formatting violations and Gradle's
"What went wrong" sections, and prints them as at most ten ::error commands
(the per-step limit), so failures are visible in the PR checks and through
the GitHub API without opening the full log.

Usage: python3 .github/scripts/annotate_build_log.py build.log
"""

import os
import re
import sys

MAX_ANNOTATIONS = 10
MAX_CHARS = 6000


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def collect(lines: list[str]) -> dict[str, list[str]]:
    sections: dict[str, list[str]] = {
        "Compiler errors": [],
        "Failed tests": [],
        "Formatting": [],
        "What went wrong": [],
    }
    i = 0
    while i < len(lines):
        line = lines[i]
        if re.search(r"\[Error\]|^e: |error: ", line):
            sections["Compiler errors"].extend(lines[i : i + 4])
            i += 4
            continue
        if "==> X" in line or re.search(r"\bFAILED\b", line):
            sections["Failed tests"].extend(lines[i : i + 6])
            i += 6
            continue
        if "format violations" in line:
            j = i
            while j < len(lines) and j < i + 40 and lines[j].strip() != "":
                j += 1
            sections["Formatting"].extend(lines[i:j])
            i = j
            continue
        if line.startswith("* What went wrong:"):
            j = i
            while j < len(lines) and not lines[j].startswith("* Try:"):
                j += 1
            sections["What went wrong"].extend(lines[i:j])
            i = j
            continue
        i += 1
    return sections


def main() -> None:
    path = sys.argv[1]
    with open(path, encoding="utf-8", errors="replace") as f:
        lines = [l.rstrip("\n") for l in f]
    root = os.getcwd() + "/"
    lines = [l.replace(root, "") for l in lines]

    chunks: list[tuple[str, str]] = []
    for title, body in collect(lines).items():
        text = "\n".join(body).strip()
        while text:
            chunks.append((title, text[:MAX_CHARS]))
            text = text[MAX_CHARS:]
    if not chunks:
        chunks.append(("Build failed", "\n".join(lines[-60:])))

    for n, (title, text) in enumerate(chunks[:MAX_ANNOTATIONS], start=1):
        print(f"::error title={title} ({n})::{escape(text)}")


if __name__ == "__main__":
    main()
