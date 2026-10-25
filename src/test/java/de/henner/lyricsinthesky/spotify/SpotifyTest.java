package de.henner.lyricsinthesky.spotify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.henner.lyricsinthesky.nowplaying.NowPlaying;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class SpotifyTest {
    @Test
    void pkceMatchesRfc7636Example() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        String verifier = Pkce.randomString(48);
        assertEquals(64, verifier.length());
        assertTrue(verifier.matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void formEncodesSpacesAndColons() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("scope", "user-read-currently-playing user-read-playback-state");
        params.put("redirect_uri", SpotifyAccount.REDIRECT_URI);
        assertEquals("scope=user-read-currently-playing+user-read-playback-state"
                + "&redirect_uri=http%3A%2F%2F127.0.0.1%3A43217%2Fcallback", SpotifyAccount.form(params));
    }

    @Test
    void loopbackServerAnswersTheCallbackAndIgnoresOtherRequests() throws Exception {
        try (LoopbackServer server = LoopbackServer.open(0)) {
            CompletableFuture<Map<String, String>> result = CompletableFuture.supplyAsync(() -> {
                try {
                    return server.awaitCallback(Duration.ofSeconds(10), query -> CallbackPage.render(true, ""));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            // A timeout so a broken server fails the test instead of hanging the build.
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String base = "http://127.0.0.1:" + server.port();
            HttpResponse<String> favicon = http.send(HttpRequest.newBuilder(URI.create(base + "/favicon.ico"))
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, favicon.statusCode());
            HttpResponse<String> callback = http.send(HttpRequest.newBuilder(
                            URI.create(base + "/callback?code=abc%2B1&state=xyz"))
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, callback.statusCode());
            assertTrue(callback.body().contains("aria-label=\"Verbunden\""));
            Map<String, String> query = result.get();
            assertEquals("abc+1", query.get("code"));
            assertEquals("xyz", query.get("state"));
        }
    }

    @Test
    void callbackPageExplainsADeniedConsent() {
        String page = CallbackPage.render(false, "access_denied");
        assertTrue(page.contains("Zustimmung abgelehnt"));
        assertFalse(page.contains("{{"));
        // The font is embedded, so the page loads nothing from the internet.
        assertTrue(page.contains("data:font/ttf;base64,"));
        assertFalse(page.contains("http://") || page.contains("https://"));
    }

    @Test
    void parsesATrack() {
        String body = """
                {"timestamp": 1792900000000, "progress_ms": 61500, "is_playing": true,
                 "currently_playing_type": "track",
                 "item": {"name": "Sonne", "duration_ms": 272000, "album": {"name": "Mutter"},
                          "artists": [{"name": "Rammstein"}, {"name": "Someone"}]}}
                """;
        NowPlaying track = SpotifyNowPlayingSource.parse(body, 1_000);
        assertEquals("Sonne", track.title());
        assertEquals("Rammstein, Someone", track.artist());
        assertEquals("Mutter", track.album());
        assertTrue(track.playing());
        assertEquals(61_500, track.positionMs());
        assertEquals(272_000, track.durationMs());
        assertEquals(1_000, track.updatedAtMs());
    }

    @Test
    void parsesAnEpisodeAndSkipsAds() {
        NowPlaying episode = SpotifyNowPlayingSource.parse("""
                {"progress_ms": 5000, "is_playing": false,
                 "item": {"name": "Folge 1", "duration_ms": 1800000, "show": {"name": "Ein Podcast"}}}
                """, 0);
        assertEquals("Ein Podcast", episode.artist());
        assertFalse(episode.playing());
        assertNull(SpotifyNowPlayingSource.parse("{\"currently_playing_type\": \"ad\", \"item\": null}", 0));
    }
}
