#!/usr/bin/env python3
"""
Receive the console's walk files: a CSV of every second and a summary.

    python3 stride_walk_listener.py

The console writes two files per walk while the walk is going and posts
them, as one `multipart/form-data` request, when the walk ends. This is the
other end of that.

**Why a separate listener rather than an addition to the health webhook.**
`stride_health_webhook.py` does not receive anything: it *publishes* a Home
Assistant automation, and the phone posts JSON at Home Assistant's own
webhook. There is no server process there to add an endpoint to. This is a
server, it has to be one, and it is the same shape as the other small
scripts here — a single file, stdlib only, no daemon framework.

**What it does with a walk.** Writes the two files into a directory, named
by the walk id, so the pair can be found together. The CSV is the walk;
the summary is the walk's totals. They arrive in one request and are
written together, because a receiver that kept one without the other would
be holding half a walk.

**A walk is never stored twice.** The console retries a send it could not
deliver, and it may retry one that actually landed if it lost power in
between. The walk id says which walk it is, and a walk already on disk is
not written again — the files are the same files.

**It binds to the network and takes files from it, so it is trusted
hardware.** It is meant for a home network, next to Home Assistant, on a
machine nobody else has an account on. There is no authentication here and
none is offered: the URL on the console's settings screen is the only
secret, and anybody who can reach it can put a walk on the disk. Do not
expose this to the internet, and do not point it at anything that is.

Where the files go, and the port, are in `stride.conf` — see
`stride.conf.example`.
"""
import json
import os
import re
import sys
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from stride_config import conf

# Where the received walks are kept. A relative path is relative to this
# script, so the listener and the files travel together.
DEFAULT_DIR = "walks"

# The port the console is told to post to.
DEFAULT_PORT = 8099

# How big a walk may be before it is refused. A forty-minute walk is a few
# hundred kilobytes of CSV; this is a ceiling against a runaway, not a
# limit anybody should meet.
MAX_BODY = 32 * 1024 * 1024

# The parts the console sends. The walk id is also in both filenames, so a
# receiver that only reads the files still knows which walk they are.
PART_CSV = "csv"
PART_SUMMARY = "summary"
FIELD_WALK_ID = "walk_id"
FIELD_WHO = "who"


def walk_dir() -> str:
    """The directory the received walks are kept in."""
    here = os.path.dirname(os.path.abspath(__file__))
    path = conf("walk_log_dir", DEFAULT_DIR)
    if not os.path.isabs(path):
        path = os.path.join(here, path)
    os.makedirs(path, exist_ok=True)
    return path


def parse_multipart(content_type: str, body: bytes):
    """Split a multipart body into its fields and its files.

    Returns `(fields, files)`: fields are name -> text, files are
    name -> (filename, bytes).

    **`email.parser` rather than `cgi`.** `cgi.FieldStorage` was the
    obvious tool and it was removed from Python in 3.13. The email parser
    reads the same MIME shape, and it is still in the standard library.
    """
    head = b"Content-Type: " + content_type.encode("latin-1") + b"\r\n"
    head += b"MIME-Version: 1.0\r\n\r\n"
    msg = BytesParser(policy=policy.default).parsebytes(head + body)
    if not msg.is_multipart():
        raise ValueError("the body is not multipart")

    fields, files = {}, {}
    for part in msg.iter_parts():
        name = part.get_param("name", header="content-disposition")
        if not name:
            continue
        filename = part.get_filename()
        payload = part.get_payload(decode=True)
        if payload is None:
            payload = b""
        if filename is None:
            # A plain field, and it is text. `get_payload(decode=True)`
            # hands back bytes, so it is decoded here rather than left to
            # turn a walk id into "b'walk-...'".
            if isinstance(payload, (bytes, bytearray)):
                text = bytes(payload).decode("utf-8", "replace")
            else:
                text = str(payload)
            fields[name] = text
        else:
            files[name] = (filename, payload)
    return fields, files


def safe_id(raw: str) -> str:
    """A walk id that is safe to put on the disk.

    The id comes off the network, so it is not trusted: it becomes a file
    name, and a file name is a path. Everything that is not a letter, a
    digit, a dash or an underscore is dropped, and what is left must not be
    empty or be a pair of dots.
    """
    clean = re.sub(r"[^A-Za-z0-9_-]", "", raw or "")
    clean = clean.strip(".")
    return clean or "walk"


class Handler(BaseHTTPRequestHandler):
    """One request: either a walk arriving, or somebody asking if it is up."""

    server_version = "STRIDE-walk-listener/1.0"

    def do_GET(self):
        """A small status page, so the listener can be checked with a browser."""
        body = json.dumps({
            "service": "stride walk listener",
            "walk_dir": walk_dir(),
            "walks": len([n for n in os.listdir(walk_dir())
                          if n.endswith(".summary.json")]),
        }, indent=2).encode()
        self._reply(200, "application/json", body)

    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length <= 0:
            self._fail(400, "no body")
            return
        if length > MAX_BODY:
            self._fail(413, "the body is too large")
            return

        content_type = self.headers.get("Content-Type") or ""
        if "multipart/form-data" not in content_type:
            self._fail(415, "expected multipart/form-data")
            return

        body = self.rfile.read(length)
        try:
            fields, files = parse_multipart(content_type, body)
        except Exception as e:                       # noqa: BLE001
            self._fail(400, f"cannot read the body ({e})")
            return

        csv = files.get(PART_CSV)
        summary = files.get(PART_SUMMARY)
        if csv is None or summary is None:
            self._fail(400, f"a walk needs both a '{PART_CSV}' and a "
                            f"'{PART_SUMMARY}' file")
            return

        # The walk id is a form field, and it is also in both filenames. The
        # field is preferred; the filename is the fallback for a receiver
        # that was sent the files without it.
        walk_id = fields.get(FIELD_WALK_ID) or ""
        if not walk_id:
            walk_id = os.path.splitext(os.path.basename(csv[0]))[0]
        walk_id = safe_id(walk_id)

        who = fields.get(FIELD_WHO) or ""
        try:
            stored = self._store(walk_id, csv[1], summary[1])
        except OSError as e:
            self._fail(500, f"cannot write the files ({e})")
            return

        if stored:
            print(f"  received walk {walk_id}" + (f" ({who})" if who else ""))
        else:
            print(f"  walk {walk_id} was already here — not written again")

        self._reply(200, "application/json", json.dumps({
            "walk_id": walk_id,
            "who": who,
            "stored": stored,
        }).encode())

    def _store(self, walk_id: str, csv: bytes, summary: bytes) -> bool:
        """Write the two files. Returns False if the walk was already here.

        **The summary is written last, and it is the marker.** If the
        listener dies between the two writes, the next attempt writes both
        again — which is harmless, because they are the same files. If it
        dies after, the walk is known to be complete.
        """
        where = walk_dir()
        summary_path = os.path.join(where, f"{walk_id}.summary.json")
        if os.path.exists(summary_path):
            return False
        csv_path = os.path.join(where, f"{walk_id}.csv")
        with open(csv_path, "wb") as fh:
            fh.write(csv)
        with open(summary_path, "wb") as fh:
            fh.write(summary)
        return True

    def _fail(self, code: int, why: str):
        print(f"  refused: {why}")
        self._reply(code, "application/json",
                    json.dumps({"error": why}).encode())

    def _reply(self, code: int, content_type: str, body: bytes):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        """Quieter than the default, which logs every request twice."""
        print(f"  {self.address_string()} {format % args}")


def main():
    host = conf("walk_listener_host", "0.0.0.0")
    port = int(conf("walk_listener_port", str(DEFAULT_PORT)))
    where = walk_dir()

    print(f"STRIDE walk listener on http://{host}:{port}")
    print(f"  walks go to: {where}")
    print(f"  point the console's Settings -> Walk log at:")
    print(f"    http://<this machine>:{port}/walk")
    print("  Ctrl-C to stop.")
    try:
        ThreadingHTTPServer((host, port), Handler).serve_forever()
    except KeyboardInterrupt:
        print("\nstopped.")
    except OSError as e:
        sys.exit(f"cannot listen on {host}:{port} ({e})")


if __name__ == "__main__":
    main()
