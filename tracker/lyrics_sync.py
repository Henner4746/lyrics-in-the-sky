"""Timing tracker for Lyrics in the Sky.

Players who correct the timing of a song tell it here; once enough of them agree, the correction applies to every
player. Stored per vote: the song ID (a hash the mod makes of artist, title and the first timed lines), the
correction in milliseconds and two keyed hashes, of the random install ID and of the network the vote came from.
No IP addresses, names or Minecraft accounts are kept.

Songs that Hoergenau learned by listening (word times for songs nobody has timed) are shared here too, so every player
gets them. Stored per learned song and player: the song key (a hash of artist, title and album), the song's names and
length, the word-timed lyrics, whether they were placed on known text or written down as heard, and the same two keyed
hashes. Lyrics placed on known text count once this server finds exactly that text on lrclib.net; lyrics written down
as heard count once players on two networks heard alike, so no single person can put words under a song for all.

Endpoints (behind nginx at https://henrik.click/lyrics-sync/):
    GET  /health
    GET  /v1/corrections/<song>   -> {"players": n, "offsetMs": ms or null}
    POST /v1/corrections          <- {"song": ..., "offsetMs": ms or null, "install": ..., "version": ...}
    GET  /v1/learned/<key>        -> {"lrc": ..., "kind": "aligned" or "heard", "players": n}, 404 when none counts
    POST /v1/learned              <- {"song": key, "artist", "title", "album", "durationMs", "kind", "matched",
                                      "lrc", "install", "version"}

Python standard library only. Settings come from the environment, see ``Settings``.
"""

import difflib
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
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SONG = re.compile(r"^[0-9a-f]{32}$")
INSTALL = re.compile(r"^[0-9a-fA-F-]{16,64}$")
MAX_OFFSET_MS = 5000
MAX_BODY = 1024
# Learned lyrics: what the mod writes, size limits, and how alike two players' lyrics must be.
LEARNED_MARK = "[re:Hoergenau]"
MAX_LEARNED_BODY = 96 * 1024
MAX_LRC = 64 * 1024
MAX_LRC_LINES = 600
MAX_NAME = 300
TIMED_LINE = re.compile(r"^\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\]")
ANY_TAG = re.compile(r"[\[<]\d{1,3}:\d{2}(?:[.:]\d{1,3})?[\]>]")
LABEL_LINE = re.compile(r"^[\[(].*[\])]$")
KINDS = ("aligned", "heard")
SAME_TEXT = 0.97
HEARD_ALIKE = 0.5
RECHECK_S = 600
LRCLIB = "https://lrclib.net/api"


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


def song_key(artist, title, album):
    """The key the mod files a song under: SHA-256 of artist, title and album, the first 16 bytes in hex."""
    return hashlib.sha256(f"{artist}\0{title}\0{album}".encode()).hexdigest()[:32]


def words_of(text):
    """The words of a text, lower case, without punctuation; "ß" as "ss" like the mod."""
    return re.findall(r"[^\W_]+", text.lower().replace("ß", "ss"))


def lrc_words(lrc):
    """The sung words of an LRC, without its time tags and header lines."""
    found = []
    for line in lrc.splitlines():
        if TIMED_LINE.match(line):
            found += words_of(ANY_TAG.sub(" ", line))
    return found


def plain_words(plain):
    """The words of plain lyrics as the mod uses them: section labels such as "[Chorus]" left out."""
    found = []
    for line in plain.splitlines():
        line = line.strip()
        if line and not LABEL_LINE.match(line):
            found += words_of(line)
    return found


def same_text(lrc, plain):
    """Whether learned lyrics carry exactly the given plain lyrics (a few words may differ in spelling)."""
    a, b = lrc_words(lrc), plain_words(plain)
    return bool(a) and (a == b or difflib.SequenceMatcher(None, a, b, autojunk=False).ratio() >= SAME_TEXT)


def heard_alike(lrc_a, lrc_b):
    """Whether two players heard the same song alike: half of their words in common at least."""
    a, b = set(lrc_words(lrc_a)), set(lrc_words(lrc_b))
    return bool(a) and bool(b) and len(a & b) / len(a | b) >= HEARD_ALIKE


class LrclibUnreachable(Exception):
    pass


def fetch_plain(artist, title, album, duration_ms):
    """The plain lyrics lrclib.net has for a song, None when it has none; raises LrclibUnreachable when it is busy."""
    headers = {"User-Agent": "lyrics-sync (Lyrics in the Sky tracker; henrik.click)"}

    def get(path, query):
        url = f"{LRCLIB}/{path}?{urllib.parse.urlencode(query)}"
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=8) as response:
                return json.loads(response.read())
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            raise LrclibUnreachable(str(e)) from e
        except (urllib.error.URLError, TimeoutError, ValueError) as e:
            raise LrclibUnreachable(str(e)) from e

    found = get("get", {"artist_name": artist, "track_name": title, "album_name": album,
                        "duration": round(duration_ms / 1000)})
    if isinstance(found, dict) and found.get("plainLyrics"):
        return found["plainLyrics"]
    hits = get("search", {"artist_name": artist, "track_name": title}) or []
    for hit in hits if isinstance(hits, list) else []:
        if hit.get("plainLyrics") and abs((hit.get("duration") or 0) * 1000 - duration_ms) <= 3000:
            return hit["plainLyrics"]
    return None


def parse_learned(body):
    """The learned song in a request body, or an error text."""
    try:
        data = json.loads(body)
    except (ValueError, UnicodeDecodeError):
        return None, "not JSON"
    if not isinstance(data, dict):
        return None, "not an object"
    song, install, kind, lrc = data.get("song"), data.get("install"), data.get("kind"), data.get("lrc")
    names = [data.get(field) for field in ("artist", "title", "album")]
    duration, matched = data.get("durationMs"), data.get("matched", 0)
    if not all(isinstance(name, str) and len(name) <= MAX_NAME for name in names) or not names[1]:
        return None, "bad names"
    if not isinstance(song, str) or song != song_key(*names):
        return None, "bad song"
    if not isinstance(install, str) or not INSTALL.match(install):
        return None, "bad install"
    if kind not in KINDS:
        return None, "bad kind"
    if isinstance(duration, bool) or not isinstance(duration, int) or not 10_000 <= duration <= 3_600_000:
        return None, "bad durationMs"
    if isinstance(matched, bool) or not isinstance(matched, (int, float)) or not 0 <= matched <= 1:
        return None, "bad matched"
    if (not isinstance(lrc, str) or not lrc.startswith(LEARNED_MARK) or len(lrc) > MAX_LRC
            or lrc.count("\n") > MAX_LRC_LINES or len(lrc_words(lrc)) < 3):
        return None, "bad lrc"
    return {"song": song, "artist": names[0], "title": names[1], "album": names[2], "duration_ms": duration,
            "kind": kind, "matched": float(matched), "lrc": lrc, "install": install.lower()}, None


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
    def __init__(self, settings, plain_lyrics=fetch_plain):
        self.settings = settings
        self.plain_lyrics = plain_lyrics
        os.makedirs(settings.data_dir, exist_ok=True)
        self.salt = self._salt(os.path.join(settings.data_dir, "salt"))
        self.db = sqlite3.connect(os.path.join(settings.data_dir, "tracker.db"), check_same_thread=False)
        self.lock = threading.Lock()
        with self.lock:
            self.db.execute(
                "CREATE TABLE IF NOT EXISTS votes (song TEXT NOT NULL, install TEXT NOT NULL, network TEXT NOT NULL, "
                "offset_ms INTEGER NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY (song, install))")
            # verified: 1 the text was found on lrclib.net, 0 not checked yet (lrclib busy) or written down as heard.
            self.db.execute(
                "CREATE TABLE IF NOT EXISTS learned (song TEXT NOT NULL, install TEXT NOT NULL, network TEXT NOT NULL, "
                "kind TEXT NOT NULL, verified INTEGER NOT NULL, checked INTEGER NOT NULL, matched REAL NOT NULL, "
                "artist TEXT NOT NULL, title TEXT NOT NULL, album TEXT NOT NULL, duration_ms INTEGER NOT NULL, "
                "lrc TEXT NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY (song, install))")
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

    def check_text(self, entry):
        """1 when lrclib.net has exactly this text, 0 when it could not be asked; raises ValueError when it differs."""
        try:
            plain = self.plain_lyrics(entry["artist"], entry["title"], entry["album"], entry["duration_ms"])
        except LrclibUnreachable:
            return 0
        if plain is None or not same_text(entry["lrc"], plain):
            raise ValueError("text not on lrclib.net")
        return 1

    def learn(self, entry, network):
        """Keeps a player's learned lyrics; known text is checked against lrclib.net first. Raises ValueError."""
        verified = self.check_text(entry) if entry["kind"] == "aligned" else 0
        now = int(time.time())
        with self.lock:
            self.db.execute(
                "INSERT INTO learned (song, install, network, kind, verified, checked, matched, artist, title, album, "
                "duration_ms, lrc, updated) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                "ON CONFLICT (song, install) DO UPDATE SET network = excluded.network, kind = excluded.kind, "
                "verified = excluded.verified, checked = excluded.checked, matched = excluded.matched, "
                "artist = excluded.artist, title = excluded.title, album = excluded.album, "
                "duration_ms = excluded.duration_ms, lrc = excluded.lrc, updated = excluded.updated",
                (entry["song"], self.hash("install", entry["install"]), self.hash("network", network), entry["kind"],
                 verified, now, entry["matched"], entry["artist"], entry["title"], entry["album"], entry["duration_ms"],
                 entry["lrc"], now))
            self.db.commit()
        return verified == 1

    def learned_for(self, song):
        """The learned lyrics that count for everyone, or None: checked known text first, then lyrics heard alike."""
        with self.lock:
            rows = self.db.execute(
                "SELECT install, network, kind, verified, checked, matched, artist, title, album, duration_ms, lrc "
                "FROM learned WHERE song = ?", (song,)).fetchall()
        rows = [dict(zip(("install", "network", "kind", "verified", "checked", "matched", "artist", "title", "album",
                          "duration_ms", "lrc"), row)) for row in rows]
        now = int(time.time())
        for row in rows:
            if row["kind"] == "aligned" and not row["verified"] and now - row["checked"] >= RECHECK_S:
                self.recheck(song, row, now)
        aligned = [row for row in rows if row["kind"] == "aligned" and row["verified"] == 1]
        if aligned:
            best = max(aligned, key=lambda row: row["matched"])
            return {"lrc": best["lrc"], "kind": "aligned", "players": len(aligned)}
        heard = [row for row in rows if row["kind"] == "heard"]
        confirmed = [row for row in heard if any(other["install"] != row["install"] and other["network"] != row["network"]
                                                 and heard_alike(row["lrc"], other["lrc"]) for other in heard)]
        if confirmed:
            best = max(confirmed, key=lambda row: len(lrc_words(row["lrc"])))
            return {"lrc": best["lrc"], "kind": "heard", "players": len(confirmed)}
        return None

    def recheck(self, song, row, now):
        """Known text that could not be checked when it came: asked again, dropped when lrclib.net disagrees."""
        try:
            verified = self.check_text(row)
        except ValueError:
            with self.lock:
                self.db.execute("DELETE FROM learned WHERE song = ? AND install = ?", (song, row["install"]))
                self.db.commit()
            row["verified"] = -1
            return
        row["verified"] = verified
        with self.lock:
            self.db.execute("UPDATE learned SET verified = ?, checked = ? WHERE song = ? AND install = ?",
                            (verified, now, song, row["install"]))
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
            for prefix in ("/v1/corrections/", "/v1/learned/"):
                if self.path.startswith(prefix):
                    break
            else:
                return self.reply(404, {"error": "not found"})
            song = self.path[len(prefix):]
            if not SONG.match(song):
                return self.reply(400, {"error": "bad song"})
            if not tracker.reads.allow(tracker.hash("network", self.network())):
                return self.reply(429, {"error": "slow down"})
            if prefix == "/v1/corrections/":
                return self.reply(200, tracker.lookup(song))
            learned = tracker.learned_for(song)
            return self.reply(200, learned) if learned else self.reply(404, {"error": "none"})

        def do_POST(self):
            if self.path not in ("/v1/corrections", "/v1/learned"):
                return self.reply(404, {"error": "not found"})
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                length = -1
            if length < 0 or length > (MAX_LEARNED_BODY if self.path == "/v1/learned" else MAX_BODY):
                return self.reply(413, {"error": "too large"})
            network = self.network()
            if not tracker.writes.allow(tracker.hash("network", network)):
                return self.reply(429, {"error": "slow down"})
            if self.path == "/v1/learned":
                entry, error = parse_learned(self.rfile.read(length))
                if error:
                    return self.reply(400, {"error": error})
                try:
                    verified = tracker.learn(entry, network)
                except ValueError as e:
                    return self.reply(422, {"error": str(e)})
                return self.reply(200, {"ok": True, "verified": verified})
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
