"""Tests for prepare.py (standard library only): python3 -m unittest discover -s scripts/docs"""
import os
import tempfile
import unittest
from pathlib import Path

import prepare


class RewriteLinks(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        for rel in ["README.md", "CHANGELOG.md", "CLAUDE.md", "docs/tutorial.md", "docs/showcase/every.md",
                    "src/main/Foo.kt", "samples/policies/a.rego"]:
            p = self.root / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text("x")
        self.site = prepare.site_map(self.root)

    def rewrite(self, source, text):
        return prepare.rewrite(text, source, self.site, self.root, "https://github.com/o/r", "main")

    def test_readme_becomes_the_home_page_and_docs_lose_their_prefix(self):
        self.assertEqual(self.site["README.md"], "index.md")
        self.assertEqual(self.site["docs/tutorial.md"], "tutorial.md")
        self.assertEqual(self.site["docs/showcase/every.md"], "showcase/every.md")
        self.assertEqual(self.site["CHANGELOG.md"], "changelog.md")

    def test_a_link_to_another_page_is_made_relative_to_the_new_location(self):
        self.assertEqual(self.rewrite("README.md", "[t](docs/tutorial.md)"), "[t](tutorial.md)")
        self.assertEqual(self.rewrite("docs/showcase/every.md", "[t](../tutorial.md#x)"), "[t](../tutorial.md#x)")
        self.assertEqual(self.rewrite("docs/tutorial.md", "[c](../CHANGELOG.md)"), "[c](changelog.md)")

    def test_a_link_to_a_repository_file_outside_the_site_becomes_a_github_blob_url(self):
        self.assertEqual(self.rewrite("docs/tutorial.md", "[k](../src/main/Foo.kt)"), "[k](https://github.com/o/r/blob/main/src/main/Foo.kt)")
        self.assertEqual(self.rewrite("README.md", "[c](CLAUDE.md#a)"), "[c](https://github.com/o/r/blob/main/CLAUDE.md#a)")

    def test_a_link_to_a_directory_becomes_a_tree_url(self):
        self.assertEqual(self.rewrite("docs/tutorial.md", "[s](../samples/policies)"), "[s](https://github.com/o/r/tree/main/samples/policies)")

    def test_external_anchor_and_mailto_links_are_untouched(self):
        for link in ["https://x.dev/a", "#local", "mailto:a@b.c"]:
            self.assertEqual(self.rewrite("README.md", f"[a]({link})"), f"[a]({link})")

    def test_links_inside_code_fences_and_inline_code_are_untouched(self):
        text = "```\n[t](docs/tutorial.md)\n```\nand `[t](docs/tutorial.md)` and [t](docs/tutorial.md)"
        self.assertEqual(self.rewrite("README.md", text), "```\n[t](docs/tutorial.md)\n```\nand `[t](docs/tutorial.md)` and [t](tutorial.md)")

    def test_a_double_backtick_span_containing_backticks_is_code_too(self):
        text = "see `` rule [`t`](#...) `` and [t](docs/tutorial.md)"
        self.assertEqual(self.rewrite("README.md", text), "see `` rule [`t`](#...) `` and [t](tutorial.md)")

    def test_a_readme_beside_an_index_is_a_marker_and_not_a_page(self):
        (self.root / "docs/sample-output").mkdir()
        (self.root / "docs/sample-output/README.md").write_text("x")
        (self.root / "docs/sample-output/index.md").write_text("x")
        site = prepare.site_map(self.root)
        self.assertNotIn("docs/sample-output/README.md", site)
        self.assertIn("docs/sample-output/index.md", site)

    def test_a_link_to_nothing_is_left_alone_so_the_strict_build_reports_it(self):
        self.assertEqual(self.rewrite("README.md", "[m](docs/missing.md)"), "[m](docs/missing.md)")


class Stage(unittest.TestCase):
    def test_stages_markdown_only_and_never_binary_decks(self):
        root = Path(tempfile.mkdtemp())
        (root / "docs").mkdir()
        for name in ["a.md", "deck.pdf", "deck.pptx"]:
            (root / "docs" / name).write_text("x")
        for name in ["README.md", "CHANGELOG.md", "ARCHITECTURE.md", "SECURITY.md"]:
            (root / name).write_text("x")
        out = Path(tempfile.mkdtemp())
        prepare.stage(root, out, "https://github.com/o/r", "main")
        self.assertEqual(sorted(p.name for p in out.rglob("*") if p.is_file()),
                         ["a.md", "architecture.md", "changelog.md", "index.md", "security.md"])


if __name__ == "__main__":
    unittest.main()
