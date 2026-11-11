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
import json

spec = importlib.util.spec_from_file_location("app_smoke", Path(__file__).with_name("run.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)
display_spec = importlib.util.spec_from_file_location("macos_smoke_display", Path(__file__).with_name("macos-run.py"))
display = importlib.util.module_from_spec(display_spec)
display_spec.loader.exec_module(display)


class DisplayBoundsTest(unittest.TestCase):
    def receipt(self):
        return {"phase": "virtual", "online": 1, "mirrored": 0, "main": 0,
                "width": 2048, "height": 1280, "x": 1074, "y": -30}

    def test_virtual_origin_is_preserved(self):
        self.assertEqual((1074, -30), display.smoke_origin(self.receipt()))

    def test_clipped_or_physical_display_is_rejected(self):
        for field, value in [("width", 1024), ("height", 768), ("main", 1),
                             ("mirrored", 1), ("online", 0), ("phase", "before")]:
            with self.subTest(field=field):
                receipt = self.receipt()
                receipt[field] = value
                with self.assertRaisesRegex(RuntimeError, "virtual display"):
                    display.smoke_origin(receipt)


class NativeCloseVerdictTest(unittest.TestCase):
    def test_normal_smoke_does_not_require_optional_close_receipts(self):
        with tempfile.TemporaryDirectory() as folder:
            self.assertEqual({}, smoke.validate_native_close(Path(folder), False))

    def test_window_close_after_ready_playback_and_shutdown_passes(self):
        with tempfile.TemporaryDirectory() as folder:
            evidence = Path(folder)
            (evidence / "native-close-request.json").write_text(json.dumps({"ready": True}))
            (evidence / "close-lifecycle.json").write_text(json.dumps({"source": "window", "shutdownComplete": True}))
            self.assertEqual({"nativeCloseVerified": True}, smoke.validate_native_close(evidence, True))

    def test_missing_or_incomplete_close_evidence_is_rejected(self):
        cases = [(None, None), ({"ready": True}, None),
                 ({"ready": False}, {"source": "window", "shutdownComplete": True}),
                 ({"ready": True}, {"source": "smoke", "shutdownComplete": True}),
                 ({"ready": True}, {"source": "window", "shutdownComplete": False}),
                 ({"ready": True}, {"source": "window", "shutdownComplete": "true"})]
        for request, lifecycle in cases:
            with self.subTest(request=request, lifecycle=lifecycle), tempfile.TemporaryDirectory() as folder:
                evidence = Path(folder)
                if request is not None:
                    (evidence / "native-close-request.json").write_text(json.dumps(request))
                if lifecycle is not None:
                    (evidence / "close-lifecycle.json").write_text(json.dumps(lifecycle))
                with self.assertRaisesRegex(RuntimeError, "Native window close"):
                    smoke.validate_native_close(evidence, True)


class UncaughtExceptionVerdictTest(unittest.TestCase):
    def test_uncaught_exception_is_retained_without_message_or_paths(self):
        raw = '\n'.join([
            'Exception in thread "AWT-EventQueue-0" java.lang.NullPointerException: https://example.invalid/?api_key=private',
            '\tat java.desktop@21.0.13/java.awt.Component.requestFocusHelper(Component.java:8049)',
            '\tat app.pictureshow.desktop.DesktopSmokeKt.smoke(DesktopSmoke.kt:123)',
            '\tat app.bad.Frame(/tmp/private/token:42)',
            'SMOKE all 18 viewer flows passed',
            '\tat app.unrelated.Frame(Unrelated.java:7)',
        ])
        exceptions = smoke.uncaught_app_exceptions(raw)
        self.assertEqual([{'type': 'java.lang.NullPointerException', 'frames': [
            {'method': 'java.awt.Component.requestFocusHelper', 'line': 8049},
            {'method': 'app.pictureshow.desktop.DesktopSmokeKt.smoke', 'line': 123}]}], exceptions)
        with self.assertRaisesRegex(RuntimeError, 'uncaught exception'):
            smoke.require_no_uncaught_exceptions(exceptions)
        for private in ['example.invalid', 'private', '/tmp', 'api_key', 'unrelated']:
            self.assertNotIn(private, json.dumps(exceptions))

    def test_handled_player_diagnostic_does_not_count_as_uncaught(self):
        exceptions = smoke.uncaught_app_exceptions('DESKTOP_PLAYER_FAILURE stage=open type=java.lang.IllegalArgumentException')
        self.assertEqual([], exceptions)
        smoke.require_no_uncaught_exceptions(exceptions)


class MacEncoderProcessTest(unittest.TestCase):
    def library(self, inaccessible=False):
        class Library:
            children = {123: [201, 202], 201: [203], 999: [204]}
            records = {201: (123, b'ffmpeg'), 202: (123, b'ffprobe'),
                       203: (201, b'ffmpeg'), 204: (999, b'ffmpeg')}

            def proc_listpids(self, kind, parent, buffer, size):
                assert kind == 6
                rows = self.children.get(parent, [])
                if buffer is not None:
                    for index, pid in enumerate(rows):
                        buffer[index] = pid
                return len(rows) * smoke.ctypes.sizeof(smoke.ctypes.c_int)

            def proc_pidinfo(self, pid, flavor, arg, pointer, size):
                assert flavor == 13 and size == 64
                if inaccessible:
                    smoke.ctypes.set_errno(1)
                    return 0
                info = pointer._obj
                info.pid = pid
                info.ppid, info.comm = self.records[pid]
                return size
        return Library()

    def test_owned_tree_counts_real_names_and_excludes_other_processes(self):
        with patch.object(smoke, 'mac_process_library', return_value=self.library()):
            self.assertEqual(2, smoke.mac_encoder_process_count(123))

    def test_inaccessible_owned_process_cannot_invent_zero_encoders(self):
        with patch.object(smoke, 'mac_process_library', return_value=self.library(inaccessible=True)):
            with self.assertRaisesRegex(RuntimeError, 'identity observation failed'):
                smoke.mac_encoder_process_count(123)

    def test_truncated_process_list_cannot_invent_zero_encoders(self):
        library = self.library()
        library.proc_listpids = lambda kind, parent, buffer, size: size if buffer is not None else 4
        with patch.object(smoke, 'mac_process_library', return_value=library):
            with self.assertRaisesRegex(RuntimeError, 'truncated'):
                smoke.mac_encoder_process_count(123)


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

    def test_player_failure_keeps_class_and_app_frame_without_raw_messages(self):
        text = "\n".join([
            "DESKTOP_PLAYER_FAILURE stage=open type=java.lang.UnsatisfiedLinkError",
            "DESKTOP_PLAYER_FAILURE stage=window-chrome type=java.lang.IllegalStateException",
            "DESKTOP_PLAYER_FRAME app.pictureshow.desktop.NativeDesktopPlayback.open:188",
            "DESKTOP_PLAYER_FRAME app.pictureshow.desktop.Mpv.<init>:50",
            "DESKTOP_PLAYER_FAILURE stage=open type=java.lang.Exception https://example.invalid/private?api_key=abc",
            "DESKTOP_PLAYER_FRAME /tmp/private/token:32",
            "Raw mpv message password=pw",
            "SMOKE ready https://example.invalid/private?api_key=abc",
        ])
        cleaned = smoke.selected_app_output(text, "pw", "abc")
        self.assertIn("type=java.lang.UnsatisfiedLinkError", cleaned)
        self.assertIn("stage=window-chrome type=java.lang.IllegalStateException", cleaned)
        self.assertIn("NativeDesktopPlayback.open:188", cleaned)
        self.assertIn("Mpv.<init>:50", cleaned)
        self.assertEqual(2, cleaned.count("DESKTOP_PLAYER_FAILURE"))
        self.assertEqual(2, cleaned.count("DESKTOP_PLAYER_FRAME"))
        for private in ["example.invalid", "abc", "private", "password", "Raw mpv"]:
            self.assertNotIn(private, cleaned)

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

    def test_native_server_configuration_has_only_loopback_ingress(self):
        import xml.etree.ElementTree as ET
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "network.xml"
            smoke.write_native_network_configuration(path, 19382)
            configuration = ET.parse(path).getroot()
        self.assertEqual(["127.0.0.1"], [node.text for node in configuration.findall("LocalNetworkAddresses/string")])
        self.assertEqual("false", configuration.findtext("AutoDiscovery"))
        self.assertEqual("false", configuration.findtext("EnableIPv6"))
        self.assertEqual("false", configuration.findtext("EnableRemoteAccess"))
        self.assertEqual("19382", configuration.findtext("InternalHttpPort"))

    def test_fixture_empty_virtual_folder_fails_before_scan_timeout(self):
        class FixtureAPI:
            def request(self, method, endpoint, body=None, query=None):
                if endpoint == "/Environment/DirectoryContents":
                    return [{"Name": "Signal One", "Type": "Directory"}]
                return [{"Name": "Movies", "Locations": []}] if method == "GET" else None
        with self.assertRaisesRegex(RuntimeError, "did not retain"):
            smoke.add_fixture_library(FixtureAPI(), "Movies", "movies", "/fixture/movies")

    def test_container_encoder_observation_keeps_required_pid_column(self):
        def docker_top(command, **_kwargs):
            self.assertEqual(["docker", "top", "owned-fixture", "-eo", "pid,comm"], command)
            return "PID COMMAND\n12 jellyfin\n13 ffmpeg\n14 ffprobe\n"
        api = type("OwnedServer", (), {"fixture_container": "owned-fixture"})()
        with patch.object(smoke.subprocess, "check_output", docker_top):
            self.assertEqual(1, smoke.encoding_process_count(api))


if __name__ == "__main__":
    unittest.main()
