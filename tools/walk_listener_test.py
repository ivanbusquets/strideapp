#!/usr/bin/env python3
"""
Test the walk listener: does a walk survive the trip from the console to disk?

    python3 tools/walk_listener_test.py

The console's half of this is Kotlin and cannot be run here, so this test
builds the request **the way the console builds it** — the same
multipart/form-data layout, the same field names, the same file names — and
posts it at a listener running on a real socket.

That is the part worth checking: the listener and the console are two
implementations of one format, written on either side of a network, and
nothing else in the repo holds them together. A change to one that the
other does not know about is a walk that is silently lost, and this is the
test that catches it.

What it checks:

  * a walk arrives, and both files are on disk
  * the walk id and the walker's name arrive as fields
  * a walk that is posted twice is stored once
  * a walk missing its summary is refused rather than half-stored
  * a body that is not multipart is refused
"""
import http.client
import json
import os
import shutil
import sys
import tempfile
import threading
import time
import unittest
import uuid
from http.server import ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
HA = os.path.join(os.path.dirname(HERE), "homeassistant")
sys.path.insert(0, HA)

# The listener reads its configuration through stride_config, which looks for
# `stride.conf` beside the scripts. One is written into a temporary directory
# for the test and the module is pointed at it, so the test neither needs
# nor touches the one on this machine.
TMP = tempfile.mkdtemp(prefix="stride-walk-test-")
CONF = os.path.join(TMP, "stride.conf")
WALK_DIR = os.path.join(TMP, "walks")

with open(CONF, "w") as fh:
    fh.write(f"ha_url: http://localhost:8123\n"
             f"ha_api_token: test-token\n"
             f"mqtt_broker: localhost:1883\n"
             f"mqtt_user: test\n"
             f"mqtt_pass: test\n"
             f"walk_log_dir: {WALK_DIR}\n")

import stride_config  # noqa: E402

# stride_config.conf() reads this list at call time, so pointing it here
# redirects every setting lookup — including the walk directory. The test
# writes into a temporary directory and never into the repository, which is
# the whole reason this is done rather than left to the default.
stride_config.CANDIDATES = [CONF]

import stride_walk_listener as listener  # noqa: E402

# Belt and braces: the listener asks the config for the directory, and the
# config now points into the temporary directory. Asserting that here means
# a change to either of them cannot quietly start writing walks into the
# repository.
assert listener.walk_dir() == WALK_DIR, (
    f"the listener would write to {listener.walk_dir()}, not the test's "
    f"temporary directory {WALK_DIR}"
)


def multipart(fields, files):
    """A multipart/form-data body, built the way the console builds it.

    Deliberately written out here rather than imported from the console:
    this is the *other* implementation, and the test is only worth
    something if it is independent of the one it is checking.
    """
    boundary = "----STRIDE" + uuid.uuid4().hex
    out = b""
    for name, value in fields.items():
        out += f"--{boundary}\r\n".encode()
        out += f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode()
        out += value.encode() + b"\r\n"
    for name, (filename, content, ctype) in files.items():
        out += f"--{boundary}\r\n".encode()
        out += (f'Content-Disposition: form-data; name="{name}"; '
                f'filename="{filename}"\r\n').encode()
        out += f"Content-Type: {ctype}\r\n\r\n".encode()
        out += content + b"\r\n"
    out += f"--{boundary}--\r\n".encode()
    return boundary, out


def summary_bytes(who="Sam"):
    return json.dumps({
        "who": who, "startedAt": 1792530000000, "elapsed": 1200,
        "distance": 2000, "calories": 150, "climbM": 30,
        "avgSpeed": 6.0, "maxSpeed": 7.2, "maxIncline": 6.0,
        "avgPulse": 120, "maxPulse": 148, "plan": "rolling",
    }, indent=2).encode()


CSV = (b"elapsed_s,speed_kph,belt_kph,incline_pct,distance_m,calories_kcal,pulse_bpm\n"
       b"0,2.0,1.9,0.0,0,0,0\n"
       b"1,4.0,3.8,0.0,2,0,0\n"
       b"2,6.0,5.9,2.0,5,1,110\n")


class ListenerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), listener.Handler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        # Give the socket a moment to accept.
        time.sleep(0.2)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        shutil.rmtree(TMP, ignore_errors=True)

    def post(self, body, boundary, content_type=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.request("POST", "/walk", body=body, headers={
            "Content-Type": content_type or f"multipart/form-data; boundary={boundary}",
            "Content-Length": str(len(body)),
        })
        resp = conn.getresponse()
        payload = resp.read()
        conn.close()
        return resp.status, payload

    def test_a_walk_arrives_with_both_files(self):
        walk_id = "walk-1792530000000-abc123"
        boundary, body = multipart(
            {"walk_id": walk_id, "who": "Sam"},
            {"csv": (f"{walk_id}.csv", CSV, "text/csv"),
             "summary": (f"{walk_id}.summary.json", summary_bytes(), "application/json")},
        )
        status, payload = self.post(body, boundary)
        self.assertEqual(status, 200, payload)
        answer = json.loads(payload)
        self.assertTrue(answer["stored"])
        self.assertEqual(answer["walk_id"], walk_id)
        self.assertEqual(answer["who"], "Sam")

        # Both files, on disk, with the walk's own bytes in them.
        csv_path = os.path.join(WALK_DIR, f"{walk_id}.csv")
        summary_path = os.path.join(WALK_DIR, f"{walk_id}.summary.json")
        self.assertTrue(os.path.exists(csv_path), "the CSV was not written")
        self.assertTrue(os.path.exists(summary_path), "the summary was not written")
        with open(csv_path, "rb") as fh:
            self.assertEqual(fh.read(), CSV)
        with open(summary_path, "rb") as fh:
            got = json.loads(fh.read())
        self.assertEqual(got["who"], "Sam")
        self.assertEqual(got["distance"], 2000)

    def test_a_walk_posted_twice_is_stored_once(self):
        """The console retries a send it could not deliver.

        If the first attempt actually landed, the retry must not produce a
        second copy. The walk id is what says the two are the same walk.
        """
        walk_id = "walk-1792530000000-twice"
        boundary, body = multipart(
            {"walk_id": walk_id, "who": "Sam"},
            {"csv": (f"{walk_id}.csv", CSV, "text/csv"),
             "summary": (f"{walk_id}.summary.json", summary_bytes(), "application/json")},
        )
        status, payload = self.post(body, boundary)
        self.assertEqual(status, 200, payload)
        self.assertTrue(json.loads(payload)["stored"])

        status, payload = self.post(body, boundary)
        self.assertEqual(status, 200, payload)
        self.assertFalse(json.loads(payload)["stored"], "the retry was stored again")

    def test_a_walk_without_its_summary_is_refused(self):
        """Half a walk is not a walk.

        The console always sends both files together, so a request with
        only one of them is not the console. It is refused rather than
        stored, because a CSV with no summary cannot be matched up with
        anything later.
        """
        walk_id = "walk-1792530000000-half"
        boundary, body = multipart(
            {"walk_id": walk_id, "who": "Sam"},
            {"csv": (f"{walk_id}.csv", CSV, "text/csv")},
        )
        status, payload = self.post(body, boundary)
        self.assertEqual(status, 400, payload)
        self.assertFalse(os.path.exists(os.path.join(WALK_DIR, f"{walk_id}.csv")))

    def test_a_body_that_is_not_multipart_is_refused(self):
        status, payload = self.post(b"just some bytes", "----STRIDE",
                                    content_type="text/plain")
        self.assertEqual(status, 415, payload)

    def test_the_walk_id_cannot_escape_the_directory(self):
        """The id comes off the network and becomes a file name.

        A file name is a path, so an id of `../../etc/passwd` must not be
        able to write outside the walk directory.
        """
        walk_id = "../../etc/passwd"
        boundary, body = multipart(
            {"walk_id": walk_id, "who": "Sam"},
            {"csv": ("x.csv", CSV, "text/csv"),
             "summary": ("x.summary.json", summary_bytes(), "application/json")},
        )
        status, payload = self.post(body, boundary)
        self.assertEqual(status, 200, payload)
        answer = json.loads(payload)
        self.assertNotIn("/", answer["walk_id"])
        self.assertNotIn("..", answer["walk_id"])
        self.assertTrue(os.path.exists(
            os.path.join(WALK_DIR, f"{answer['walk_id']}.csv")))

    def test_the_status_page_reports_the_walks(self):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.request("GET", "/")
        resp = conn.getresponse()
        payload = resp.read()
        conn.close()
        self.assertEqual(resp.status, 200)
        answer = json.loads(payload)
        self.assertEqual(answer["service"], "stride walk listener")
        self.assertGreaterEqual(answer["walks"], 1)

    def test_a_name_with_a_quote_in_it_arrives_intact(self):
        """Somebody's name is typed in by hand.

        The console escapes a quote in the header, and the listener has to
        unescape it: a name that arrives with the quote missing is a name
        that does not match the person on the walk.
        """
        walk_id = "walk-1792530000000-quote"
        who = 'Sam "The Walker"'
        boundary, body = multipart(
            {"walk_id": walk_id, "who": who},
            {"csv": (f"{walk_id}.csv", CSV, "text/csv"),
             "summary": (f"{walk_id}.summary.json", summary_bytes(), "application/json")},
        )
        status, payload = self.post(body, boundary)
        self.assertEqual(status, 200, payload)
        self.assertEqual(json.loads(payload)["who"], who)

    def test_a_name_with_a_newline_in_it_arrives_intact(self):
        """A newline in a name must not end the header early.

        That is an injection: a name carrying a newline could add a header
        of its own. The console escapes it, and the listener must not be
        fooled by one that did not.
        """
        walk_id = "walk-1792530000000-newline"
        who = "Sam\nInjected: yes"
        boundary, body = multipart(
            {"walk_id": walk_id, "who": who},
            {"csv": (f"{walk_id}.csv", CSV, "text/csv"),
             "summary": (f"{walk_id}.summary.json", summary_bytes(), "application/json")},
        )
        status, payload = self.post(body, boundary)
        self.assertEqual(status, 200, payload)
        self.assertEqual(json.loads(payload)["who"], who)


if __name__ == "__main__":
    unittest.main(verbosity=2)
