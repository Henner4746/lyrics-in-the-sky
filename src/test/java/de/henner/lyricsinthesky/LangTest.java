package de.henner.lyricsinthesky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LangTest {
    @AfterEach
    void backToGerman() {
        Lang.follow(() -> "auto", () -> "de_de");
    }

    @Test
    void theEnglishTextsAreInTheMod() {
        Map<String, String> english = Lang.dictionary();
        assertTrue(english.size() > 350, english.size() + " texts");
        for (Map.Entry<String, String> text : english.entrySet()) {
            assertFalse(text.getValue().isBlank(), text.getKey());
            assertFalse(text.getValue().contains("„"), "German quotes in the English of " + text.getKey());
        }
    }

    @Test
    void germanUnlessEnglishIsChosenOrTheGameSpeaksAnotherLanguage() {
        Lang.follow(() -> "auto", () -> "de_at");
        assertEquals("Fertig", Lang.t("Fertig"));
        Lang.follow(() -> "auto", () -> "en_us");
        assertEquals("Done", Lang.t("Fertig"));
        Lang.follow(() -> "auto", () -> "fr_fr");
        assertTrue(Lang.english());
        Lang.follow(() -> "de", () -> "en_us");
        assertEquals("Fertig", Lang.t("Fertig"));
        Lang.follow(() -> "en", () -> "de_de");
        assertEquals("Done", Lang.t("Fertig"));
    }

    @Test
    void aTextWithoutEnglishStaysAsItIs() {
        Lang.follow(() -> "en", () -> "de_de");
        assertEquals("Ein Song ohne Englisch", Lang.t("Ein Song ohne Englisch"));
        assertEquals("", Lang.t(""));
    }

    @Test
    void valuesInTheirLanguagesNumbers() {
        assertEquals("die Wörter kamen 1,5 s zu spät", Lang.f("die Wörter kamen %.1f s zu %s", "the words came %.1f s too %s",
                1.5, Lang.of("spät", "late")));
        Lang.follow(() -> "en", () -> "de_de");
        assertEquals("the words came 1.5 s too late", Lang.f("die Wörter kamen %.1f s zu %s", "the words came %.1f s too %s",
                1.5, Lang.of("spät", "late")));
    }

    @Test
    void everyNameAndPresetInTheMenusHasItsEnglish() {
        List<String> names = new ArrayList<>();
        names.addAll(SkyLyricsConfig.MODE_NAMES.values());
        names.addAll(SkyLyricsConfig.SCREEN_STYLE_NAMES.values());
        names.addAll(SkyLyricsConfig.PRESET_NAMES.values());
        names.addAll(SkyLyricsConfig.LANGUAGE_NAMES.values());
        for (String preset : SkyLyricsConfig.PRESETS) {
            names.add(SkyLyricsConfig.presetHint(preset));
        }
        for (String source : SkyLyricsConfig.WORD_SOURCES) {
            names.add(WordSourceInfo.hint(source));
            names.add("Wortzeiten von " + WordSourceInfo.label(source));
        }
        Map<String, String> english = Lang.dictionary();
        for (String name : names) {
            assertTrue(english.containsKey(name), "no English for " + name);
        }
    }
}
