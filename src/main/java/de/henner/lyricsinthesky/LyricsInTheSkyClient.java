package de.henner.lyricsinthesky;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import de.henner.lyricsinthesky.dev.LiveTest;
import de.henner.lyricsinthesky.dev.SelfTest;
import de.henner.lyricsinthesky.dev.Showcase;
import de.henner.lyricsinthesky.gui.GlassMenuScreen;
import de.henner.lyricsinthesky.gui.SettingsScreen;
import de.henner.lyricsinthesky.hoergenau.Hoergenau;
import de.henner.lyricsinthesky.hoergenau.SharedLearned;
import de.henner.lyricsinthesky.hoergenau.WhisperEngine;
import de.henner.lyricsinthesky.lyrics.BetterLyricsClient;
import de.henner.lyricsinthesky.lyrics.LrclibClient;
import de.henner.lyricsinthesky.lyrics.LyricsCache;
import de.henner.lyricsinthesky.lyrics.DeezerClient;
import de.henner.lyricsinthesky.lyrics.KugouClient;
import de.henner.lyricsinthesky.lyrics.LyricsPlusClient;
import de.henner.lyricsinthesky.lyrics.NeteaseClient;
import de.henner.lyricsinthesky.lyrics.SyncLrcClient;
import de.henner.lyricsinthesky.lyrics.WordTimeFinder;
import de.henner.lyricsinthesky.nowplaying.NowPlayingSource;
import de.henner.lyricsinthesky.nowplaying.PreferredNowPlayingSource;
import de.henner.lyricsinthesky.render.GlyphWarmup;
import de.henner.lyricsinthesky.render.NowPlayingHud;
import de.henner.lyricsinthesky.render.ScreenLyrics;
import de.henner.lyricsinthesky.render.SkyLyricsRenderer;
import de.henner.lyricsinthesky.spotify.SpotifyAccount;
import de.henner.lyricsinthesky.spotify.SpotifyNowPlayingSource;
import de.henner.lyricsinthesky.timing.SongTimings;
import de.henner.lyricsinthesky.timing.TimingTracker;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LyricsInTheSkyClient implements ClientModInitializer {
    public static final String MOD_ID = "lyricsinthesky";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static LyricsController controller;
    private static SkyLyricsConfig config;
    private static SpotifyAccount spotify;
    private static KeyMapping settingsKey;
    private static KeyMapping toggleKey;
    private static KeyMapping earlierKey;
    private static KeyMapping laterKey;
    /** Ticks until the settings open; set by the command, which runs while the chat screen is still closing. */
    private static int openSettingsIn = -1;
    /** LyricsPlus was offered in chat this game session. */
    private static boolean lyricsPlusOffered;
    /** Hoergenau was offered in chat this game session. */
    private static boolean hoergenauOffered;

    /** {@code null} until the client is initialized. */
    public static LyricsController controller() {
        return controller;
    }

    @Override
    public void onInitializeClient() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        String version = FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("dev");
        config = SkyLyricsConfig.load(configDir.resolve(MOD_ID + ".json"));

        // Without login: the OS media session (Spotify, Amazon Music, browsers on this PC). Optional login: the
        // Spotify account, which also covers music playing on a phone or console.
        spotify = new SpotifyAccount(configDir.resolve(MOD_ID).resolve("spotify.json"), () -> config.spotifyClientId);
        NowPlayingSource spotifySource = new SpotifyNowPlayingSource(spotify);
        NowPlayingSource osSource = NowPlayingSource.forCurrentOs(configDir.resolve(MOD_ID));
        if (osSource == null) {
            LOGGER.warn("No media session support on this OS; only a connected Spotify account will work.");
        }
        NowPlayingSource source = osSource == null ? spotifySource : new PreferredNowPlayingSource(spotifySource, osSource);
        LrclibClient lrclib = new LrclibClient(version);
        SongTimings timings = new SongTimings(configDir.resolve(MOD_ID).resolve("song-timing.json"));
        // The self test plays songs with made-up timing; nothing of it goes to the tracker.
        TimingTracker tracker = new TimingTracker(timings, () -> config.shareTiming && !SelfTest.isEnabled(),
                config.installId, version);
        LyricsPlusClient lyricsPlus = new LyricsPlusClient(version);
        NeteaseClient netease = new NeteaseClient(version);
        // In the order measured best: Deezer fitted most songs, LyricsPlus (Apple, QQ) and NetEase add others, Kugou few;
        // SyncLRC and Better Lyrics came last and only have songs someone asked for before.
        WordTimeFinder wordTimes = new WordTimeFinder(List.of(new DeezerClient(version), lyricsPlus, netease,
                new KugouClient(version), new SyncLrcClient(version), new BetterLyricsClient(version)));
        LyricsCache store = new LyricsCache(configDir.resolve(MOD_ID).resolve("lyrics"));
        controller = new LyricsController(source, lrclib, wordTimes, lyricsPlus, store, config, timings, tracker);
        // Off unless switched on; listens only on Windows and only to songs nobody has word times for.
        Hoergenau hoergenau = new Hoergenau(configDir.resolve(MOD_ID).resolve("hoergenau"), config, store,
                new Hoergenau.Notifier() {
                    @Override
                    public void toast(String title, String detail) {
                        LyricsInTheSkyClient.toast(title, detail);
                    }

                    @Override
                    public void chat(String message) {
                        tell(message);
                    }
                }, controller::forget);
        controller.listenWith(hoergenau);
        // What anyone's Hoergenau learned counts for all: shared through the tracker, unless switched off.
        SharedLearned shared = new SharedLearned(() -> config.shareLearned, config.installId, version);
        hoergenau.shareWith(shared);
        hoergenau.fixSyncWith((track, lyrics, offsetMs) -> controller.applyHeardOffset(track, lyrics, offsetMs));
        // Only the player's own sound when Windows can tell it apart: no voice chat, videos or game sounds in it.
        hoergenau.recordFrom(source::localPlayer);
        controller.shareLearnedWith(shared);

        // Started on the first world join rather than at launch: nothing to show in menus, and it keeps the
        // helper process out of the busy resource-loading phase.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> source.start());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> source.stop());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            controller.tick();
            handleKeys(client);
            offerLyricsPlus(client);
            offerHoergenau(client);
            if (client.player != null) {
                GlyphWarmup.lyrics(controller.currentLyrics(), controller.config().font);
                GlyphWarmup.tick(client);
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            GlyphWarmup.usualLetters(controller.config().font);
            noteLrclib(client);
            noteTiming(client);
            noteLearned(client);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SkyLyricsRenderer.INSTANCE.reset());
        HudElementRegistry.attachElementBefore(VanillaHudElements.BOSS_BAR,
                Identifier.fromNamespaceAndPath(MOD_ID, "now_playing"), new NowPlayingHud());
        HudElementRegistry.attachElementBefore(VanillaHudElements.BOSS_BAR,
                Identifier.fromNamespaceAndPath(MOD_ID, "screen_lyrics"), new ScreenLyrics());
        registerKeys();
        registerCommands();
        if (SelfTest.isEnabled()) {
            SelfTest.register(controller, lrclib);
        }
        if (LiveTest.isEnabled()) {
            LiveTest.register(controller, spotify);
        }
        if (Showcase.isEnabled()) {
            Showcase.register(controller);
        }
    }

    private static void registerKeys() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        int unbound = InputConstants.UNKNOWN.getValue();
        settingsKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.lyricsinthesky.settings", InputConstants.KEY_RSHIFT, category));
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.lyricsinthesky.toggle", unbound, category));
        earlierKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.lyricsinthesky.earlier", unbound, category));
        laterKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.lyricsinthesky.later", unbound, category));
    }

    /** Opens the settings screen on top of whatever is shown now. */
    public static void openSettings(Minecraft client) {
        Screen parent = client.gui.screen();
        client.gui.setScreen("fancy".equals(config.menuStyle) ? new SettingsScreen(parent, controller, spotify)
                : new GlassMenuScreen(parent, controller, spotify));
    }

    private static void handleKeys(Minecraft client) {
        if (openSettingsIn >= 0 && openSettingsIn-- == 0 && client.gui.screen() == null) {
            openSettings(client);
        }
        while (settingsKey.consumeClick()) {
            openSettings(client);
        }
        while (toggleKey.consumeClick()) {
            config.enabled = !config.enabled;
            config.save();
            client.gui.hud.getChat().addClientSystemMessage(prefix().append(config.enabled ? "an" : "aus"));
        }
        while (earlierKey.consumeClick()) {
            changeOffset(client, 100);
        }
        while (laterKey.consumeClick()) {
            changeOffset(client, -100);
        }
    }

    /** While a song with lyrics plays, the keys correct that song only; else they move all lyrics. */
    private static void changeOffset(Minecraft client, int deltaMs) {
        LyricsController.Song song = controller.currentSong();
        if (song != null) {
            controller.setSongOffset(song, controller.timings().offsetFor(song.id()) + deltaMs);
            client.gui.hud.getChat().addClientSystemMessage(prefix().append(songOffsetText(song)));
            return;
        }
        config.offsetMs += deltaMs;
        config.save();
        client.gui.hud.getChat().addClientSystemMessage(prefix().append(offsetText()));
    }

    private static String songOffsetText(LyricsController.Song song) {
        int offset = controller.timings().offsetFor(song.id());
        boolean own = controller.timings().own(song.id()).isPresent();
        return "Timing für „" + song.title() + "“: " + (offset >= 0 ? "+" : "") + offset + " ms"
                + (own ? "" : " (gemeinsame Korrektur)") + ", nur dieser Song. Positiv = Lyrics früher.";
    }

    private static int songOffset(CommandContext<FabricClientCommandSource> context, Integer offsetMs) {
        LyricsController.Song song = controller.currentSong();
        if (song == null) {
            return reply(context, "Gerade läuft kein Song mit Songtext. Für alle Songs: /skylyrics offset <ms>.");
        }
        controller.setSongOffset(song, offsetMs);
        return reply(context, songOffsetText(song));
    }

    /**
     * First world join with sharing on: say once that timing corrections are shared and that the mod asks the tracker
     * about every song with lyrics, and offer to switch it off.
     */
    private static void noteTiming(Minecraft client) {
        if (config.timingNoticeShown || !config.shareTiming || SelfTest.isEnabled()) {
            return;
        }
        MutableComponent message = prefix()
                .append("Korrigierst du das Timing eines Songs, wird das anonym mit henrik.click geteilt: eine zufällige "
                        + "Kennung, der Song nur als Prüfsumme. Haben 10 Spieler einen Song gleich korrigiert, passt er "
                        + "für alle. Dafür fragt die Mod dort bei jedem Song mit Songtext nach. ")
                .append(button("[Ausschalten]", "/skylyrics share off", ChatFormatting.GRAY));
        client.gui.hud.getChat().addClientSystemMessage(message);
        config.timingNoticeShown = true;
        config.save();
    }

    /**
     * First world join with sharing learned songs on: say once that songs other players' Hoergenau learned come from
     * the tracker, and what is sent for that, and offer to switch it off.
     */
    private static void noteLearned(Minecraft client) {
        if (config.learnedNoticeShown || !config.shareLearned || SelfTest.isEnabled()) {
            return;
        }
        MutableComponent message = prefix()
                .append("Songs ohne Wortzeiten, die Hörgenau bei anderen Spielern gelernt hat, holt die Mod von "
                        + "henrik.click; gefragt wird mit einer Prüfsumme des Songs. Was dein Hörgenau lernt, wird dort "
                        + "auch geteilt. ")
                .append(button("[Ausschalten]", "/skylyrics sharelearned off", ChatFormatting.GRAY));
        client.gui.hud.getChat().addClientSystemMessage(message);
        config.learnedNoticeShown = true;
        config.save();
    }

    /**
     * First world join after install: lyrics lookups are on from the start, so say once what is sent to lrclib.net
     * and offer to switch them off.
     */
    private static void noteLrclib(Minecraft client) {
        if (config.lrclibNoticeShown || !Boolean.TRUE.equals(config.lrclibAllowed) || SelfTest.isEnabled()) {
            return;
        }
        MutableComponent message = prefix()
                .append("Songtexte kommen von lrclib.net. Gesendet werden nur Titel, Künstler, Album und Länge "
                        + "des laufenden Songs. ")
                .append(button("[Ausschalten]", "/skylyrics lrclib off", ChatFormatting.GRAY));
        client.gui.hud.getChat().addClientSystemMessage(message);
        config.lrclibNoticeShown = true;
        config.save();
    }

    /**
     * A song LRCLIB has no timed lyrics for, with the word time services off: offer them once per game session, since
     * they find many of those songs. Nothing is sent to them until the player clicks.
     */
    private static void offerLyricsPlus(Minecraft client) {
        if (lyricsPlusOffered || config.wordTimes() || client.player == null || SelfTest.isEnabled()) {
            return;
        }
        LyricsController.Timeline timeline = controller.timeline(System.currentTimeMillis());
        if (timeline == null || timeline.trackKey().startsWith("demo")) {
            return;
        }
        boolean missing = timeline.state() == LyricsController.LookupState.NOT_FOUND
                || timeline.lyrics() != null && timeline.lyrics().isEstimated();
        if (!missing) {
            return;
        }
        lyricsPlusOffered = true;
        MutableComponent message = prefix()
                .append("Für „" + timeline.title() + "“ hat lrclib.net keine Lyrics mit Zeiten. „Wortgenau“ fragt zusätzlich "
                        + "inoffizielle Dienste (Grauzone: Deezer, LyricsPlus, NetEase, Kugou) und schickt ihnen Titel, "
                        + "Künstler, Album und Länge. Sie können jederzeit wegfallen. ")
                .append(button("[Einschalten]", "/skylyrics wortgenau on", ChatFormatting.GREEN));
        client.gui.hud.getChat().addClientSystemMessage(message);
    }

    private static MutableComponent button(String label, String command, ChatFormatting color) {
        return Component.literal(label).withStyle(style -> style.withColor(color).withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }

    /** Switches streamer mode; the lines in the sky are laid out anew, so the one showing changes at once too. */
    public static void setStreamerMode(SkyLyricsConfig settings, boolean on) {
        settings.streamerMode = on;
        SkyLyricsRenderer.INSTANCE.reset();
    }

    /**
     * Switches Hoergenau; switching it on says once what it does and costs. A failed download is tried again.
     */
    public static void setHoergenau(SkyLyricsConfig settings, boolean on) {
        settings.hoergenau = on;
        Hoergenau listener = controller.hoergenau();
        if (on && listener != null) {
            listener.reset();
            tell("Hörgenau ist an. Bei Songs ohne Wortzeiten hört es ab Songanfang mit, was dein PC abspielt (nur im "
                    + "Speicher), und lernt danach im Hintergrund die Wörter: etwa eine Minute je Song mit niedriger "
                    + "Priorität. Lass den Song bis zum Ende laufen, dann läuft er ab dem nächsten Mal Wort für Wort."
                    + (settings.shareLearned ? " Was es lernt, wird anonym für alle geteilt." : ""));
        }
    }

    /** Hoergenau's notes share one toast, so a new one replaces the last instead of piling up. */
    private static final SystemToast.SystemToastId HOERGENAU_TOAST = new SystemToast.SystemToastId(6_000L);

    /** A short note in the top corner, from any thread. */
    private static void toast(String title, String detail) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> SystemToast.addOrUpdate(client.gui.toastManager(), HOERGENAU_TOAST,
                Component.literal(title), Component.literal(detail)));
    }

    /**
     * A song nobody has word times for, on Windows with Hoergenau off: offer it once per game session, with a button
     * that switches it on. Nothing is downloaded or listened to until the player clicks.
     */
    private static void offerHoergenau(Minecraft client) {
        if (hoergenauOffered || config.hoergenau || !WhisperEngine.supported() || client.player == null
                || SelfTest.isEnabled() || !(lyricsPlusOffered || config.wordTimes())) {
            return;
        }
        LyricsController.Timeline timeline = controller.timeline(System.currentTimeMillis());
        if (timeline == null || timeline.trackKey().startsWith("demo")) {
            return;
        }
        boolean missing = timeline.state() == LyricsController.LookupState.NOT_FOUND
                || timeline.lyrics() != null && timeline.lyrics().isEstimated();
        if (!missing) {
            return;
        }
        hoergenauOffered = true;
        MutableComponent message = prefix()
                .append("„" + timeline.title() + "“ hat noch keine Wortzeiten. Hörgenau kann solche Songs beim Abspielen "
                        + "mithören und Wort für Wort lernen: lokal auf deinem PC, einmalig ca. "
                        + WhisperEngine.Model.of(config.hoergenauModel).megabytes()
                        + " MB. Was es lernt, bekommen alle anderen Spieler auch. ")
                .append(button("[Einschalten]", "/skylyrics hoergenau on", ChatFormatting.GREEN));
        client.gui.hud.getChat().addClientSystemMessage(message);
    }

    /** A message in chat from any thread, e.g. Hoergenau's background work. */
    private static void tell(String message) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.gui.hud.getChat().addClientSystemMessage(prefix().append(message));
            }
        });
    }

    private static MutableComponent prefix() {
        return Component.literal("[Lyrics in the Sky] ").withStyle(ChatFormatting.AQUA);
    }

    private static String offsetText() {
        return "Offset " + (config.offsetMs >= 0 ? "+" : "") + config.offsetMs + " ms (positiv = Lyrics früher)";
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("skylyrics")
                    .executes(context -> reply(context, "Einstellungen: Rechte Umschalttaste oder /skylyrics settings. "
                            + "Befehle: status, demo, on, off, mode <sky|around>, style <elegant|natural|flow|classic>, "
                            + "offset <ms>, songoffset <ms|reset>, share <on|off>, opacity <0.1-1>, scale <0.3-3>, "
                            + "height <10-70>, palette <name>, "
                            + "font <sans|script|minecraft>, follow <on|off>, title <on|off>, hud <on|off>, "
                            + "behind <on|off>, beat <on|off>, along <on|off>, preset <standard|sky|around|party|calm>, "
                            + "bar <pill|karaoke|plain|title>, barpos <top|bottom>, theme <classic|glass>, "
                            + "barsize <0.75-1.5>, barbg <0-1>, barround <0-1>, glow <0-2>, "
                            + "lrclib <on|off>, wortgenau <on|off>, source <deezer|lyricsplus|netease|kugou> <on|off>, "
                            + "spotify <connect|disconnect>"))
                    .then(ClientCommands.literal("status").executes(context ->
                            reply(context, controller.status() + " | " + spotify.statusText())))
                    .then(ClientCommands.literal("settings").executes(context -> {
                        openSettingsIn = 1;
                        return 1;
                    }))
                    .then(ClientCommands.literal("demo").executes(context -> {
                        controller.startDemo();
                        return reply(context, "Demo läuft 37 Sekunden – schau in den Himmel.");
                    }))
                    .then(choice("mode", SkyLyricsConfig.MODES, (c, value) -> c.mode = value, "Anzeige "))
                    .then(choice("style", SkyLyricsConfig.STYLES, (c, value) -> c.style = value, "Stil "))
                    .then(ClientCommands.literal("spotify")
                            .then(ClientCommands.literal("connect").executes(context -> {
                                if (config.spotifyClientId.isBlank()) {
                                    return reply(context, "Erst die Client-ID deiner Spotify-App eintragen (Rechte Umschalttaste).");
                                }
                                spotify.connect();
                                return reply(context, "Spotify öffnet sich im Browser – dort anmelden und zustimmen.");
                            }))
                            .then(ClientCommands.literal("disconnect").executes(context -> {
                                spotify.disconnect();
                                return reply(context, "Spotify getrennt.");
                            })))
                    .then(ClientCommands.literal("on").executes(context -> set(context, c -> c.enabled = true, "an")))
                    .then(ClientCommands.literal("off").executes(context -> set(context, c -> c.enabled = false, "aus")))
                    .then(ClientCommands.literal("offset")
                            .then(ClientCommands.argument("ms", IntegerArgumentType.integer(-10_000, 10_000))
                                    .executes(context -> {
                                        config.offsetMs = IntegerArgumentType.getInteger(context, "ms");
                                        config.save();
                                        return reply(context, offsetText());
                                    })))
                    .then(ClientCommands.literal("songoffset")
                            .then(ClientCommands.literal("reset").executes(context -> songOffset(context, null)))
                            .then(ClientCommands.argument("ms", IntegerArgumentType.integer(-SongTimings.MAX_OFFSET_MS,
                                    SongTimings.MAX_OFFSET_MS))
                                    .executes(context -> songOffset(context, IntegerArgumentType.getInteger(context, "ms")))))
                    .then(toggle("share", (c, value) -> c.shareTiming = value, "Timing-Korrekturen teilen"))
                    .then(ClientCommands.literal("opacity")
                            .then(ClientCommands.argument("value", FloatArgumentType.floatArg(0.1f, 1f))
                                    .executes(context -> set(context, c -> c.opacity = FloatArgumentType.getFloat(context, "value"),
                                            "Deckkraft " + FloatArgumentType.getFloat(context, "value")))))
                    .then(ClientCommands.literal("scale")
                            .then(ClientCommands.argument("value", FloatArgumentType.floatArg(0.3f, 3f))
                                    .executes(context -> set(context, c -> c.scale = FloatArgumentType.getFloat(context, "value"),
                                            "Größe " + FloatArgumentType.getFloat(context, "value")))))
                    .then(ClientCommands.literal("height")
                            .then(ClientCommands.argument("degrees", FloatArgumentType.floatArg(10f, 70f))
                                    .executes(context -> set(context, c -> c.elevationDeg = FloatArgumentType.getFloat(context, "degrees"),
                                            "Höhe " + FloatArgumentType.getFloat(context, "degrees") + "°"))))
                    .then(choice("palette", SkyLyricsConfig.PALETTES, (c, value) -> c.palette = value, "Farben "))
                    .then(choice("font", SkyLyricsConfig.FONTS, (c, value) -> c.font = value, "Schrift "))
                    .then(toggle("follow", (c, value) -> c.followView = value, "Zeilen folgen dem Blick"))
                    .then(toggle("title", (c, value) -> c.showTitleCard = value, "Songtitel am Himmel"))
                    .then(toggle("hud", (c, value) -> c.showHud = value, "Now-Playing-Anzeige"))
                    .then(toggle("behind", (c, value) -> c.skyBehindTerrain = value, "Am Himmel hinter Bergen"))
                    .then(toggle("beat", (c, value) -> c.beatBounce = value, "Im Takt wackeln"))
                    .then(toggle("along", (c, value) -> c.followAlong = value, "Mitdrehen"))
                    .then(choice("preset", SkyLyricsConfig.PRESETS, SkyLyricsConfig::applyPreset, "Vorlage "))
                    .then(choice("theme", SkyLyricsConfig.THEMES, (c, value) -> c.theme = value, "Theme "))
                    .then(choice("menu", SkyLyricsConfig.MENU_STYLES, (c, value) -> c.menuStyle = value, "Menü "))
                    .then(toggle("streamer", LyricsInTheSkyClient::setStreamerMode,
                            "Streamer-Modus (explizite Wörter versteckt)"))
                    .then(number("barsize", 0.75f, 1.5f, (c, value) -> c.hudScale = value, "Leistengröße"))
                    .then(number("barbg", 0f, 1f, (c, value) -> c.hudOpacity = value, "Hintergrund der Leiste"))
                    .then(number("barround", 0f, 1f, (c, value) -> c.hudRoundness = value, "Rundung der Leiste"))
                    .then(number("glow", 0f, 2f, (c, value) -> c.glow = value, "Leuchten"))
                    .then(choice("bar", SkyLyricsConfig.HUD_STYLES, (c, value) -> {
                        c.hudStyle = value;
                        c.showHud = true;
                    }, "Leiste "))
                    .then(choice("barpos", SkyLyricsConfig.HUD_ANCHORS, (c, value) -> {
                        c.hudAnchor = value;
                        c.hudOffsetX = 0;
                        c.hudOffsetY = 0;
                    }, "Leiste "))
                    .then(toggle("lrclib", (c, value) -> {
                        c.lrclibAllowed = value;
                        controller.retryLookups();
                    }, "Songtexte von lrclib.net"))
                    .then(toggle("wortgenau", (c, value) -> {
                        c.setWordTimes(value);
                        controller.refreshWordTimes();
                    }, "Wortgenau (alle Grauzonen-Dienste)"))
                    .then(toggle("grauzone", (c, value) -> {
                        c.setWordTimes(value);
                        controller.refreshWordTimes();
                    }, "Grauzone (alle inoffiziellen Dienste)"))
                    .then(toggle("hoergenau", (c, value) -> setHoergenau(c, value),
                            "Hörgenau (Mithören für Songs ohne Wortzeiten, nur Windows)"))
                    .then(toggle("sharelearned", (c, value) -> c.shareLearned = value,
                            "Gelerntes teilen (nur Hörgenaus Wortzeiten, kein Songtext)"))
                    .then(toggle("lyricsplus", (c, value) -> {
                        c.setWordSource("lyricsplus", value);
                        controller.refreshWordTimes();
                    }, "Wortzeiten von LyricsPlus"))
                    .then(sources());
            dispatcher.register(root);
        });
    }

    /** {@code source <name> <on|off>}: one word time service at a time. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> sources() {
        LiteralArgumentBuilder<FabricClientCommandSource> node = ClientCommands.literal("source");
        for (String name : SkyLyricsConfig.WORD_SOURCES) {
            node.then(toggle(name, (c, value) -> {
                c.setWordSource(name, value);
                controller.refreshWordTimes();
            }, "Wortzeiten von " + WordSourceInfo.label(name)));
        }
        return node;
    }

    /** {@code name <value>} for a setting between {@code min} and {@code max}; replies with the value in percent. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> number(String name, float min, float max,
            Setter<Float> setter, String label) {
        return ClientCommands.literal(name)
                .then(ClientCommands.argument("value", FloatArgumentType.floatArg(min, max))
                        .executes(context -> {
                            float value = FloatArgumentType.getFloat(context, "value");
                            return set(context, c -> setter.apply(c, value), label + " " + Math.round(value * 100) + " %");
                        }));
    }

    private interface Setter<T> {
        void apply(SkyLyricsConfig config, T value);
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> toggle(String name, Setter<Boolean> setter, String label) {
        return ClientCommands.literal(name)
                .then(ClientCommands.literal("on").executes(context -> set(context, c -> setter.apply(c, true), label + ": an")))
                .then(ClientCommands.literal("off").executes(context -> set(context, c -> setter.apply(c, false), label + ": aus")));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> choice(String name, List<String> values,
            Setter<String> setter, String label) {
        LiteralArgumentBuilder<FabricClientCommandSource> node = ClientCommands.literal(name);
        for (String value : values) {
            node.then(ClientCommands.literal(value).executes(context -> set(context, c -> setter.apply(c, value), label + value)));
        }
        return node;
    }

    private static int set(CommandContext<FabricClientCommandSource> context, Consumer<SkyLyricsConfig> change, String feedback) {
        change.accept(config);
        config.save();
        return reply(context, feedback);
    }

    private static int reply(CommandContext<FabricClientCommandSource> context, String text) {
        context.getSource().sendFeedback(prefix().append(text));
        return 1;
    }
}
