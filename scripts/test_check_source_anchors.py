import os
import pathlib
import subprocess
import sys
import tempfile
import unittest


SCRIPT = pathlib.Path(__file__).with_name("check_source_anchors.py")


class SourceAnchorCheckTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        (self.root / "docs").mkdir()
        (self.root / "framework").mkdir()
        self.source = self.root / "framework" / "Example.java"
        self.source.write_text("class Example {\n\n// comment\n * javadoc\n}\n}\n", encoding="utf-8")
        self.document = self.root / "docs" / "guide.md"

    def run_check(self):
        return subprocess.run(
            [sys.executable, str(SCRIPT), str(self.root)],
            capture_output=True,
            text=True,
            check=False,
        )

    def write_anchor(self, label="Example.java:1", target="Example.java#L1"):
        self.document.write_text(f"[{label}](../framework/{target})\n", encoding="utf-8")

    def test_accepts_an_anchor_to_a_code_line(self):
        self.write_anchor()

        result = self.run_check()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("checked 1 Java source anchor", result.stdout)

    def test_accepts_a_file_label_without_a_line_number(self):
        self.write_anchor(label="Example.java")

        result = self.run_check()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("checked 1 Java source anchor", result.stdout)

    def test_rejects_a_missing_source_file(self):
        self.write_anchor(target="Missing.java#L1")

        result = self.run_check()

        self.assertEqual(result.returncode, 1)
        self.assertIn("does not exist", result.stderr)

    def test_rejects_an_out_of_range_line(self):
        self.write_anchor(label="Example.java:99", target="Example.java#L99")

        result = self.run_check()

        self.assertEqual(result.returncode, 1)
        self.assertIn("out of range", result.stderr)

    def test_rejects_a_zero_line(self):
        self.write_anchor(label="Example.java:0", target="Example.java#L0")

        result = self.run_check()

        self.assertEqual(result.returncode, 1, f"{result.stdout}\n{result.stderr}")
        self.assertIn("out of range", result.stderr)

    def test_rejects_a_label_that_disagrees_with_the_link(self):
        self.write_anchor(label="Example.java:2", target="Example.java#L1")

        result = self.run_check()

        self.assertEqual(result.returncode, 1)
        self.assertIn("label says line 2", result.stderr)

    def test_rejects_a_method_label_that_is_not_on_the_target_line(self):
        for label in ("actual", "actual():1"):
            with self.subTest(label=label):
                self.write_anchor(label=label)

                result = self.run_check()

                self.assertEqual(result.returncode, 1, f"{result.stdout}\n{result.stderr}")
                self.assertIn("method label 'actual'", result.stderr)

    def test_rejects_a_blank_target_line(self):
        self.write_anchor(label="Example.java:2", target="Example.java#L2")

        result = self.run_check()

        self.assertEqual(result.returncode, 1)
        self.assertIn("blank line", result.stderr)

    def test_rejects_a_comment_target_line(self):
        for line in (3, 4):
            with self.subTest(line=line):
                self.write_anchor(label=f"Example.java:{line}", target=f"Example.java#L{line}")

                result = self.run_check()

                self.assertEqual(result.returncode, 1, f"{result.stdout}\n{result.stderr}")
                self.assertIn("comment line", result.stderr)

    def test_rejects_a_brace_only_target_line(self):
        for line in (5, 6):
            with self.subTest(line=line):
                self.write_anchor(label=f"Example.java:{line}", target=f"Example.java#L{line}")

                result = self.run_check()

                self.assertEqual(result.returncode, 1, f"{result.stdout}\n{result.stderr}")
                self.assertIn("brace-only line", result.stderr)

    def test_rejects_a_source_outside_the_repository(self):
        outside_directory = tempfile.TemporaryDirectory()
        self.addCleanup(outside_directory.cleanup)
        outside = pathlib.Path(outside_directory.name) / "Outside.java"
        outside.write_text("class Outside {}\n", encoding="utf-8")
        target = pathlib.Path(os.path.relpath(outside, self.document.parent)).as_posix()
        self.document.write_text(f"[Outside.java:1]({target}#L1)\n", encoding="utf-8")

        result = self.run_check()

        self.assertEqual(result.returncode, 1)
        self.assertIn("outside the repository", result.stderr)


if __name__ == "__main__":
    unittest.main()
