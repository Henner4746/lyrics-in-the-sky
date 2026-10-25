"""Timing tracker for Lyrics in the Sky.

Players who correct the timing of a song tell it here; once enough of them agree, the correction applies to every
player. Stored per vote: the song ID (a hash the mod makes of artist, title and the first timed lines), the
correction in milliseconds and two keyed hashes, of the random install ID and of the network the vote came from.
No IP addresses, names or Minecraft accounts are kept.

Endpoints (behind nginx at https://henrik.click/lyrics-sync/):
    GET  /health
    GET  /v1/corrections/<song>   -> {"players": n, "offsetMs": ms or null}
    POST /v1/corrections          <- {"song": ..., "offsetMs": ms or null, "install": ..., "version": ...}

Python standard library only. Settings come from the environment, see ``Settings``.
"""

import hashlib
import hmac
import ipaddress
import json
import os
import re
import secrets
import sqlite3
import statistics
import threading
import time
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SONG = re.compile(r"^[0-9a-f]{32}$")
INSTALL = re.compile(r"^[0-9a-fA-F-]{16,64}$")
MAX_OFFSET_MS = 5000
MAX_BODY = 1024


@dataclass(frozen=True)
class Settings:
    data_dir: str = os.environ.get("LYRICS_SYNC_DATA", "/var/lib/lyrics-sync")
    port: int = int(os.environ.get("LYRICS_SYNC_PORT", "8472"))
    # Players who corrected a song before a common correction can apply.
    min_players: int = int(os.environ.get("LYRICS_SYNC_MIN_PLAYERS", "10"))
    # Different networks among them, so one person with many installs cannot decide alone.
    min_networks: int = int(os.environ.get("LYRICS_SYNC_MIN_NETWORKS", "5"))
    # Share of the players whose correction lies close to the middle one.
    min_agreement: float = 0.7
    agree_within_ms: int = 150
    # Requests per network and 10 minutes.
    reads_per_window: int = 600
    writes_per_window: int = 60
    window_s: int = 600


def consensus(votes, settings):
    """votes: (install_hash, network_hash, offset_ms) rows of one song -> (players, offset_ms or None)."""
    players = len(votes)
    if players < settings.min_players:
        return players, None
    middle = statistics.median(offset for _, _, offset in votes)
    agreeing = [(network, offset) for _, network, offset in votes if abs(offset - middle) <= settings.agree_within_ms]
    if len(agreeing) < settings.min_agreement * players:
        return players, None
    if len({network for network, _ in agreeing}) < settings.min_networks:
        return players, None
    common = statistics.median(offset for _, offset in agreeing)
    return players, int(round(common / 10.0)) * 10


def network_of(address):
    """The network a request came from: the address for IPv4, the /64 for IPv6, which one line usually gets."""
    try:
        ip = ipaddress.ip_address(address)
    except ValueError:
        return "unknown"
    if ip.version == 6:
        if ip.ipv4_mapped:
            return str(ip.ipv4_mapped)
        return str(ipaddress.ip_network(f"{ip}/64", strict=False))
    return str(ip)


class RateLimit:
    def __init__(self, limit, window_s):
        self.limit = limit
        self.window_s = window_s
        self.hits = {}
        self.lock = threading.Lock()

    def allow(self, key, now=None):
        now = time.monotonic() if now is None else now
        with self.lock:
            start, count = self.hits.get(key, (now, 0))
            if now - start >= self.window_s:
                start, count = now, 0
            if count >= self.limit:
                return False
            self.hits[key] = (start, count + 1)
            if len(self.hits) > 50_000:
                self.hits = {k: v for k, v in self.hits.items() if now - v[0] < self.window_s}
            return True


class Tracker:
    def __init__(self, settings):
        self.settings = settings
        os.makedirs(settings.data_dir, exist_ok=True)
        self.salt = self._salt(os.path.join(settings.data_dir, "salt"))
        self.db = sqlite3.connect(os.path.join(settings.data_dir, "tracker.db"), check_same_thread=False)
        self.lock = threading.Lock()
        with self.lock:
            self.db.execute(
                "CREATE TABLE IF NOT EXISTS votes (song TEXT NOT NULL, install TEXT NOT NULL, network TEXT NOT NULL, "
                "offset_ms INTEGER NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY (song, install))")
            self.db.commit()
        self.reads = RateLimit(settings.reads_per_window, settings.window_s)
        self.writes = RateLimit(settings.writes_per_window, settings.window_s)

    @staticmethod
    def _salt(path):
        if os.path.exists(path):
            with open(path, "rb") as f:
                return f.read()
        salt = secrets.token_bytes(32)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(salt)
        return salt

    def hash(self, kind, value):
        return hmac.new(self.salt, f"{kind}:{value}".encode(), hashlib.sha256).hexdigest()[:32]

    def lookup(self, song):
        with self.lock:
            rows = self.db.execute("SELECT install, network, offset_ms FROM votes WHERE song = ?", (song,)).fetchall()
        players, offset = consensus(rows, self.settings)
        return {"players": players, "offsetMs": offset}

    def vote(self, song, install, network, offset_ms):
        install_hash = self.hash("install", install)
        network_hash = self.hash("network", network)
        with self.lock:
            if offset_ms is None:
                self.db.execute("DELETE FROM votes WHERE song = ? AND install = ?", (song, install_hash))
            else:
                self.db.execute(
                    "INSERT INTO votes (song, install, network, offset_ms, updated) VALUES (?, ?, ?, ?, ?) "
                    "ON CONFLICT (song, install) DO UPDATE SET network = excluded.network, "
                    "offset_ms = excluded.offset_ms, updated = excluded.updated",
                    (song, install_hash, network_hash, offset_ms, int(time.time())))
            self.db.commit()


def parse_vote(body):
    """The vote in a request body, or an error text."""
    try:
        data = json.loads(body)
    except (ValueError, UnicodeDecodeError):
        return None, "not JSON"
    if not isinstance(data, dict):
        return None, "not an object"
    song, install, offset = data.get("song"), data.get("install"), data.get("offsetMs")
    if not isinstance(song, str) or not SONG.match(song):
        return None, "bad song"
    if not isinstance(install, str) or not INSTALL.match(install):
        return None, "bad install"
    if offset is not None and (isinstance(offset, bool) or not isinstance(offset, int) or abs(offset) > MAX_OFFSET_MS):
        return None, "bad offsetMs"
    return (song, install.lower(), offset), None


def make_handler(tracker):
    class Handler(BaseHTTPRequestHandler):
        server_version = "lyrics-sync"
        sys_version = ""

        def log_message(self, format, *args):
            # No access log: it would hold addresses this service promises not to keep.
            pass

        def network(self):
            return network_of(self.headers.get("X-Real-IP") or self.client_address[0])

        def reply(self, status, payload):
            body = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/health":
                return self.reply(200, {"ok": True})
            prefix = "/v1/corrections/"
            if not self.path.startswith(prefix):
                return self.reply(404, {"error": "not found"})
            song = self.path[len(prefix):]
            if not SONG.match(song):
                return self.reply(400, {"error": "bad song"})
            if not tracker.reads.allow(tracker.hash("network", self.network())):
                return self.reply(429, {"error": "slow down"})
            return self.reply(200, tracker.lookup(song))

        def do_POST(self):
            if self.path != "/v1/corrections":
                return self.reply(404, {"error": "not found"})
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                length = -1
            if length < 0 or length > MAX_BODY:
                return self.reply(413, {"error": "too large"})
            network = self.network()
            if not tracker.writes.allow(tracker.hash("network", network)):
                return self.reply(429, {"error": "slow down"})
            vote, error = parse_vote(self.rfile.read(length))
            if error:
                return self.reply(400, {"error": error})
            song, install, offset = vote
            tracker.vote(song, install, network, offset)
            return self.reply(200, tracker.lookup(song))

    return Handler


def main():
    settings = Settings()
    tracker = Tracker(settings)
    server = ThreadingHTTPServer(("127.0.0.1", settings.port), make_handler(tracker))
    server.daemon_threads = True
    print(f"lyrics-sync listening on 127.0.0.1:{settings.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
