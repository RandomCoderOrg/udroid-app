#!/usr/bin/env python3

import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location(
    "udroid_debug",
    Path(__file__).with_name("udroid-debug.py"),
)
UDROID_DEBUG = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(UDROID_DEBUG)


class UdroidDebugTest(unittest.TestCase):
    def test_finds_accessibility_node_center(self):
        document = """<hierarchy><node package="org.randomcoder.udroid.dev"
            content-desc="Collapse desktop controls"
            bounds="[963,132][1080,249]" /></hierarchy>"""

        self.assertEqual(
            (1021, 190),
            UDROID_DEBUG.accessibility_node_center(
                document,
                "Collapse desktop controls",
                "org.randomcoder.udroid.dev",
            ),
        )

    def test_missing_accessibility_node_returns_none(self):
        self.assertIsNone(
            UDROID_DEBUG.accessibility_node_center(
                '<hierarchy><node content-desc="Desktop settings" bounds="[0,0][1,1]" /></hierarchy>',
                "Collapse desktop controls",
            )
        )

    def test_ignores_another_apps_accessibility_node(self):
        self.assertIsNone(
            UDROID_DEBUG.accessibility_node_center(
                '<hierarchy><node package="other" content-desc="Collapse desktop controls" bounds="[0,0][10,10]" /></hierarchy>',
                "Collapse desktop controls",
                "org.randomcoder.udroid.dev",
            )
        )


if __name__ == "__main__":
    unittest.main()
