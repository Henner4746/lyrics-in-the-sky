package de.henner.lyricsinthesky;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import de.henner.lyricsinthesky.dev.SelfTest;
import de.henner.lyricsinthesky.dev.Showcase;
import de.henner.lyricsinthesky.gui.SettingsScreen;
import de.henner.lyricsinthesky.lyrics.LrclibClient;
import de.henner.lyricsinthesky.nowplaying.NowPlayingSource;
import de.henner.lyricsinthesky.nowplaying.PreferredNowPlayingSource;
import de.henner.lyricsinthesky.render.NowPlayingHud;
import de.henner.lyricsinthesky.render.SkyLyricsRenderer;
import de.henner.lyricsinthesky.spotify.SpotifyAccount;
import de.henner.lyricsinthesky.spotify.SpotifyNowPlayingSource;
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
        controller = new LyricsController(source, lrclib, config);

        // Started on the first world join rather than at launch: nothing to show in menus, and it keeps the
        // helper process out of the busy resource-loading phase.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> source.start());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> source.stop());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            controller.tick();
            handleKeys(client);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> askForLrclib(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SkyLyricsRenderer.INSTANCE.reset());
        HudElementRegistry.attachElementBefore(VanillaHudElements.BOSS_BAR,
                Identifier.fromNamespaceAndPath(MOD_ID, "now_playing"), new NowPlayingHud());
        registerKeys();
        registerCommands();
        if (SelfTest.isEnabled()) {
            SelfTest.register(controller, lrclib);
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
        client.gui.setScreen(new SettingsScreen(client.gui.screen(), controller, spotify));
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

    private static void changeOffset(Minecraft client, int deltaMs) {
        config.offsetMs += deltaMs;
        config.save();
        client.gui.hud.getChat().addClientSystemMessage(prefix().append(offsetText()));
    }

    /** First world join after install: explain what is sent to lrclib.net and let the player decide. */
    private static void askForLrclib(Minecraft client) {
        if (config.lrclibAllowed != null || SelfTest.isEnabled()) {
            return;
        }
        MutableComponent message = prefix()
                .append("Für Songtexte fragt die Mod lrclib.net. Gesendet werden nur Titel, Künstler, Album und Länge "
                        + "des laufenden Songs. ")
                .append(button("[Erlauben]", "/skylyrics lrclib on", ChatFormatting.GREEN))
                .append(" ")
                .append(button("[Nein danke]", "/skylyrics lrclib off", ChatFormatting.GRAY));
        client.gui.hud.getChat().addClientSystemMessage(message);
    }

    private static MutableComponent button(String label, String command, ChatFormatting color) {
        return Component.literal(label).withStyle(style -> style.withColor(color).withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
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
                            + "Befehle: status, demo, on, off, mode <sky|around>, offset <ms>, opacity <0.1-1>, "
                            + "scale <0.3-3>, height <10-70>, palette <name>, font <script|minecraft>, "
                            + "follow <on|off>, title <on|off>, hud <on|off>, lrclib <on|off>, spotify <connect|disconnect>"))
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
                    .then(toggle("lrclib", (c, value) -> {
                        c.lrclibAllowed = value;
                        controller.retryLookups();
                    }, "Songtexte von lrclib.net"));
            dispatcher.register(root);
        });
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
