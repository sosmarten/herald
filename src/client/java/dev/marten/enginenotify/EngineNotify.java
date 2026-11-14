package dev.marten.enginenotify;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.Command;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Engine Notify: shows SkyBlock Engine notifications in game.
 * The SkyBlock Engine bridge (PowerShell, every 5 min) writes config/engine-notify/feed.json:
 *   {"gen": ms, "items": [{"id", "at" (ms), "kind", "title", "text", "url", "sound"}]}
 * Each item is shown once, at its time (toast + chat line + sound), and its id goes to shown.json
 * (the bridge reads that so the desktop popup never repeats it). Client-only: nothing is sent anywhere.
 */
public class EngineNotify implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("enginenotify");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final long STALE_MS = 30 * 60_000L;      // older than this when first seen = skipped
	private static final long KEEP_SHOWN_MS = 3 * 86_400_000L;

	private final Path dir = FabricLoader.getInstance().getConfigDir().resolve("engine-notify");
	private final Path feedFile = dir.resolve("feed.json");
	private final Path shownFile = dir.resolve("shown.json");
	private final Path configFile = dir.resolve("config.json");

	private Config cfg = new Config();
	private final List<Item> feed = new ArrayList<>();
	private final Map<String, Long> shown = new HashMap<>();
	private final Deque<Item> waiting = new ArrayDeque<>();   // due while not in a world: shown on join
	private long feedMtime = -1, cfgMtime = -1, gen = 0;
	private int tick = 0;

	static class Config {
		boolean toast = true, chat = true, sound = true;
		float volume = 1.0f;
		Map<String, Boolean> kinds = new HashMap<>();        // e.g. "minions": false turns minion pings off
	}

	static class Item {
		String id, kind, title, text, url, sound;
		long at;
	}

	@Override
	public void onInitializeClient() {
		try { Files.createDirectories(dir); } catch (Exception e) { LOG.warn("engine-notify dir: {}", e.toString()); }
		loadConfig(true);
		loadShown();
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (++tick % 20 != 0) return;                     // once a second
			try { step(mc); } catch (Exception e) { LOG.warn("engine-notify: {}", e.toString()); }
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
			ClientCommands.literal("enginenotify")
				.then(ClientCommands.literal("test").executes(c -> { test(); return Command.SINGLE_SUCCESS; }))
				.then(ClientCommands.literal("list").executes(c -> { list(); return Command.SINGLE_SUCCESS; }))
				.then(ClientCommands.literal("reload").executes(c -> { feedMtime = -1; loadConfig(true); chatLine(prefix().append(Component.literal("reloaded").withStyle(ChatFormatting.GRAY))); return Command.SINGLE_SUCCESS; }))
				.executes(c -> { list(); return Command.SINGLE_SUCCESS; })));
		LOG.info("Engine Notify ready: {}", feedFile);
	}

	private void step(Minecraft mc) {
		loadConfig(false);
		loadFeed();
		long now = System.currentTimeMillis();
		boolean inWorld = mc.player != null && mc.level != null;
		boolean changed = false;
		for (Item it : feed) {
			if (it.at > now || shown.containsKey(it.id)) continue;
			shown.put(it.id, now); changed = true;
			if (now - it.at > STALE_MS) continue;                 // missed while the game was closed
			if (!kindOn(it.kind)) continue;
			if (inWorld) show(mc, it); else waiting.add(it);
		}
		if (inWorld) while (!waiting.isEmpty()) show(mc, waiting.poll());
		if (changed) saveShown(now);
	}

	private boolean kindOn(String kind) {
		Boolean b = cfg.kinds.get(kind == null ? "" : kind);
		return b == null || b;
	}

	// ---------------------------------------------------------------- showing

	private void show(Minecraft mc, Item it) {
		String title = clean(it.title), text = clean(it.text);
		if (cfg.toast) {
			SystemToast.add(mc.gui.toastManager(), new SystemToast.SystemToastId(6000L),
				Component.literal(title).withStyle(ChatFormatting.GOLD), Component.literal(text));
		}
		if (cfg.chat) {
			MutableComponent line = prefix()
				.append(Component.literal(title).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			if (!text.isEmpty()) line.append(Component.literal("  " + text).withStyle(ChatFormatting.WHITE));
			if (it.url != null && it.url.startsWith("https://")) {
				line.append(Component.literal("  [Open]").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
					.withClickEvent(new ClickEvent.OpenUrl(URI.create(it.url)))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Open in Todoist")))));
			}
			chatLine(line);
		}
		if (cfg.sound) {
			net.minecraft.sounds.SoundEvent ev = switch (it.sound == null ? "" : it.sound) {
				case "bell" -> SoundEvents.NOTE_BLOCK_BELL.value();
				case "levelup" -> SoundEvents.PLAYER_LEVELUP;
				default -> SoundEvents.NOTE_BLOCK_PLING.value();
			};
			mc.getSoundManager().play(SimpleSoundInstance.forUI(ev, 1.0f, cfg.volume));
		}
	}

	private static MutableComponent prefix() {
		return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
			.append(Component.literal("⚙ Engine").withStyle(ChatFormatting.AQUA))
			.append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY));
	}

	private static void chatLine(Component c) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui != null) mc.gui.getChat().addMessage(c);   // local only: never sent to the server
	}

	/** Minecraft's font has no colour emoji: drop characters outside the Basic Multilingual Plane + joiners. */
	static String clean(String s) {
		if (s == null) return "";
		StringBuilder b = new StringBuilder();
		s.codePoints().forEach(cp -> {
			if (cp > 0xFFFF || cp == 0xFE0F || cp == 0x200D) return;
			b.appendCodePoint(cp);
		});
		return b.toString().replaceAll("\\s{2,}", " ").trim();
	}

	private void test() {
		Item it = new Item();
		it.id = "test|" + System.currentTimeMillis(); it.kind = "test"; it.sound = "pling";
		it.title = "Engine Notify works"; it.text = "You'll see timers, crafts, contests and milestones like this";
		it.url = "https://app.todoist.com/app/project/6hw6qfjf5mVpPgGH";
		show(Minecraft.getInstance(), it);
	}

	private void list() {
		long now = System.currentTimeMillis();
		SimpleDateFormat f = new SimpleDateFormat("EEE HH:mm");
		int n = 0;
		chatLine(prefix().append(Component.literal("coming up" + (gen > 0 ? " (feed from " + f.format(new Date(gen)) + ")" : " (no feed yet)")).withStyle(ChatFormatting.GRAY)));
		for (Item it : feed) {
			if (it.at < now || n >= 8) continue;
			chatLine(Component.literal("  " + f.format(new Date(it.at)) + "  ").withStyle(ChatFormatting.DARK_AQUA)
				.append(Component.literal(clean(it.title)).withStyle(ChatFormatting.WHITE)));
			n++;
		}
		if (n == 0) chatLine(Component.literal("  nothing scheduled").withStyle(ChatFormatting.GRAY));
	}

	// ---------------------------------------------------------------- files

	private void loadFeed() {
		try {
			if (!Files.exists(feedFile)) return;
			long m = Files.getLastModifiedTime(feedFile).toMillis();
			if (m == feedMtime) return;
			feedMtime = m;
			String txt = Files.readString(feedFile, StandardCharsets.UTF_8);
			if (!txt.isEmpty() && txt.charAt(0) == '﻿') txt = txt.substring(1);
			JsonObject o = JsonParser.parseString(txt).getAsJsonObject();
			gen = o.has("gen") ? o.get("gen").getAsLong() : 0;
			List<Item> next = new ArrayList<>();
			JsonArray arr = o.has("items") ? o.getAsJsonArray("items") : new JsonArray();
			for (JsonElement e : arr) {
				JsonObject j = e.getAsJsonObject();
				Item it = new Item();
				it.id = str(j, "id"); it.kind = str(j, "kind"); it.title = str(j, "title"); it.text = str(j, "text");
				it.url = str(j, "url"); it.sound = str(j, "sound");
				it.at = j.has("at") ? j.get("at").getAsLong() : 0;
				if (it.id != null && !it.id.isEmpty()) next.add(it);
			}
			next.sort((a, b) -> Long.compare(a.at, b.at));
			feed.clear(); feed.addAll(next);
		} catch (Exception e) { LOG.warn("feed.json: {}", e.toString()); }
	}

	private static String str(JsonObject j, String k) {
		return j.has(k) && !j.get(k).isJsonNull() ? j.get(k).getAsString() : "";
	}

	private void loadConfig(boolean force) {
		try {
			if (!Files.exists(configFile)) { Files.writeString(configFile, GSON.toJson(new Config()), StandardCharsets.UTF_8); }
			long m = Files.getLastModifiedTime(configFile).toMillis();
			if (!force && m == cfgMtime) return;
			cfgMtime = m;
			Config c = GSON.fromJson(Files.readString(configFile, StandardCharsets.UTF_8), Config.class);
			if (c != null) { if (c.kinds == null) c.kinds = new HashMap<>(); cfg = c; }
		} catch (Exception e) { LOG.warn("config.json: {}", e.toString()); }
	}

	private void loadShown() {
		try {
			if (!Files.exists(shownFile)) return;
			JsonObject o = JsonParser.parseString(Files.readString(shownFile, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject ids = o.has("ids") ? o.getAsJsonObject("ids") : new JsonObject();
			for (String k : ids.keySet()) shown.put(k, ids.get(k).getAsLong());
		} catch (Exception e) { LOG.warn("shown.json: {}", e.toString()); }
	}

	private void saveShown(long now) {
		try {
			for (Iterator<Map.Entry<String, Long>> i = shown.entrySet().iterator(); i.hasNext(); ) {
				if (now - i.next().getValue() > KEEP_SHOWN_MS) i.remove();
			}
			JsonObject ids = new JsonObject();
			shown.forEach(ids::addProperty);
			JsonObject o = new JsonObject();
			o.add("ids", ids);
			o.addProperty("by", "mod");
			o.addProperty("at", now);
			Path tmp = dir.resolve("shown.json.tmp");
			Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
			Files.move(tmp, shownFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) { LOG.warn("shown.json write: {}", e.toString()); }
	}
}
