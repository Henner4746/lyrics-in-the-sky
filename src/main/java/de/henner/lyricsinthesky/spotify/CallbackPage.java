package de.henner.lyricsinthesky.spotify;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Random;

/**
 * The page the browser shows after returning from Spotify's consent page: a Minecraft dusk with pixel stars and
 * blocky hills, and the result written into the sky in the mod's own handwriting, letter by letter.
 */
final class CallbackPage {
    private static final String FONT_RESOURCE = "/assets/lyricsinthesky/font/kalam-bold.ttf";
    private static String fontFace;

    private CallbackPage() {}

    static String render(boolean success, String error) {
        String word = success ? "verbunden" : "nicht verbunden";
        String note = success
                ? "Spotify ist jetzt mit Lyrics in the Sky verbunden. Du kannst diesen Tab schließen und zurück ins Spiel."
                : "access_denied".equals(error)
                        ? "Du hast die Zustimmung abgelehnt. In Minecraft kannst du es jederzeit noch einmal versuchen."
                        : "Etwas hat nicht geklappt" + (error.isBlank() ? "" : " (" + escape(error) + ")")
                                + ". Versuch es in Minecraft noch einmal.";
        return TEMPLATE
                .replace("{{FONT}}", fontFace())
                .replace("{{STARS}}", stars())
                .replace("{{HILLS}}", hills())
                .replace("{{WORD_LABEL}}", word.substring(0, 1).toUpperCase() + word.substring(1))
                .replace("{{LETTERS}}", letters(word, success ? "#fff4ea" : "#ffb3d6"))
                .replace("{{SUB}}", success ? "Spotify &amp; Lyrics in the Sky" : "Spotify &amp; Lyrics in the Sky – noch nicht")
                .replace("{{NOTE}}", note);
    }

    /** One span per letter, each popping in a little later, the way words appear in the sky in game. */
    private static String letters(String word, String color) {
        StringBuilder out = new StringBuilder();
        int visible = 0;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c == ' ') {
                out.append("<span class=\"gap\"></span>");
                continue;
            }
            out.append("<span style=\"animation-delay:").append(250 + visible * 90).append("ms;color:").append(color)
                    .append("\">").append(c).append("</span>");
            visible++;
        }
        return out.toString();
    }

    /** Square pixel stars at fixed spots, as a box-shadow list on one 3 px element. */
    private static String stars() {
        Random random = new Random(26_3);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 90; i++) {
            if (i > 0) {
                out.append(',');
            }
            int x = random.nextInt(100);
            int y = random.nextInt(62);
            String alpha = random.nextInt(3) == 0 ? ".9" : ".55";
            out.append(x).append("vw ").append(y).append("vh 0 rgba(255,255,255,").append(alpha).append(')');
        }
        return out.toString();
    }

    /** Blocky hills with a few trees, drawn as rectangles in a 160×40 grid. */
    private static String hills() {
        Random random = new Random(1_337);
        StringBuilder out = new StringBuilder();
        int height = 12;
        for (int x = 0; x < 160; x += 4) {
            height = Math.max(6, Math.min(22, height + random.nextInt(5) - 2));
            out.append("<rect x=\"").append(x).append("\" y=\"").append(40 - height).append("\" width=\"4\" height=\"")
                    .append(height).append("\"/>");
            if (random.nextInt(7) == 0) {
                int top = 40 - height;
                // Trunk, a blocky crown and a narrower top, like an oak at the horizon.
                out.append("<rect x=\"").append(x + 1).append("\" y=\"").append(top - 4).append("\" width=\"2\" height=\"4\"/>");
                out.append("<rect x=\"").append(x - 1).append("\" y=\"").append(top - 10).append("\" width=\"6\" height=\"6\"/>");
                out.append("<rect x=\"").append(x).append("\" y=\"").append(top - 12).append("\" width=\"4\" height=\"2\"/>");
            }
        }
        return out.toString();
    }

    /** The mod's own font, embedded so the page needs nothing from the internet. */
    private static synchronized String fontFace() {
        if (fontFace == null) {
            try (InputStream in = CallbackPage.class.getResourceAsStream(FONT_RESOURCE)) {
                fontFace = in == null ? ""
                        : "@font-face{font-family:Kalam;src:url(data:font/ttf;base64,"
                                + Base64.getEncoder().encodeToString(in.readAllBytes()) + ") format('truetype');}";
            } catch (IOException e) {
                fontFace = "";
            }
        }
        return fontFace;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String TEMPLATE = """
            <!doctype html>
            <html lang="de">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Lyrics in the Sky</title>
            <style>
            {{FONT}}
            html, body { margin: 0; height: 100%; }
            body {
              overflow: hidden;
              background: linear-gradient(to top, #7a3b3f 0%, #4a2d55 18%, #22224c 42%, #141737 70%, #0f1230 100%);
              color: #eaf0ff;
              font-family: Kalam, "Segoe Print", "Comic Sans MS", cursive;
            }
            .stars { position: fixed; top: 0; left: 0; width: 3px; height: 3px; box-shadow: {{STARS}}; }
            .moon {
              position: fixed; top: 9vh; right: 12vw; width: 56px; height: 56px; background: #e9e4cf;
              box-shadow: inset -14px 0 0 #cfc8ad, 0 0 40px rgba(233, 228, 207, .35);
            }
            .moon::before, .moon::after { content: ""; position: absolute; background: #cfc8ad; }
            .moon::before { width: 14px; height: 14px; left: 10px; top: 12px; }
            .moon::after { width: 7px; height: 7px; left: 28px; top: 34px; }
            .hills { position: fixed; left: 0; right: 0; bottom: 0; width: 100%; height: 30vh; fill: #090b1a; }
            .sky { position: fixed; left: 0; right: 0; top: 24vh; text-align: center; transform: rotate(-4deg); }
            h1 { margin: 0; font-size: clamp(56px, 12vw, 140px); font-weight: 700; line-height: 1; letter-spacing: .01em; }
            h1 span {
              display: inline-block; opacity: 0;
              text-shadow: 0 0 18px rgba(255, 210, 160, .35), 2px 3px 0 rgba(10, 12, 24, .45);
              animation: pop .55s cubic-bezier(.34, 1.56, .64, 1) forwards, drift 4.5s ease-in-out 1.2s infinite alternate;
            }
            h1 .gap { width: .35em; }
            .sub { margin-top: .4em; font-size: clamp(20px, 3vw, 32px); color: #c7d6ff; opacity: 0; animation: fade 1s ease 1.4s forwards; }
            .note {
              position: fixed; left: 0; right: 0; bottom: 7vh; text-align: center; padding: 0 24px;
              font: 15px/1.5 Consolas, "Courier New", monospace; color: #9aa3c7;
            }
            @keyframes pop { from { opacity: 0; transform: translateY(18px) scale(.5); } to { opacity: 1; transform: none; } }
            @keyframes drift { to { transform: translateY(-5px); } }
            @keyframes fade { to { opacity: .9; } }
            @media (prefers-reduced-motion: reduce) { h1 span, .sub { animation: none; opacity: 1; } }
            </style>
            </head>
            <body>
            <div class="stars"></div>
            <div class="moon"></div>
            <div class="sky">
              <h1 aria-label="{{WORD_LABEL}}">{{LETTERS}}</h1>
              <div class="sub">{{SUB}}</div>
            </div>
            <svg class="hills" viewBox="0 0 160 40" preserveAspectRatio="xMidYMax slice" shape-rendering="crispEdges" aria-hidden="true">{{HILLS}}</svg>
            <p class="note">{{NOTE}}</p>
            </body>
            </html>
            """;
}
