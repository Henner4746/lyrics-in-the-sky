"""Timing tracker for Lyrics in the Sky.

Players who correct the timing of a song tell it here; once enough of them agree, the correction applies to every
player. What a player's Hoergenau measured by listening (how far the words are shown from where they are sung, from
how many words paired) applies to every player at once, as long as other measurements do not contradict it. Stored
per vote or measurement: the song ID (a hash the mod makes of artist, title and the first timed lines), the
correction in milliseconds, for a measurement how many words were paired and how far they lay apart, and two keyed
hashes, of the random install ID and of the network the vote came from. No IP addresses, names or Minecraft accounts
are kept.

What Hoergenau learned by listening is shared here too, so every player gets it. For songs whose text lrclib.net has
without times, no lyrics are kept or sent: only the times of the words and a fingerprint of the text they belong to;
every player puts them on lrclib.net's text on their own PC. They count once this server finds exactly that text on
lrclib.net. For songs nobody has the text of, what Hoergenau wrote down as heard (text with word times) is kept, and
counts once players on two networks heard it alike, so no single person can put words under a song for all; it is
taken only while lrclib.net has no text for the song, for a song of a song's length that Deezer or Apple know, so no
livestream, video or podcast. Stored per song and player: the song key (a hash of artist,
title and album), the song's names and length, the word times with the fingerprint or the lyrics written down, and
the same two keyed hashes.

Endpoints (behind nginx at https://henrik.click/lyrics-sync/):
    GET  /health
    GET  /v1/corrections/<song>   -> {"players": n, "offsetMs": ms or null, "measured": m}
    POST /v1/corrections          <- {"song": ..., "offsetMs": ms or null, "install": ..., "version": ...}
                                  <- {..., "offsetMs": ms, "measured": {"pairs": n, "spreadMs": ms}} (Hoergenau)
    GET  /v1/learned/<key>        -> {"times": [[ms, ...], ...], "text": fingerprint, "kind": "aligned", "players": n}
                                     or {"lrc": ..., "kind": "heard", "players": n}, 404 when none counts
    POST /v1/learned              <- {"song": key, "artist", "title", "album", "durationMs", "kind": "aligned",
                                      "matched", "times", "text", "install", "version"}
                                     (older mods send "lrc" instead of "times" and "text": its text is dropped here)
                                  <- {..., "kind": "heard", "lrc": lyrics written down with word times, ...}

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
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SONG = re.compile(r"^[0-9a-f]{32}$")
INSTALL = re.compile(r"^[0-9a-fA-F-]{16,64}$")
MAX_OFFSET_MS = 5000
MAX_BODY = 1024
# Learned word times: what the mod writes, and size limits.
LEARNED_MARK = "[re:Hoergenau]"
MAX_LEARNED_BODY = 96 * 1024
MAX_LRC = 64 * 1024
MAX_LINES = 600
MAX_WORDS = 20_000
MAX_NAME = 300
TIMED_LINE = re.compile(r"^\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\]")
WORD_TAG = re.compile(r"<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>([^<]*)")
LABEL_LINE = re.compile(r"^[\[(].*[\])]$")
SPACES = re.compile(r"[ \t\n\x0b\f\r]+")
FINGERPRINT = re.compile(r"^[0-9a-f]{32}$")
ANY_TAG = re.compile(r"[\[<]\d{1,3}:\d{2}(?:[.:]\d{1,3})?[\]>]")
HEARD_ALIKE = 0.5
RECHECK_S = 600
LRCLIB = "https://lrclib.net/api"
# Lyrics written down as heard are taken only of a song's length: a livestream has none, a podcast runs longer.
MIN_SONG_MS = 30_000
MAX_SONG_MS = 20 * 60_000
# What Hoergenau takes as a measurement (SyncCheck in the mod): enough words paired, and close together.
MIN_PAIRS = 12
MAX_SPREAD_MS = 220
DEEZER = "https://api.deezer.com/search"
APPLE = "https://itunes.apple.com/search"
VERSION_NOTE = re.compile(r"\s*[(\[][^)\]]*[)\]]|\s+-\s+.*$")
NOT_WORD = re.compile(r"[\W_]+")
ARTIST_SPLIT = re.compile(r"\s*(?:,|;|&|/|\bx\b|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b)\s*")


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


def measured_consensus(measurements, settings):
    """measurements: (install_hash, network_hash, offset_ms) of what Hoergenau measured for one song -> offset_ms or
    None. One measurement counts at once; of several, more than half must agree."""
    if not measurements:
        return None
    middle = statistics.median(offset for _, _, offset in measurements)
    agreeing = [offset for _, _, offset in measurements if abs(offset - middle) <= settings.agree_within_ms]
    if len(agreeing) * 2 <= len(measurements):
        return None
    return int(round(statistics.median(agreeing) / 10.0)) * 10


def plain_name(name):
    """A title or artist for comparing: lower case, without version notes in brackets or after " - ", only words."""
    return NOT_WORD.sub(" ", VERSION_NOTE.sub("", name.lower())).strip()


def same_song(artist, title, hit_artist, hit_title):
    """Whether a catalog's hit is the song: the same title (or one the start of the other) and an artist in common."""
    mine, theirs = plain_name(title), plain_name(hit_title)
    if not mine or not theirs or not (mine == theirs or mine.startswith(theirs) or theirs.startswith(mine)):
        return False
    hit = plain_name(hit_artist)
    names = [plain_name(name) for name in ARTIST_SPLIT.split(artist.lower())]
    return bool(hit) and any(name and (name in hit or hit in name) for name in names)


def song_key(artist, title, album):
    """The key the mod files a song under: SHA-256 of artist, title and album, the first 16 bytes in hex."""
    return hashlib.sha256(f"{artist}\0{title}\0{album}".encode()).hexdigest()[:32]


def text_lines(plain):
    """The lines of plain lyrics as the mod times them: trimmed, spaces made single, empty lines and labels such as
    "[Chorus]" left out."""
    lines = []
    for line in plain.splitlines():
        line = SPACES.sub(" ", line.strip())
        if line and not LABEL_LINE.match(line):
            lines.append(line)
    return lines


def fingerprint(lines):
    """Which text word times belong to, without the text itself; the mod makes the same from its own lines."""
    return hashlib.sha256("\n".join(lines).lower().encode()).hexdigest()[:32]


def fits(times, lines):
    """Whether the word times have one time for every word of every line."""
    return len(times) == len(lines) and all(len(words) == len(line.split(" ")) for words, line in zip(times, lines))


def lines_and_times(lrc):
    """The lines and word times of learned lyrics as older mods sent them; only the times and a fingerprint are kept."""
    lines, times = [], []
    for line in lrc.splitlines():
        if not TIMED_LINE.match(line):
            continue
        words, starts = [], []
        for minutes, seconds, fraction, word in WORD_TAG.findall(line):
            fraction = (fraction or "0").ljust(3, "0")
            starts.append(int(minutes) * 60_000 + int(seconds) * 1000 + int(fraction))
            words.append(SPACES.sub(" ", word.strip()))
        if words and all(words) and all(" " not in word for word in words):
            lines.append(" ".join(words))
            times.append(starts)
    return lines, times


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


def heard_alike(lrc_a, lrc_b):
    """Whether two players heard the same song alike: half of their words in common at least."""
    a, b = set(lrc_words(lrc_a)), set(lrc_words(lrc_b))
    return bool(a) and bool(b) and len(a & b) / len(a | b) >= HEARD_ALIKE


def valid_times(times):
    """Word times as sent: lines of song positions in ms, at least three words, within the size limits."""
    if not isinstance(times, list) or not 1 <= len(times) <= MAX_LINES:
        return False
    count = 0
    for line in times:
        if not isinstance(line, list) or not line:
            return False
        for ms in line:
            if isinstance(ms, bool) or not isinstance(ms, int) or not 0 <= ms <= 3_600_000:
                return False
        count += len(line)
    return 3 <= count <= MAX_WORDS


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


class CatalogUnreachable(Exception):
    pass


def fetch_known_song(artist, title):
    """Whether Deezer or Apple know a song of that artist with that title; raises CatalogUnreachable when neither
    answers."""
    headers = {"User-Agent": "lyrics-sync (Lyrics in the Sky tracker; henrik.click)"}
    asks = [(f"{DEEZER}?{urllib.parse.urlencode({'q': f'{artist} {title}', 'limit': 10})}", "data", "title",
             lambda hit: (hit.get("artist") or {}).get("name", "")),
            (f"{APPLE}?{urllib.parse.urlencode({'term': f'{artist} {title}', 'entity': 'song', 'limit': 10})}",
             "results", "trackName", lambda hit: hit.get("artistName", ""))]
    answered = False
    for url, field, title_field, artist_of in asks:
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=8) as response:
                hits = json.loads(response.read()).get(field) or []
        except (urllib.error.URLError, TimeoutError, ValueError, AttributeError):
            continue
        answered = True
        if any(isinstance(hit, dict) and same_song(artist, title, artist_of(hit), hit.get(title_field) or "")
               for hit in hits):
            return True
    if not answered:
        raise CatalogUnreachable("neither Deezer nor Apple answered")
    return False


def parse_learned(body):
    """The learned word times in a request body, or an error text."""
    try:
        data = json.loads(body)
    except (ValueError, UnicodeDecodeError):
        return None, "not JSON"
    if not isinstance(data, dict):
        return None, "not an object"
    song, install, kind = data.get("song"), data.get("install"), data.get("kind")
    times, text = data.get("times"), data.get("text")
    names = [data.get(field) for field in ("artist", "title", "album")]
    duration, matched = data.get("durationMs"), data.get("matched", 0)
    if not all(isinstance(name, str) and len(name) <= MAX_NAME for name in names) or not names[1]:
        return None, "bad names"
    if not isinstance(song, str) or song != song_key(*names):
        return None, "bad song"
    if not isinstance(install, str) or not INSTALL.match(install):
        return None, "bad install"
    if kind not in ("aligned", "heard"):
        return None, "bad kind"
    if isinstance(duration, bool) or not isinstance(duration, int) or not 10_000 <= duration <= 3_600_000:
        return None, "bad durationMs"
    if isinstance(matched, bool) or not isinstance(matched, (int, float)) or not 0 <= matched <= 1:
        return None, "bad matched"
    lrc = data.get("lrc")
    if kind == "heard":
        # A song nobody has the text of: what was heard, with its word times.
        if (not isinstance(lrc, str) or not lrc.startswith(LEARNED_MARK) or len(lrc) > MAX_LRC
                or lrc.count("\n") > MAX_LINES or len(lrc_words(lrc)) < 3):
            return None, "bad lrc"
        if not MIN_SONG_MS <= duration <= MAX_SONG_MS:
            return None, "not a song's length"
        return {"song": song, "artist": names[0], "title": names[1], "album": names[2], "duration_ms": duration,
                "kind": "heard", "lrc": lrc, "install": install.lower()}, None
    if times is None and isinstance(lrc, str):
        # Older mods send the lyrics with their times: only the times and a fingerprint are taken, the text dropped.
        if not lrc.startswith(LEARNED_MARK) or len(lrc) > MAX_LRC:
            return None, "bad lrc"
        lines, times = lines_and_times(lrc)
        text = fingerprint(lines)
    if not valid_times(times):
        return None, "bad times"
    if not isinstance(text, str) or not FINGERPRINT.match(text):
        return None, "bad text"
    return {"song": song, "artist": names[0], "title": names[1], "album": names[2], "duration_ms": duration,
            "kind": "aligned", "matched": float(matched), "times": times, "text": text, "install": install.lower()}, None


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
    def __init__(self, settings, plain_lyrics=fetch_plain, known_song=fetch_known_song):
        self.settings = settings
        self.plain_lyrics = plain_lyrics
        self.known_song = known_song
        os.makedirs(settings.data_dir, exist_ok=True)
        self.salt = self._salt(os.path.join(settings.data_dir, "salt"))
        self.db = sqlite3.connect(os.path.join(settings.data_dir, "tracker.db"), check_same_thread=False)
        self.lock = threading.Lock()
        with self.lock:
            self.db.execute(
                "CREATE TABLE IF NOT EXISTS votes (song TEXT NOT NULL, install TEXT NOT NULL, network TEXT NOT NULL, "
                "offset_ms INTEGER NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY (song, install))")
            # What Hoergenau measured: the correction (0 when the lyrics fit), from how many words, how close.
            self.db.execute(
                "CREATE TABLE IF NOT EXISTS measured (song TEXT NOT NULL, install TEXT NOT NULL, network TEXT NOT NULL, "
                "offset_ms INTEGER NOT NULL, pairs INTEGER NOT NULL, spread_ms INTEGER NOT NULL, "
                "updated INTEGER NOT NULL, PRIMARY KEY (song, install))")
            # verified: 1 the text was found on lrclib.net, 0 not checked yet (lrclib busy).
            self.db.execute(
                "CREATE TABLE IF NOT EXISTS timings (song TEXT NOT NULL, install TEXT NOT NULL, network TEXT NOT NULL, "
                "verified INTEGER NOT NULL, checked INTEGER NOT NULL, matched REAL NOT NULL, artist TEXT NOT NULL, "
                "title TEXT NOT NULL, album TEXT NOT NULL, duration_ms INTEGER NOT NULL, text TEXT NOT NULL, "
                "times TEXT NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY (song, install))")
            # Songs nobody has the text of: the lyrics written down as heard.
            self.db.execute(
                "CREATE TABLE IF NOT EXISTS heard (song TEXT NOT NULL, install TEXT NOT NULL, network TEXT NOT NULL, "
                "artist TEXT NOT NULL, title TEXT NOT NULL, album TEXT NOT NULL, duration_ms INTEGER NOT NULL, "
                "lrc TEXT NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY (song, install))")
            self.db.commit()
            self._forget_lyrics()
        self.reads = RateLimit(settings.reads_per_window, settings.window_s)
        self.writes = RateLimit(settings.writes_per_window, settings.window_s)

    def _forget_lyrics(self):
        """Lyrics kept by older versions of this server: their word times stay, the text and heard lyrics go."""
        if not self.db.execute("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'learned'").fetchone():
            return
        rows = self.db.execute("SELECT song, install, network, verified, checked, matched, artist, title, album, "
                               "duration_ms, lrc, updated FROM learned WHERE kind = 'aligned'").fetchall()
        for row in rows:
            lines, times = lines_and_times(row[10])
            if valid_times(times):
                self.db.execute(
                    "INSERT OR IGNORE INTO timings (song, install, network, verified, checked, matched, artist, title, "
                    "album, duration_ms, text, times, updated) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (*row[:10], fingerprint(lines), json.dumps(times), row[11]))
        self.db.execute("DROP TABLE learned")
        self.db.commit()
        # Rewrites the file, so nothing of the text is left in its free pages.
        self.db.execute("VACUUM")

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
        """The correction for everyone: what Hoergenau measured, else what enough players set by hand."""
        with self.lock:
            rows = self.db.execute("SELECT install, network, offset_ms FROM votes WHERE song = ?", (song,)).fetchall()
            measured = self.db.execute("SELECT install, network, offset_ms FROM measured WHERE song = ?",
                                       (song,)).fetchall()
        _, by_hand = consensus(rows, self.settings)
        by_ear = measured_consensus(measured, self.settings)
        players = len({row[0] for row in rows} | {row[0] for row in measured})
        return {"players": players, "offsetMs": by_ear if by_ear is not None else by_hand, "measured": len(measured)}

    def vote(self, song, install, network, offset_ms, measured=None):
        install_hash = self.hash("install", install)
        network_hash = self.hash("network", network)
        with self.lock:
            if measured is not None:
                self.db.execute(
                    "INSERT INTO measured (song, install, network, offset_ms, pairs, spread_ms, updated) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (song, install) DO UPDATE SET network = excluded.network, "
                    "offset_ms = excluded.offset_ms, pairs = excluded.pairs, spread_ms = excluded.spread_ms, "
                    "updated = excluded.updated",
                    (song, install_hash, network_hash, offset_ms, measured[0], measured[1], int(time.time())))
            elif offset_ms is None:
                self.db.execute("DELETE FROM votes WHERE song = ? AND install = ?", (song, install_hash))
            else:
                self.db.execute(
                    "INSERT INTO votes (song, install, network, offset_ms, updated) VALUES (?, ?, ?, ?, ?) "
                    "ON CONFLICT (song, install) DO UPDATE SET network = excluded.network, "
                    "offset_ms = excluded.offset_ms, updated = excluded.updated",
                    (song, install_hash, network_hash, offset_ms, int(time.time())))
            self.db.commit()

    def check_text(self, entry):
        """1 when lrclib.net has exactly the text the times belong to, 0 when it could not be asked; raises ValueError
        when it has another text or none."""
        try:
            plain = self.plain_lyrics(entry["artist"], entry["title"], entry["album"], entry["duration_ms"])
        except LrclibUnreachable:
            return 0
        lines = text_lines(plain) if plain else []
        if not lines or fingerprint(lines) != entry["text"] or not fits(entry["times"], lines):
            raise ValueError("text not on lrclib.net")
        return 1

    def check_no_text(self, entry):
        """Lyrics written down as heard are only for songs lrclib.net has no text of, and only for songs Deezer or Apple
        know; raises ValueError for others."""
        try:
            plain = self.plain_lyrics(entry["artist"], entry["title"], entry["album"], entry["duration_ms"])
        except LrclibUnreachable:
            plain = None
        if plain and text_lines(plain):
            raise ValueError("lrclib.net has the text; only its word times are shared")
        try:
            known = self.known_song(entry["artist"], entry["title"])
        except CatalogUnreachable:
            return
        if not known:
            raise ValueError("no music catalog knows this song")

    def learn(self, entry, network):
        """Keeps a player's word times, checked against lrclib.net's text first, or the lyrics written down as heard of
        a song lrclib.net has no text of. Whether they count already. Raises ValueError."""
        if entry["kind"] == "heard":
            self.check_no_text(entry)
            with self.lock:
                self.db.execute(
                    "INSERT INTO heard (song, install, network, artist, title, album, duration_ms, lrc, updated) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (song, install) DO UPDATE SET "
                    "network = excluded.network, artist = excluded.artist, title = excluded.title, "
                    "album = excluded.album, duration_ms = excluded.duration_ms, lrc = excluded.lrc, "
                    "updated = excluded.updated",
                    (entry["song"], self.hash("install", entry["install"]), self.hash("network", network),
                     entry["artist"], entry["title"], entry["album"], entry["duration_ms"], entry["lrc"],
                     int(time.time())))
                self.db.commit()
            return False
        verified = self.check_text(entry)
        now = int(time.time())
        with self.lock:
            self.db.execute(
                "INSERT INTO timings (song, install, network, verified, checked, matched, artist, title, album, "
                "duration_ms, text, times, updated) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                "ON CONFLICT (song, install) DO UPDATE SET network = excluded.network, verified = excluded.verified, "
                "checked = excluded.checked, matched = excluded.matched, artist = excluded.artist, "
                "title = excluded.title, album = excluded.album, duration_ms = excluded.duration_ms, "
                "text = excluded.text, times = excluded.times, updated = excluded.updated",
                (entry["song"], self.hash("install", entry["install"]), self.hash("network", network), verified, now,
                 entry["matched"], entry["artist"], entry["title"], entry["album"], entry["duration_ms"],
                 entry["text"], json.dumps(entry["times"]), now))
            self.db.commit()
        return verified == 1

    def learned_for(self, song):
        """The word times that count for everyone, or None: those of the text lrclib.net has, heard best."""
        with self.lock:
            rows = self.db.execute(
                "SELECT install, verified, checked, matched, artist, title, album, duration_ms, text, times "
                "FROM timings WHERE song = ?", (song,)).fetchall()
        rows = [dict(zip(("install", "verified", "checked", "matched", "artist", "title", "album", "duration_ms",
                          "text", "times"), row)) for row in rows]
        now = int(time.time())
        for row in rows:
            row["times"] = json.loads(row["times"])
            if not row["verified"] and now - row["checked"] >= RECHECK_S:
                self.recheck(song, row, now)
        verified = [row for row in rows if row["verified"] == 1]
        if verified:
            best = max(verified, key=lambda row: row["matched"])
            return {"times": best["times"], "text": best["text"], "kind": "aligned", "players": len(verified)}
        return self.heard_for(song)

    def heard_for(self, song):
        """The lyrics written down as heard that count for everyone: those players on two networks heard alike."""
        with self.lock:
            heard = [dict(zip(("install", "network", "lrc"), row)) for row in self.db.execute(
                "SELECT install, network, lrc FROM heard WHERE song = ?", (song,)).fetchall()]
        confirmed = [row for row in heard if any(other["install"] != row["install"] and other["network"] != row["network"]
                                                 and heard_alike(row["lrc"], other["lrc"]) for other in heard)]
        if not confirmed:
            return None
        best = max(confirmed, key=lambda row: len(lrc_words(row["lrc"])))
        return {"lrc": best["lrc"], "kind": "heard", "players": len(confirmed)}

    def recheck(self, song, row, now):
        """Times whose text could not be checked when they came: asked again, dropped when lrclib.net disagrees."""
        try:
            verified = self.check_text(row)
        except ValueError:
            with self.lock:
                self.db.execute("DELETE FROM timings WHERE song = ? AND install = ?", (song, row["install"]))
                self.db.commit()
            row["verified"] = -1
            return
        row["verified"] = verified
        with self.lock:
            self.db.execute("UPDATE timings SET verified = ?, checked = ? WHERE song = ? AND install = ?",
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
    measured = data.get("measured")
    if measured is not None:
        pairs = measured.get("pairs") if isinstance(measured, dict) else None
        spread = measured.get("spreadMs") if isinstance(measured, dict) else None
        if (offset is None or isinstance(pairs, bool) or not isinstance(pairs, int) or not MIN_PAIRS <= pairs <= MAX_WORDS
                or isinstance(spread, bool) or not isinstance(spread, int) or not 0 <= spread <= MAX_SPREAD_MS):
            return None, "bad measured"
        measured = (pairs, spread)
    return (song, install.lower(), offset, measured), None


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
            song, install, offset, measured = vote
            tracker.vote(song, install, network, offset, measured)
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
