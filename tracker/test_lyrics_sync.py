import json
import sqlite3
import os
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import lyrics_sync as ls

SONG = "0123456789abcdef0123456789abcdef"


def votes(*offsets, networks=None):
    networks = networks or [f"net{i}" for i in range(len(offsets))]
    return [(f"install{i}", networks[i], offset) for i, offset in enumerate(offsets)]


class ConsensusTest(unittest.TestCase):
    settings = ls.Settings(data_dir="unused")

    def test_nothing_applies_below_ten_players(self):
        self.assertEqual((9, None), ls.consensus(votes(*[300] * 9), self.settings))

    def test_ten_agreeing_players_set_the_correction(self):
        self.assertEqual((10, 300), ls.consensus(votes(*[290, 300, 310, 300, 296, 305, 300, 280, 320, 300]), self.settings))

    def test_a_few_outliers_do_not_spoil_it(self):
        # 8 of 10 agree; the headphone lag of two players is left out.
        self.assertEqual((10, 300), ls.consensus(votes(*[300] * 8, 1200, -900), self.settings))

    def test_no_correction_when_players_disagree(self):
        self.assertEqual((10, None), ls.consensus(votes(*[300] * 5, *[900] * 5), self.settings))

    def test_one_network_alone_cannot_decide(self):
        self.assertEqual((10, None), ls.consensus(votes(*[300] * 10, networks=["home"] * 10), self.settings))
        mixed = ["home"] * 6 + ["a", "b", "c", "d"]
        self.assertEqual((10, 300), ls.consensus(votes(*[300] * 10, networks=mixed), self.settings))

    def test_rounded_to_ten_ms(self):
        self.assertEqual((10, 260), ls.consensus(votes(*[257] * 10), self.settings))


class InputTest(unittest.TestCase):
    def test_votes_are_checked(self):
        good = {"song": SONG, "offsetMs": 250, "install": "c0ffee00-1234-4abc-8def-0123456789ab", "version": "1.3.0"}
        self.assertEqual((SONG, good["install"], 250), ls.parse_vote(json.dumps(good))[0])
        self.assertEqual(None, ls.parse_vote(json.dumps({**good, "offsetMs": None}))[0][2])
        for bad in ({**good, "song": "x"}, {**good, "offsetMs": 9000}, {**good, "offsetMs": True},
                    {**good, "offsetMs": 1.5}, {**good, "install": "a"}, [1, 2]):
            self.assertIsNotNone(ls.parse_vote(json.dumps(bad))[1], bad)
        self.assertEqual("not JSON", ls.parse_vote(b"\xff")[1])

    def test_networks(self):
        self.assertEqual("203.0.113.7", ls.network_of("203.0.113.7"))
        self.assertEqual("2001:db8:1:2::/64", ls.network_of("2001:db8:1:2:aaaa:bbbb:cccc:dddd"))
        self.assertEqual("203.0.113.7", ls.network_of("::ffff:203.0.113.7"))
        self.assertEqual("unknown", ls.network_of("nonsense"))

    def test_rate_limit(self):
        limit = ls.RateLimit(2, 10)
        self.assertTrue(limit.allow("a", 0))
        self.assertTrue(limit.allow("a", 1))
        self.assertFalse(limit.allow("a", 2))
        self.assertTrue(limit.allow("b", 2))
        self.assertTrue(limit.allow("a", 11))


class HttpTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.tracker = ls.Tracker(ls.Settings(data_dir=self.dir.name, min_players=3, min_networks=2))
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), ls.make_handler(self.tracker))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.tracker.db.close()
        self.dir.cleanup()

    def request(self, path, body=None, ip="198.51.100.1"):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, headers={"X-Real-IP": ip,
                                                                                "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def vote(self, install, offset, ip):
        return self.request("/v1/corrections", {"song": SONG, "offsetMs": offset, "install": install, "version": "t"}, ip)

    def test_votes_become_a_common_correction_and_can_be_taken_back(self):
        self.assertEqual((200, {"ok": True}), self.request("/health"))
        self.assertEqual((200, {"players": 0, "offsetMs": None}), self.request(f"/v1/corrections/{SONG}"))
        self.vote("aaaaaaaa-0000-0000-0000-000000000001", 400, "198.51.100.1")
        self.vote("aaaaaaaa-0000-0000-0000-000000000002", 410, "198.51.100.2")
        # Changing one's mind replaces the vote instead of adding one.
        self.vote("aaaaaaaa-0000-0000-0000-000000000002", 400, "198.51.100.2")
        self.assertEqual((200, {"players": 2, "offsetMs": None}), self.request(f"/v1/corrections/{SONG}"))
        self.assertEqual((200, {"players": 3, "offsetMs": 400}),
                         self.vote("aaaaaaaa-0000-0000-0000-000000000003", 390, "198.51.100.3"))
        self.assertEqual((200, {"players": 2, "offsetMs": None}),
                         self.vote("aaaaaaaa-0000-0000-0000-000000000003", None, "198.51.100.3"))

    def test_nothing_identifying_is_stored(self):
        install = "bbbbbbbb-0000-0000-0000-000000000001"
        self.vote(install, 100, "198.51.100.77")
        rows = self.tracker.db.execute("SELECT * FROM votes").fetchall()
        self.assertEqual(1, len(rows))
        self.assertNotIn(install, repr(rows))
        self.assertNotIn("198.51.100.77", repr(rows))

    def test_bad_requests(self):
        self.assertEqual(400, self.request("/v1/corrections/xyz")[0])
        self.assertEqual(404, self.request("/nope")[0])
        self.assertEqual(400, self.request("/v1/corrections", {"song": SONG})[0])


PLAIN = "[Chorus]\nDie Tür fällt ins Schloss\nIch bin endlich allein\n\nDie Wohnung ist still"
# What older mods sent: the lyrics with their word times.
ALIGNED = ("[re:Hoergenau]\n[00:00.419]<00:00.419>Die <00:00.769>Tür <00:01.119>fällt <00:01.469>ins <00:01.819>Schloss\n"
           "[00:02.169]<00:02.169>Ich <00:02.519>bin <00:02.869>endlich <00:03.289>allein\n"
           "[00:04.369]<00:04.369>Die <00:04.629>Wohnung <00:05.129>ist <00:05.589>still\n")
# What the mod sends now: only the times, and the fingerprint of lrclib.net's text they belong to.
TIMES = [[419, 769, 1119, 1469, 1819], [2169, 2519, 2869, 3289], [4369, 4629, 5129, 5589]]
# The mod's test computes the same fingerprint for the same text (SharedLearnedTest).
TEXT = "08e0e4adfa0c37b0eb994e0c4d161e90"
HEARD = ("[re:Hoergenau] heard\n[00:01.907]<00:01.907>Das <00:02.152>ist <00:02.772>die <00:03.272>Haarschlacht, "
         "<00:04.272>Lokomotive\n[00:14.252]<00:14.252>Himmel <00:14.872>in <00:15.072>den <00:15.232>Arsch\n")
HEARD_TOO = ("[re:Hoergenau] heard\n[00:01.900]<00:01.900>Das <00:02.100>ist <00:02.700>die <00:03.300>Aarschlacht "
             "<00:04.300>Lokomotive\n[00:14.200]<00:14.200>Himmel <00:14.900>in <00:15.000>den <00:15.200>Arsch\n")
NO_TEXT = ("SUV WHATEVER", "Arschloch Lokomotive", "Arschloch Lokomotive")


def learned(times=TIMES, text=TEXT, install="cccccccc-0000-0000-0000-000000000001",
            names=("Peter Petersen", "Staubsaugerliebe", "Staubsaugerliebe")):
    return {"song": ls.song_key(*names), "artist": names[0], "title": names[1], "album": names[2],
            "durationMs": 210841, "kind": "aligned", "matched": 0.89, "times": times, "text": text,
            "install": install, "version": "t"}


def older(lrc=ALIGNED, kind="aligned", install="cccccccc-0000-0000-0000-000000000002",
          names=("Peter Petersen", "Staubsaugerliebe", "Staubsaugerliebe")):
    body = learned(install=install, names=names)
    del body["times"], body["text"]
    return {**body, "kind": kind, "lrc": lrc}


class LearnedTest(unittest.TestCase):
    def test_song_keys_are_the_mods(self):
        self.assertEqual("c491f2d8dfadbd4752970376365091a3",
                         ls.song_key("Peter Petersen", "Staubsaugerliebe", "Staubsaugerliebe"))

    def test_the_fingerprint_is_the_mods(self):
        lines = ls.text_lines(PLAIN)
        self.assertEqual(["Die Tür fällt ins Schloss", "Ich bin endlich allein", "Die Wohnung ist still"], lines)
        self.assertEqual(TEXT, ls.fingerprint(lines))
        self.assertEqual(TEXT, ls.fingerprint(ls.text_lines(PLAIN.replace("Ich bin", "Ich  bin "))))
        self.assertTrue(ls.fits(TIMES, lines))
        self.assertFalse(ls.fits([[1, 2, 3]] + TIMES[1:], lines))

    def test_older_mods_lyrics_become_times(self):
        lines, times = ls.lines_and_times(ALIGNED)
        self.assertEqual(TIMES, times)
        self.assertEqual(TEXT, ls.fingerprint(lines))

    def test_learned_times_are_checked(self):
        self.assertIsNone(ls.parse_learned(json.dumps(learned()))[1])
        entry, error = ls.parse_learned(json.dumps(older()))
        self.assertIsNone(error)
        self.assertEqual((TIMES, TEXT), (entry["times"], entry["text"]))
        self.assertNotIn("lrc", entry)
        for bad in ({**learned(), "song": "0" * 32}, {**learned(), "kind": "x"}, {**learned(), "times": [[1, 2]]},
                    {**learned(), "times": [["a", 2, 3]]}, {**learned(), "text": "Die Tür"}, older("[00:01.00]x"),
                    {**learned(), "durationMs": 5}, {**learned(), "matched": 2}, {**learned(), "install": "a"},
                    {**learned(), "title": "x" * 400}, older("[00:01.00]x", "heard"), older(ALIGNED[15:], "heard")):
            self.assertIsNotNone(ls.parse_learned(json.dumps(bad))[1], bad)
        heard, error = ls.parse_learned(json.dumps(older(HEARD, "heard", names=NO_TEXT)))
        self.assertIsNone(error)
        self.assertEqual(("heard", HEARD), (heard["kind"], heard["lrc"]))

    def test_heard_alike(self):
        self.assertTrue(ls.heard_alike(HEARD, HEARD_TOO))
        self.assertFalse(ls.heard_alike(HEARD, ALIGNED))


class LearnedHttpTest(HttpTest):
    def setUp(self):
        self.plain = PLAIN
        self.unreachable = False
        self.dir = tempfile.TemporaryDirectory()
        self.tracker = ls.Tracker(ls.Settings(data_dir=self.dir.name), plain_lyrics=self.fake_lrclib)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), ls.make_handler(self.tracker))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"

    def fake_lrclib(self, artist, title, album, duration_ms):
        if self.unreachable:
            raise ls.LrclibUnreachable("503")
        # Nobody has the text of this one.
        return None if title == NO_TEXT[1] else self.plain

    def test_word_times_count_for_everyone_once_lrclib_has_the_text(self):
        key = learned()["song"]
        self.assertEqual(404, self.request(f"/v1/learned/{key}")[0])
        self.assertEqual((200, {"ok": True, "verified": True}), self.request("/v1/learned", learned()))
        status, body = self.request(f"/v1/learned/{key}")
        self.assertEqual((200, TIMES, TEXT, "aligned"), (status, body["times"], body["text"], body["kind"]))
        self.assertNotIn("lrc", body)

    def test_older_mods_are_heard_without_their_text(self):
        self.assertEqual((200, {"ok": True, "verified": True}), self.request("/v1/learned", older()))
        status, body = self.request(f"/v1/learned/{learned()['song']}")
        self.assertEqual((200, TIMES), (status, body["times"]))

    def test_heard_lyrics_of_a_song_nobody_has_count_once_two_networks_heard_alike(self):
        key = ls.song_key(*NO_TEXT)
        self.assertEqual((200, {"ok": True, "verified": False}), self.request(
            "/v1/learned", older(HEARD, "heard", "dddddddd-0000-0000-0000-000000000001", NO_TEXT), "198.51.100.1"))
        self.assertEqual(404, self.request(f"/v1/learned/{key}")[0])
        # A second install on the same network does not count.
        self.request("/v1/learned", older(HEARD_TOO, "heard", "dddddddd-0000-0000-0000-000000000002", NO_TEXT),
                     "198.51.100.1")
        self.assertEqual(404, self.request(f"/v1/learned/{key}")[0])
        self.request("/v1/learned", older(HEARD_TOO, "heard", "dddddddd-0000-0000-0000-000000000003", NO_TEXT),
                     "198.51.100.9")
        status, body = self.request(f"/v1/learned/{key}")
        self.assertEqual((200, "heard"), (status, body["kind"]))
        self.assertTrue(body["lrc"].startswith(ls.LEARNED_MARK))

    def test_heard_lyrics_of_a_song_lrclib_has_are_refused(self):
        self.assertEqual(422, self.request("/v1/learned", older(HEARD, "heard"))[0])

    def test_times_for_another_text_are_refused(self):
        self.assertEqual(422, self.request("/v1/learned", learned(text="0" * 32))[0])
        self.assertEqual(422, self.request("/v1/learned", learned(times=[[1, 2, 3, 4, 5], [6, 7, 8, 9]]))[0])
        self.plain = None
        self.assertEqual(422, self.request("/v1/learned", learned())[0])
        self.assertEqual(404, self.request(f"/v1/learned/{learned()['song']}")[0])

    def test_word_times_wait_while_lrclib_is_busy(self):
        self.unreachable = True
        self.assertEqual((200, {"ok": True, "verified": False}), self.request("/v1/learned", learned()))
        self.assertEqual(404, self.request(f"/v1/learned/{learned()['song']}")[0])
        self.unreachable = False
        ls.RECHECK_S, before = 0, ls.RECHECK_S
        try:
            self.assertEqual(200, self.request(f"/v1/learned/{learned()['song']}")[0])
        finally:
            ls.RECHECK_S = before

    def test_no_install_address_or_lyrics_are_stored(self):
        self.request("/v1/learned", learned(install="eeeeeeee-0000-0000-0000-000000000001"), "198.51.100.77")
        self.request("/v1/learned", older(install="eeeeeeee-0000-0000-0000-000000000002"), "198.51.100.78")
        rows = repr(self.tracker.db.execute("SELECT * FROM timings").fetchall())
        for kept in ("eeeeeeee-0000-0000-0000-000000000001", "198.51.100.77", "Wohnung", "Schloss"):
            self.assertNotIn(kept, rows)

    # The corrections tests of the base class run here too, with this tracker's settings; they are covered there.
    test_votes_become_a_common_correction_and_can_be_taken_back = None
    test_nothing_identifying_is_stored = None
    test_bad_requests = None


class ForgetLyricsTest(unittest.TestCase):
    def test_lyrics_kept_by_the_older_server_are_dropped_and_their_times_kept(self):
        with tempfile.TemporaryDirectory() as data:
            db = sqlite3.connect(os.path.join(data, "tracker.db"))
            db.execute(
                "CREATE TABLE learned (song TEXT NOT NULL, install TEXT NOT NULL, network TEXT NOT NULL, "
                "kind TEXT NOT NULL, verified INTEGER NOT NULL, checked INTEGER NOT NULL, matched REAL NOT NULL, "
                "artist TEXT NOT NULL, title TEXT NOT NULL, album TEXT NOT NULL, duration_ms INTEGER NOT NULL, "
                "lrc TEXT NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY (song, install))")
            key = learned()["song"]
            db.execute("INSERT INTO learned VALUES (?, 'i1', 'n1', 'aligned', 1, 1, 0.9, 'Peter Petersen', "
                       "'Staubsaugerliebe', 'Staubsaugerliebe', 210841, ?, 1)", (key, ALIGNED))
            db.execute("INSERT INTO learned VALUES (?, 'i2', 'n2', 'heard', 0, 1, 0, 'SUV WHATEVER', "
                       "'Arschloch Lokomotive', 'Arschloch Lokomotive', 180000, ?, 1)", ("1" * 32, HEARD))
            db.commit()
            db.close()
            tracker = ls.Tracker(ls.Settings(data_dir=data), plain_lyrics=lambda *names: PLAIN)
            self.assertIsNone(tracker.db.execute(
                "SELECT name FROM sqlite_master WHERE name = 'learned'").fetchone())
            self.assertEqual({"times": TIMES, "text": TEXT, "kind": "aligned", "players": 1}, tracker.learned_for(key))
            self.assertIsNone(tracker.learned_for("1" * 32))
            tracker.db.close()
            with open(os.path.join(data, "tracker.db"), "rb") as f:
                stored = f.read()
            for word in ("Wohnung", "Schloss", "Haarschlacht", "Lokomotive"):
                self.assertNotIn(word.encode(), stored)


if __name__ == "__main__":
    unittest.main()
