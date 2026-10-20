#!/usr/bin/env python3
"""Stage the documentation site's source (standard library only).

The site is built from `docs/` plus four files that live at the repository root (README, ARCHITECTURE, CHANGELOG, SECURITY).
Those files link to each other and to source files using repository-relative paths that are right on GitHub and wrong on the site.
This copies the Markdown into a staging directory and rewrites every relative link:
  * to a page that is part of the site  -> a path relative to that page's new location;
  * to anything else in the repository   -> an absolute GitHub URL (blob for a file, tree for a directory);
  * to nothing at all                    -> left alone, so the strict site build reports it.
Links inside code fences and inline code are never touched. Nothing is generated or invented: the staged files are the repository's own.

usage: prepare.py <repository root> <output directory> [repo url] [branch]
"""
import os
import re
import shutil
import sys
from pathlib import Path

ROOT_PAGES = {"README.md": "index.md", "ARCHITECTURE.md": "architecture.md", "CHANGELOG.md": "changelog.md", "SECURITY.md": "security.md"}
# One pass per (non-fenced) line: an inline code span of any backtick count is kept verbatim, otherwise a Markdown link target is rewritten.
INLINE = re.compile(r"(?P<code>(?P<ticks>`+).+?(?P=ticks))|(?P<open>\]\()(?P<target>[^)\s]+)(?P<close>\))")


def site_map(root: Path) -> dict:
    """Repository-relative path -> site-relative path, for every page that is part of the site."""
    pages = {src: dst for src, dst in ROOT_PAGES.items() if (root / src).is_file()}
    docs = root / "docs"
    if docs.is_dir():
        for p in sorted(docs.rglob("*.md")):
            # A README.md next to an index.md is only the "generated, do not edit" marker (docs/sample-output): index.md is the page.
            if p.name == "README.md" and (p.parent / "index.md").is_file():
                continue
            pages[p.relative_to(root).as_posix()] = p.relative_to(docs).as_posix()
    return pages


def rewrite(text: str, source: str, site: dict, root: Path, repo_url: str, branch: str) -> str:
    here = os.path.dirname(site[source])

    def fix(match):
        if match.group("code"):
            return match.group(0)
        target = match.group("target")
        if target.startswith(("http://", "https://", "mailto:", "#")):
            return match.group(0)
        path, _, fragment = target.partition("#")
        resolved = os.path.normpath(os.path.join(os.path.dirname(source), path)).replace(os.sep, "/")
        suffix = f"#{fragment}" if fragment else ""
        if resolved in site:
            new = os.path.relpath(site[resolved], here or ".").replace(os.sep, "/") + suffix
        elif (root / resolved).is_file():
            new = f"{repo_url}/blob/{branch}/{resolved}{suffix}"
        elif (root / resolved).is_dir():
            new = f"{repo_url}/tree/{branch}/{resolved.rstrip('/')}{suffix}"
        else:
            return match.group(0)
        return f"{match.group('open')}{new}{match.group('close')}"

    out, fenced = [], False
    for line in text.split("\n"):
        if line.lstrip().startswith("```"):
            fenced = not fenced
            out.append(line)
        else:
            out.append(line if fenced else INLINE.sub(fix, line))
    return "\n".join(out)


def stage(root: Path, out: Path, repo_url: str, branch: str) -> None:
    if out.exists():
        shutil.rmtree(out)
    site = site_map(root)
    for source, dest in site.items():
        target = out / dest
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(rewrite((root / source).read_text(encoding="utf-8"), source, site, root, repo_url, branch), encoding="utf-8")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    stage(Path(sys.argv[1]), Path(sys.argv[2]),
          sys.argv[3] if len(sys.argv) > 3 else "https://github.com/wakaleo/explico",
          sys.argv[4] if len(sys.argv) > 4 else "main")
