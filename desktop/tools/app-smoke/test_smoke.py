# SPDX-License-Identifier: MPL-2.0
# Copyright (c) Legitimate LLC.
"""Negative controls for the E2E verdict, independent of the app implementation."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import threading
import os
import tempfile
import time

spec = importlib.util.spec_from_file_location("app_smoke", Path(__file__).with_name("run.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class SmokeVerdictTest(unittest.TestCase):
    def receipt(self):
        return {"pass": True, "checks": dict.fromkeys(smoke.REQUIRED_CHECKS, True)}

    def samples(self):
        return [{"itemId": "fixture-movie", "positionTicks": 120_000_000},
                {"itemId": "fixture-movie", "positionTicks": 150_000_000},
                {"itemId": "archive", "positionTicks": 0, "playMethod": "Transcode"},
                {"itemId": "ep1", "positionTicks": 1_750_000_000},
                {"itemId": "ep2", "positionTicks": 0}]

    def validate(self, receipt, samples, approved=True, encoder_samples=None, cleanup=None):
        return smoke.validate_receipt(receipt, samples, approved,
                                      {"archiveMovieId": "archive", "episodeIds": ["ep1", "ep2"]},
                                      encoder_samples if encoder_samples is not None else [{"encoderCount": 1}],
                                      cleanup if cleanup is not None else {"activePlayingItems": 0, "encoderCount": 0})

    def test_complete_flow_and_independent_progress_pass(self):
        result = self.validate(self.receipt(), self.samples())
        self.assertTrue(result["pass"])
        self.assertEqual(5, result["sessionSampleCount"])

    def test_pass_boolean_alone_is_insufficient(self):
        with self.assertRaisesRegex(RuntimeError, "named checks"):
            self.validate({"pass": True}, self.samples())

    def test_every_required_flow_is_individually_required(self):
        for check in smoke.REQUIRED_CHECKS:
            with self.subTest(check=check):
                receipt = self.receipt()
                del receipt["checks"][check]
                with self.assertRaisesRegex(RuntimeError, check):
                    self.validate(receipt, self.samples())

    def test_truthy_non_boolean_check_is_rejected(self):
        receipt = self.receipt()
        receipt["checks"]["playback"] = "true"
        with self.assertRaisesRegex(RuntimeError, "playback"):
            self.validate(receipt, self.samples())

    def test_failed_app_cannot_pass_server_observations(self):
        receipt = self.receipt()
        receipt["pass"] = False
        with self.assertRaisesRegex(RuntimeError, "did not pass"):
            self.validate(receipt, self.samples())

    def test_quick_connect_requires_actual_server_approval(self):
        with self.assertRaisesRegex(RuntimeError, "Quick Connect"):
            self.validate(self.receipt(), self.samples(), False)

    def test_no_playback_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "no active"):
            self.validate(self.receipt(), [])

    def test_start_at_resume_without_progress_is_rejected(self):
        samples = [{"itemId": "a", "positionTicks": 120_000_000}] * 5
        with self.assertRaisesRegex(RuntimeError, "no advancing"):
            self.validate(self.receipt(), samples)

    def test_different_item_resume_positions_do_not_invent_progress(self):
        samples = [{"itemId": "a", "positionTicks": 0}, {"itemId": "b", "positionTicks": 120_000_000}]
        with self.assertRaisesRegex(RuntimeError, "no advancing"):
            self.validate(self.receipt(), samples)

    def test_direct_play_cannot_pass_real_conversion_check(self):
        samples = self.samples()
        samples[2]["playMethod"] = "DirectPlay"
        with self.assertRaisesRegex(RuntimeError, "transcoding session"):
            self.validate(self.receipt(), samples)

    def test_converting_an_unrelated_movie_cannot_pass(self):
        samples = self.samples()
        samples[2]["itemId"] = "unrelated"
        with self.assertRaisesRegex(RuntimeError, "transcoding session"):
            self.validate(self.receipt(), samples)

    def test_negotiated_conversion_without_encoder_cannot_pass(self):
        with self.assertRaisesRegex(RuntimeError, "no FFmpeg"):
            self.validate(self.receipt(), self.samples(), encoder_samples=[{"encoderCount": 0}])

    def test_unstopped_encoding_cannot_pass(self):
        with self.assertRaisesRegex(RuntimeError, "after Stop"):
            self.validate(self.receipt(), self.samples(), cleanup={"activePlayingItems": 0, "encoderCount": 1})

    def test_unstopped_playback_cannot_pass(self):
        with self.assertRaisesRegex(RuntimeError, "after Stop"):
            self.validate(self.receipt(), self.samples(), cleanup={"activePlayingItems": 1, "encoderCount": 0})

    def test_next_button_without_near_end_episode_cannot_pass_autoplay(self):
        samples = self.samples()
        samples[3]["positionTicks"] = 50_000_000
        with self.assertRaisesRegex(RuntimeError, "near-end"):
            self.validate(self.receipt(), samples)

    def test_episode_two_before_episode_one_near_end_cannot_pass(self):
        samples = self.samples()
        samples[3], samples[4] = samples[4], samples[3]
        with self.assertRaisesRegex(RuntimeError, "near-end"):
            self.validate(self.receipt(), samples)

    def test_api_refuses_non_owned_network(self):
        for server in ["https://127.0.0.1", "http://localhost:8096", "http://192.0.2.1:8096"]:
            with self.subTest(server=server), self.assertRaises(ValueError):
                smoke.API(server)

    def test_failure_diagnostics_strip_urls_tokens_and_native_paths(self):
        raw = 'Failed https://example.invalid/video?api_key=abc Token=secret-value file C:\\fixture\\media\\film.mkv /tmp/fixture/media/film.mkv password="pw"'
        cleaned = smoke.sanitized(raw)
        for secret in ["example.invalid", "abc", "secret-value", "film.mkv", '"pw"']:
            self.assertNotIn(secret, cleaned)
        self.assertIn("Failed", cleaned)

    def test_encoder_observer_samples_concurrently_and_stops(self):
        sampled = threading.Event()
        def observe(_api):
            sampled.set()
            return 1
        api = type("OwnedServer", (), {"fixture_container": "owned-fixture"})()
        observer = smoke.EncoderObserver(api)
        with patch.object(smoke, "encoding_process_count", observe):
            try:
                observer.start()
                self.assertTrue(sampled.wait(timeout=2))
            finally:
                observer.stop()
        self.assertFalse(observer.thread.is_alive())
        self.assertIsNone(observer.error)
        self.assertTrue(any(sample["encoderCount"] == 1 for sample in observer.samples))

    @unittest.skipUnless(os.name == "posix", "Optional GUI coordination uses POSIX flock")
    def test_busy_gui_lock_fails_before_app_launch_by_default(self):
        import fcntl
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "gui.lock"
            with path.open("a+b") as owner:
                fcntl.flock(owner.fileno(), fcntl.LOCK_EX)
                launched = False
                with self.assertRaisesRegex(RuntimeError, "app was not launched"):
                    with smoke.gui_lease(path):
                        launched = True
                self.assertFalse(launched)

    @unittest.skipUnless(os.name == "posix", "Optional GUI coordination uses POSIX flock")
    def test_explicit_finite_gui_wait_acquires_after_owner_releases(self):
        import fcntl
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "gui.lock"
            with path.open("a+b") as owner:
                fcntl.flock(owner.fileno(), fcntl.LOCK_EX)
                def release():
                    time.sleep(.1)
                    fcntl.flock(owner.fileno(), fcntl.LOCK_UN)
                releaser = threading.Thread(target=release)
                releaser.start()
                launches = 0
                try:
                    with smoke.gui_lease(path, wait_seconds=2):
                        launches += 1
                finally:
                    releaser.join(timeout=2)
                self.assertEqual(1, launches)
                self.assertFalse(releaser.is_alive())

    def test_fixture_library_uses_explicit_path_and_confirms_registration(self):
        class FixtureAPI:
            def request(self, method, endpoint, body=None, query=None):
                if endpoint == "/Environment/DirectoryContents":
                    return [{"Name": "Signal One", "Type": "Directory"}]
                if method == "POST":
                    self.registered = query.get("paths")
                    return None
                return [{"Name": "Movies", "Locations": [self.registered] if self.registered else []}]
        api = FixtureAPI()
        smoke.add_fixture_library(api, "Movies", "movies", "/fixture/movies")
        self.assertEqual("/fixture/movies", api.registered)
        self.assertTrue(api.fixture_library_receipts[0]["registeredLocation"])

    def test_fixture_empty_virtual_folder_fails_before_scan_timeout(self):
        class FixtureAPI:
            def request(self, method, endpoint, body=None, query=None):
                if endpoint == "/Environment/DirectoryContents":
                    return [{"Name": "Signal One", "Type": "Directory"}]
                return [{"Name": "Movies", "Locations": []}] if method == "GET" else None
        with self.assertRaisesRegex(RuntimeError, "did not retain"):
            smoke.add_fixture_library(FixtureAPI(), "Movies", "movies", "/fixture/movies")


if __name__ == "__main__":
    unittest.main()
