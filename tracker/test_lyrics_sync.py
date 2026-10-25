import json
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


if __name__ == "__main__":
    unittest.main()
