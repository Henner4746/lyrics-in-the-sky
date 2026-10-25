package de.henner.lyricsinthesky;

/** What the player is told about each word time service: its name and, honestly, what it gets. */
public final class WordSourceInfo {
    private WordSourceInfo() {}

    public static String label(String name) {
        return switch (name) {
            case "deezer" -> "Deezer";
            case "lyricsplus" -> "LyricsPlus";
            case "netease" -> "NetEase";
            case "kugou" -> "Kugou";
            default -> name;
        };
    }

    public static String hint(String name) {
        return switch (name) {
            case "deezer" -> "Deezer hat zu vielen Songs Wortzeiten (von Musixmatch, wie Spotify) und passte in Tests am "
                    + "häufigsten. Inoffiziell über den Web-Player mit anonymer Sitzung, ohne Konto. Bekommt Künstler und Titel.";
            case "lyricsplus" -> "LyricsPlus, der Server der Erweiterung YouLy+, liest Wortzeiten von Apple Music und "
                    + "QQ Music. Inoffiziell. Bekommt Titel, Künstler, Album und Länge.";
            case "netease" -> "NetEase Cloud Music (China) hat Wortzeiten auch zu vielen westlichen Songs. Inoffiziell, "
                    + "ohne Konto. Bekommt Künstler und Titel.";
            case "kugou" -> "Kugou Music (China) hat Wortzeiten, oft dieselben wie QQ Music. Inoffiziell, ohne Konto. "
                    + "Bekommt Künstler, Titel und Länge.";
            default -> "";
        };
    }
}
