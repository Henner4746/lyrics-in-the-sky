package de.henner.lyricsinthesky.spotify;

/** The page the browser shows after returning from Spotify's consent page. */
final class CallbackPage {
    private CallbackPage() {}

    static String render(boolean success, String error) {
        String title = success ? "Verbunden!" : "Nicht verbunden";
        String text = success
                ? "Spotify ist jetzt mit Lyrics in the Sky verbunden. Du kannst dieses Fenster schließen und zurück zu Minecraft."
                : "access_denied".equals(error)
                        ? "Du hast die Zustimmung abgelehnt. In Minecraft kannst du es jederzeit erneut versuchen."
                        : "Etwas hat nicht geklappt" + (error.isBlank() ? "" : " (" + escape(error) + ")")
                                + ". Versuch es in Minecraft noch einmal.";
        return """
                <!doctype html>
                <html lang="de">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Lyrics in the Sky</title>
                <style>
                  :root { color-scheme: dark; }
                  body { margin: 0; min-height: 100vh; display: grid; place-items: center;
                         font: 17px/1.5 system-ui, sans-serif; color: #eaf4ff;
                         background: radial-gradient(ellipse at 50%% 120%%, #6a2c80 0%%, #1a1446 45%%, #0b1033 100%%); }
                  main { max-width: 32rem; padding: 2rem 2.4rem; text-align: center;
                         background: rgba(14, 15, 26, .55); border: 1px solid rgba(255,255,255,.12); border-radius: 18px; }
                  h1 { margin: 0 0 .4rem; font-size: 2rem; color: %s; }
                  p { margin: 0; opacity: .9; }
                  .note { margin-top: 1.4rem; font-size: .85rem; opacity: .6; }
                </style>
                </head>
                <body>
                <main>
                  <h1>%s</h1>
                  <p>%s</p>
                  <p class="note">Lyrics in the Sky · Minecraft-Mod</p>
                </main>
                </body>
                </html>
                """.formatted(success ? "#7cf7e4" : "#ff9ed2", title, text);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
