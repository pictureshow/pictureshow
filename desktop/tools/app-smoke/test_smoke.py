# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Negative controls for the E2E verdict, independent of the app implementation."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("app_smoke", Path(__file__).with_name("run.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class SmokeVerdictTest(unittest.TestCase):
    def receipt(self):
        return {"pass": True, "checks": dict.fromkeys(smoke.REQUIRED_CHECKS, True)}

    def samples(self):
        return [{"itemId": "fixture-movie", "positionTicks": 120_000_000},
                {"itemId": "fixture-movie", "positionTicks": 150_000_000}]

    def test_complete_flow_and_independent_progress_pass(self):
        result = smoke.validate_receipt(self.receipt(), self.samples(), True)
        self.assertTrue(result["pass"])
        self.assertEqual(2, result["sessionSampleCount"])

    def test_pass_boolean_alone_is_insufficient(self):
        with self.assertRaisesRegex(RuntimeError, "named checks"):
            smoke.validate_receipt({"pass": True}, self.samples(), True)

    def test_every_required_flow_is_individually_required(self):
        for check in smoke.REQUIRED_CHECKS:
            with self.subTest(check=check):
                receipt = self.receipt()
                del receipt["checks"][check]
                with self.assertRaisesRegex(RuntimeError, check):
                    smoke.validate_receipt(receipt, self.samples(), True)

    def test_truthy_non_boolean_check_is_rejected(self):
        receipt = self.receipt()
        receipt["checks"]["playback"] = "true"
        with self.assertRaisesRegex(RuntimeError, "playback"):
            smoke.validate_receipt(receipt, self.samples(), True)

    def test_failed_app_cannot_pass_server_observations(self):
        receipt = self.receipt()
        receipt["pass"] = False
        with self.assertRaisesRegex(RuntimeError, "did not pass"):
            smoke.validate_receipt(receipt, self.samples(), True)

    def test_quick_connect_requires_actual_server_approval(self):
        with self.assertRaisesRegex(RuntimeError, "Quick Connect"):
            smoke.validate_receipt(self.receipt(), self.samples(), False)

    def test_no_playback_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "no active"):
            smoke.validate_receipt(self.receipt(), [], True)

    def test_start_at_resume_without_progress_is_rejected(self):
        samples = [{"itemId": "a", "positionTicks": 120_000_000}] * 5
        with self.assertRaisesRegex(RuntimeError, "no advancing"):
            smoke.validate_receipt(self.receipt(), samples, True)

    def test_different_item_resume_positions_do_not_invent_progress(self):
        samples = [{"itemId": "a", "positionTicks": 0}, {"itemId": "b", "positionTicks": 120_000_000}]
        with self.assertRaisesRegex(RuntimeError, "no advancing"):
            smoke.validate_receipt(self.receipt(), samples, True)

    def test_api_refuses_non_owned_network(self):
        for server in ["https://127.0.0.1", "http://localhost:8096", "http://192.0.2.1:8096"]:
            with self.subTest(server=server), self.assertRaises(ValueError):
                smoke.API(server)


if __name__ == "__main__":
    unittest.main()
