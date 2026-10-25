package de.henner.lyricsinthesky.spotify;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.henner.lyricsinthesky.util.Browser;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The player's Spotify login: "Connect" opens Spotify's consent page in the browser, the player logs in and
 * clicks "Agree", and the browser comes back to a tiny local page. Uses Authorization Code with PKCE, so only
 * the player's own client ID is needed. Tokens stay in {@code config/lyricsinthesky/spotify.json}.
 */
public final class SpotifyAccount {
    private static final Logger LOGGER = LoggerFactory.getLogger("lyricsinthesky");
    public static final int PORT = 43217;
    public static final String REDIRECT_URI = "http://127.0.0.1:" + PORT + "/callback";
    public static final String DASHBOARD_URL = "https://developer.spotify.com/dashboard";
    private static final String SCOPES = "user-read-currently-playing user-read-playback-state";
    private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(5);
    static final String NOT_REGISTERED = "App kennt dein Konto nicht – eigene App anlegen";
    private static final Gson GSON = new Gson();

    public enum State { NOT_CONFIGURED, DISCONNECTED, WAITING_FOR_BROWSER, CONNECTED, ERROR }

    private record Tokens(String clientId, String accessToken, String refreshToken, long expiresAtMs, String displayName) {}

    private final Path tokenFile;
    private final Supplier<String> clientId;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private volatile Tokens tokens;
    private volatile State state = State.DISCONNECTED;
    private volatile String problem = "";
    private Thread loginThread;

    public SpotifyAccount(Path tokenFile, Supplier<String> clientId) {
        this.tokenFile = tokenFile;
        this.clientId = clientId;
        load();
    }

    public State state() {
        if (state != State.WAITING_FOR_BROWSER && clientId.get().isBlank()) {
            return State.NOT_CONFIGURED;
        }
        return state;
    }

    public boolean isConnected() {
        return state() == State.CONNECTED;
    }

    /** One line for the settings screen and {@code /skylyrics status}. */
    public String statusText() {
        return switch (state()) {
            case NOT_CONFIGURED -> "Spotify: Client-ID fehlt";
            case DISCONNECTED -> "Spotify: nicht verbunden";
            case WAITING_FOR_BROWSER -> "Spotify: warte auf den Browser …";
            case CONNECTED -> "Spotify: verbunden" + (tokens != null && !tokens.displayName().isBlank()
                    ? " als " + tokens.displayName() : "");
            case ERROR -> "Spotify: " + problem;
        };
    }

    /** Opens Spotify's consent page; the login finishes in the background. */
    public void connect() {
        connect(Browser::open);
    }

    /**
     * Like {@link #connect()}, with a custom way to show the consent page (used by tests that drive a browser
     * themselves).
     */
    public synchronized void connect(Predicate<String> openConsentPage) {
        String id = clientId.get();
        if (id.isBlank() || state == State.WAITING_FOR_BROWSER) {
            return;
        }
        state = State.WAITING_FOR_BROWSER;
        loginThread = new Thread(() -> login(id, openConsentPage), "LyricsInTheSky-SpotifyLogin");
        loginThread.setDaemon(true);
        loginThread.start();
    }

    public synchronized void disconnect() {
        tokens = null;
        state = State.DISCONNECTED;
        problem = "";
        try {
            Files.deleteIfExists(tokenFile);
        } catch (IOException e) {
            LOGGER.warn("Could not delete {}: {}", tokenFile, e.getMessage());
        }
    }

    private void login(String id, Predicate<String> openConsentPage) {
        String verifier = Pkce.randomString(48);
        String expectedState = Pkce.randomString(16);
        try (LoopbackServer server = LoopbackServer.open(PORT)) {
            String url = "https://accounts.spotify.com/authorize?" + form(Map.of(
                    "client_id", id,
                    "response_type", "code",
                    "redirect_uri", REDIRECT_URI,
                    "scope", SCOPES,
                    "state", expectedState,
                    "code_challenge_method", "S256",
                    "code_challenge", Pkce.challenge(verifier)));
            if (!openConsentPage.test(url)) {
                fail("Browser ließ sich nicht öffnen");
                return;
            }
            // The login is finished before the browser gets its page, so the page tells the real outcome.
            server.awaitCallback(LOGIN_TIMEOUT, params -> {
                String error = finish(id, verifier, expectedState, params);
                return CallbackPage.render(error.isEmpty(), error);
            });
        } catch (SocketTimeoutException e) {
            fail("Anmeldung abgelaufen (5 Minuten)");
        } catch (java.net.BindException e) {
            fail("Port " + PORT + " ist belegt");
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Spotify login failed: {}", e.getMessage());
            fail("Anmeldung fehlgeschlagen");
        }
    }

    /** Completes the login from the callback; returns "" on success, otherwise an error code for the page. */
    private String finish(String id, String verifier, String expectedState, Map<String, String> params) {
        if (!expectedState.equals(params.get("state"))) {
            fail("Antwort passte nicht zur Anfrage");
            return "state_mismatch";
        }
        if (params.containsKey("error")) {
            String error = params.get("error");
            fail("access_denied".equals(error) ? "Zustimmung abgelehnt" : error);
            return error;
        }
        try {
            JsonObject token = tokenRequest(Map.of(
                    "grant_type", "authorization_code",
                    "code", params.getOrDefault("code", ""),
                    "redirect_uri", REDIRECT_URI,
                    "client_id", id,
                    "code_verifier", verifier));
            store(id, token, null);
            String name = fetchDisplayName();
            if (name == null) {
                invalidate(NOT_REGISTERED);
                return CallbackPage.NOT_REGISTERED;
            }
            tokens = new Tokens(id, tokens.accessToken(), tokens.refreshToken(), tokens.expiresAtMs(), name);
            save();
            state = State.CONNECTED;
            LOGGER.info("Spotify connected");
            return "";
        } catch (IOException | InterruptedException | RuntimeException e) {
            LOGGER.warn("Spotify login failed: {}", e.getMessage());
            fail("Anmeldung fehlgeschlagen");
            return "token_exchange_failed";
        }
    }

    /** A valid access token, refreshed when it is about to expire. */
    public String accessToken() throws IOException, InterruptedException {
        Tokens current = tokens;
        if (current == null) {
            throw new IOException("not connected");
        }
        if (System.currentTimeMillis() < current.expiresAtMs() - 60_000) {
            return current.accessToken();
        }
        synchronized (this) {
            current = tokens;
            if (current == null) {
                throw new IOException("not connected");
            }
            if (System.currentTimeMillis() >= current.expiresAtMs() - 60_000) {
                JsonObject token;
                try {
                    token = tokenRequest(Map.of(
                            "grant_type", "refresh_token",
                            "refresh_token", current.refreshToken(),
                            "client_id", current.clientId()));
                } catch (IOException e) {
                    // invalid_grant: access was revoked or the refresh token ran out (Spotify: 180 days).
                    if (e.getMessage() != null && e.getMessage().contains("invalid_grant")) {
                        invalidate("Anmeldung abgelaufen – bitte neu verbinden");
                    }
                    throw e;
                }
                store(current.clientId(), token, current);
                save();
            }
            return tokens.accessToken();
        }
    }

    /** Called when Spotify rejects the token for good, e.g. after the player removed access. */
    void invalidate(String reason) {
        tokens = null;
        fail(reason);
        try {
            Files.deleteIfExists(tokenFile);
        } catch (IOException ignored) {
            // nothing more to clean up
        }
    }

    private void store(String id, JsonObject token, Tokens previous) {
        String access = token.get("access_token").getAsString();
        // Spotify may or may not rotate the refresh token.
        String refresh = token.has("refresh_token") ? token.get("refresh_token").getAsString()
                : previous != null ? previous.refreshToken() : "";
        long expiresAt = System.currentTimeMillis() + token.get("expires_in").getAsLong() * 1000L;
        tokens = new Tokens(id, access, refresh, expiresAt, previous != null ? previous.displayName() : "");
    }

    private JsonObject tokenRequest(Map<String, String> params) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(params)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            // The body names the problem (e.g. invalid_grant) but never contains a token.
            throw new IOException("token endpoint answered " + response.statusCode() + ": " + response.body());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    /**
     * The player's display name, "" when Spotify does not tell, and {@code null} when Spotify refuses the
     * account for this app (an app in development mode only serves the accounts added to it).
     */
    private String fetchDisplayName() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + tokens.accessToken())
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 403) {
                return null;
            }
            if (response.statusCode() == 200) {
                JsonObject me = JsonParser.parseString(response.body()).getAsJsonObject();
                if (me.has("display_name") && !me.get("display_name").isJsonNull()) {
                    return me.get("display_name").getAsString();
                }
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            LOGGER.debug("Spotify profile not available: {}", e.getMessage());
        }
        return "";
    }

    private void load() {
        if (!Files.exists(tokenFile)) {
            return;
        }
        try {
            Tokens loaded = GSON.fromJson(Files.readString(tokenFile, StandardCharsets.UTF_8), Tokens.class);
            if (loaded != null && loaded.refreshToken() != null && !loaded.refreshToken().isBlank()) {
                tokens = new Tokens(loaded.clientId(), loaded.accessToken(), loaded.refreshToken(), loaded.expiresAtMs(),
                        loaded.displayName() == null ? "" : loaded.displayName());
                state = loaded.clientId() != null && loaded.clientId().equals(clientId.get())
                        ? State.CONNECTED : State.DISCONNECTED;
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read {}: {}", tokenFile, e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(tokenFile.getParent());
            Files.writeString(tokenFile, GSON.toJson(tokens), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not save {}: {}", tokenFile, e.getMessage());
        }
    }

    private void fail(String reason) {
        problem = reason;
        state = State.ERROR;
    }

    static String form(Map<String, String> params) {
        StringJoiner joiner = new StringJoiner("&");
        new LinkedHashMap<>(params).forEach((key, value) -> joiner.add(
                URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return joiner.toString();
    }
}
