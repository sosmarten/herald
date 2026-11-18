package dev.marten.herald;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
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
 * Herald: shows SkyBlock Engine notifications in game.
 * The SkyBlock Engine bridge (PowerShell, every 5 min) writes config/herald/feed.json:
 *   {"gen": ms, "items": [{"id", "at" (ms), "kind", "title", "text", "url", "sound"}]}
 * Each item is shown once, at its time (toast + chat line + sound), and its id goes to shown.json
 * (the bridge reads that so the desktop popup never repeats it). Client-only: nothing is sent anywhere.
 */
public class Herald implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("herald");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final long STALE_MS = 30 * 60_000L;      // older than this when first seen = skipped
	private static final long KEEP_SHOWN_MS = 3 * 86_400_000L;

	private final Path dir = FabricLoader.getInstance().getConfigDir().resolve("herald");
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
		boolean toast = true, chat = true, sound = true, clipboard = true;
		String openIn = "app";
		boolean hud = true;                                  // session guide + counters + next timers, top left
		int hudX = 4, hudY = -1;                             // hudY -1 = left side, a bit above the middle (clear of the top-left mod HUDs)
		int hudMaxWidth = 200;                               // 1.9.0: the step wraps onto more lines instead of being cut
		float volume = 1.0f;
		Map<String, Boolean> kinds = new HashMap<>();        // e.g. "minions": false turns minion pings off
		boolean petGuard = true;                             // 1.10.0: Pet Guard (wrong-pet XP claims are stopped)
		long petGuardMinXp = 250_000;                        // claims this big ask once per menu even without a pet plan
		Map<String, String> extraDone = new HashMap<>();     // 1.10.0: step id prefix → extra regex that also finishes it
		int replayMin = 90;                                  // 1.10.0: chat lines this recent still finish a step that shows up later
	}

	static class Item {
		String id, kind, title, text, url, sound;
		long at;
		List<String[]> cmds = new ArrayList<>();          // {label, command}
	}

	/**
	 * 1.8.0 panel look, read from his SkyHanni Custom Scoreboard (gui.customScoreboard.background in
	 * config/skyhanni/config.json: color "chroma:alpha:r:g:b", borderSize, roundedCornerSmoothness), so Herald always
	 * matches that box. Falls back to his values from 15.11.2026 (black at alpha 80, 5 px padding, radius 10).
	 */
	static final class Look { int argb = 0x50000000, pad = 5, radius = 10; }
	private Look look; private long lookMtime = -2;

	private Look look() {
		Path f = FabricLoader.getInstance().getConfigDir().resolve("skyhanni").resolve("config.json");
		long m = -1;
		try { if (Files.exists(f)) m = Files.getLastModifiedTime(f).toMillis(); } catch (Exception ignored) { }
		if (look != null && m == lookMtime) return look;
		lookMtime = m;
		Look l = new Look();
		try {
			if (m > 0) {
				JsonObject bg = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject()
					.getAsJsonObject("gui").getAsJsonObject("customScoreboard").getAsJsonObject("background");
				if (bg.has("enabled") && bg.get("enabled").getAsBoolean()) {
					String[] c = bg.get("color").getAsString().split(":");
					if (c.length == 5) l.argb = (Integer.parseInt(c[1]) & 255) << 24 | (Integer.parseInt(c[2]) & 255) << 16 | (Integer.parseInt(c[3]) & 255) << 8 | (Integer.parseInt(c[4]) & 255);
					if (bg.has("borderSize")) l.pad = Math.max(2, Math.min(12, bg.get("borderSize").getAsInt()));
					if (bg.has("roundedCornerSmoothness")) l.radius = Math.max(0, Math.min(16, bg.get("roundedCornerSmoothness").getAsInt()));
				}
			}
		} catch (Exception e) { LOG.warn("skyhanni look: {}", e.toString()); }
		look = l;
		return l;
	}

	/** 1.9.0: word-wraps a HUD line to maxW pixels, up to maxLines lines (the last one ends in "…" only if it still doesn't fit). */
	private static List<String> wrap(Minecraft mc, String s, int maxW, int maxLines) {
		List<String> all = new ArrayList<>();
		String cur = "";
		for (String w : s.trim().split("\\s+")) {
			String t = cur.isEmpty() ? w : cur + " " + w;
			if (cur.isEmpty() || mc.font.width(t) <= maxW) cur = t;
			else { all.add(cur); cur = "  " + w; }
		}
		if (!cur.isEmpty()) all.add(cur);
		if (all.size() <= maxLines) { all.replaceAll(x -> fit(mc, x, maxW)); return all; }
		List<String> out = new ArrayList<>(all.subList(0, maxLines));
		String last = out.get(maxLines - 1) + "…";
		while (last.length() > 2 && mc.font.width(last) > maxW) last = last.substring(0, last.length() - 2).trim() + "…";
		out.set(maxLines - 1, last);
		return out;
	}

	/** Cuts a HUD line to maxW pixels with "…" so one long step never makes the whole panel wide. */
	private static String fit(Minecraft mc, String s, int maxW) {
		if (mc.font.width(s) <= maxW) return s;
		String t = s;
		while (t.length() > 1 && mc.font.width(t + "…") > maxW) t = t.substring(0, t.length() - 1);
		return t.trim() + "…";
	}

	/** Only these game commands can ever be run from a button (the engine feed is the only source). */
	static final java.util.regex.Pattern CMD_OK = java.util.regex.Pattern.compile("^/(warp|shnavigate|recipe|hub|is|pickupstash|pets)( [A-Za-z0-9 '_\\-]{1,60})?$");
	private final Map<String, String> tokens = new java.util.LinkedHashMap<>() {
		@Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 300; }
	};
	/** [Open] targets: only Todoist task/project pages, so a button can never open anything else. */
	private static final java.util.regex.Pattern TODOIST_URL = java.util.regex.Pattern.compile("^https://(app\\.)?todoist\\.com/app/(task|project)/(?:[A-Za-z0-9\\-]*-)?([A-Za-z0-9]{1,40})$");
	private final Map<String, String> links = new java.util.LinkedHashMap<>() {
		@Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 300; }
	};
	private final Deque<String> runQueue = new ArrayDeque<>();
	private Guide guide;
	private Watch watch;
	private PetGuard petGuard;
	private KeyMapping doKeyMap, doneKeyMap, infoKeyMap;
	private int tokenSeq = 0;

	@Override
	public void onInitializeClient() {
		try {
			Files.createDirectories(dir);
			Path old = FabricLoader.getInstance().getConfigDir().resolve("engine-notify");   // name before 14.11.2026
			for (String f : new String[] { "config.json", "shown.json" }) {
				if (Files.exists(old.resolve(f)) && !Files.exists(dir.resolve(f))) Files.copy(old.resolve(f), dir.resolve(f));
			}
		} catch (Exception e) { LOG.warn("herald dir: {}", e.toString()); }
		loadConfig(true);
		loadShown();
		guide = new Guide(this, dir);
		watch = new Watch(this);
		petGuard = new PetGuard(this);
		ScreenEvents.BEFORE_INIT.register((client, screen, w, hh) -> {
			if (!(screen instanceof AbstractContainerScreen<?> cs)) return;
			ScreenMouseEvents.allowMouseClick(screen).register((s, ev) -> petGuard.allowClick(cs));
			ScreenEvents.remove(screen).register(petGuard::onClose);
		});
		ScreenEvents.AFTER_INIT.register((client, screen, w, hh) -> {
			try { petGuard.onOpen(screen); } catch (Exception e) { LOG.warn("pet guard open: {}", e.toString()); }
		});
		KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("herald", "main"));
		doKeyMap = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.herald.do", InputConstants.Type.KEYSYM, InputConstants.KEY_H, cat));
		doneKeyMap = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.herald.done", InputConstants.Type.KEYSYM, InputConstants.KEY_K, cat));
		infoKeyMap = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.herald.info", InputConstants.Type.KEYSYM, InputConstants.KEY_J, cat));
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			String txt = message.getString();
			if (overlay) { try { petGuard.onOverlay(txt); } catch (Exception e) { LOG.warn("pet guard: {}", e.toString()); } return; }
			if (txt.startsWith("[Herald]")) return;
			try { guide.onChat(txt); } catch (Exception e) { LOG.warn("herald guide: {}", e.toString()); }
			try { watch.onChat(txt); } catch (Exception e) { LOG.warn("herald watch: {}", e.toString()); }
			try { petGuard.onChat(txt); } catch (Exception e) { LOG.warn("pet guard: {}", e.toString()); }
		});
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("herald", "hud"), (g, delta) -> {
			Minecraft mc = Minecraft.getInstance();
			if (!cfg.hud || mc.player == null) return;
			List<String> gl = guide.hud();
			List<String> wl = watch.hud();
			String nt = nextTimers();
			Component pl = petGuard.hudLine();
			if (gl.isEmpty() && wl.isEmpty() && nt == null && pl == null) return;
			// 1.8.0: same look as his SkyHanni scoreboard (see Style): see-through rounded box, centered gold title, compact rows
			Look st = look();
			int maxW = Math.max(120, cfg.hudMaxWidth);
			List<Component> rows = new ArrayList<>();
			List<Boolean> center = new ArrayList<>();
			List<Integer> grp = new ArrayList<>();          // 1.8.1: blocks like his SkyHanni scoreboard, a blank gap between them
			String prog = guide.progress();
			rows.add(Component.literal("HERALD").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
				.append(Component.literal(prog.isEmpty() ? "" : "  " + prog).withStyle(ChatFormatting.GRAY))); center.add(true); grp.add(0);
			for (int i = 0; i < gl.size(); i++) {   // 1.9.0: [title, what to do, keys, then] → title + what-to-do wrap over 2-3 lines
				if (gl.get(i).isEmpty()) continue;
				ChatFormatting c = i == 0 ? ChatFormatting.YELLOW : i == 1 ? ChatFormatting.WHITE : ChatFormatting.GRAY;
				List<String> parts = i <= 1 ? wrap(mc, gl.get(i), maxW, i == 0 ? 2 : 3) : List.of(fit(mc, gl.get(i), maxW));
				for (String part : parts) { rows.add(Component.literal(part).withStyle(c)); center.add(false); grp.add(1); }
			}
			if (pl != null) { rows.add(pl); center.add(false); grp.add(1); }   // 1.10.0: right under the step
			for (String t : wl) { rows.add(Component.literal(fit(mc, t, maxW)).withStyle(ChatFormatting.AQUA)); center.add(false); grp.add(2); }
			if (nt != null) { rows.add(Component.literal(fit(mc, nt, maxW)).withStyle(ChatFormatting.DARK_GRAY)); center.add(false); grp.add(3); }
			int w = 0; for (Component r : rows) w = Math.max(w, mc.font.width(r));
			int lh = mc.font.lineHeight, gap = 2, blockGap = lh / 2 + 3, hgt = rows.size() * lh;
			for (int k = 1; k < rows.size(); k++) hgt += grp.get(k).equals(grp.get(k - 1)) ? gap : blockGap;
			int pad = st.pad, x = cfg.hudX + pad, y = cfg.hudY >= 0 ? cfg.hudY + pad : (int) (g.guiHeight() * 0.36);
			int x0 = x - pad, y0 = y - pad, x1 = x + w + pad, y1 = y + hgt + pad;
			int r = Math.max(0, Math.min(st.radius, Math.min(x1 - x0, y1 - y0) / 2));
			g.fill(x0, y0 + r, x1, y1 - r, st.argb);                          // middle band
			for (int i = 0; i < r; i++) {                                      // rounded top + bottom rows (no overlap, so the alpha stays even)
				double dy = r - i - 0.5;
				int in = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
				g.fill(x0 + in, y0 + i, x1 - in, y0 + i + 1, st.argb);
				g.fill(x0 + in, y1 - i - 1, x1 - in, y1 - i, st.argb);
			}
			for (int k = 0; k < rows.size(); k++) {
				int rx = center.get(k) ? x + (w - mc.font.width(rows.get(k))) / 2 : x;
				g.text(mc.font, rows.get(k), rx, y, 0xFFFFFFFF, true);
				y += lh + (k + 1 < rows.size() && !grp.get(k + 1).equals(grp.get(k)) ? blockGap : gap);
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (!runQueue.isEmpty() && mc.player != null && mc.getConnection() != null) {
				String cmd = runQueue.poll();
				mc.getConnection().sendCommand(cmd.substring(1));   // same path as typing it in chat (his click)
			}
			while (doKeyMap.consumeClick()) { if (mc.player != null) guide.pressDo(); }
			while (doneKeyMap.consumeClick()) { if (mc.player != null) guide.pressDone(); }
			while (infoKeyMap.consumeClick()) { if (mc.player != null) guide.details(); }
			if (++tick % 20 != 0) return;                     // once a second
			try { step(mc); } catch (Exception e) { LOG.warn("herald: {}", e.toString()); }
			try { if (mc.player != null) petGuard.tick(mc); } catch (Exception e) { LOG.warn("pet guard tick: {}", e.toString()); }
			try { guide.tickSave(); } catch (Exception e) { LOG.warn("herald chat save: {}", e.toString()); }
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
			ClientCommands.literal("herald")
				.then(ClientCommands.literal("test").executes(c -> { test(); return Command.SINGLE_SUCCESS; }))
				.then(ClientCommands.literal("list").executes(c -> { list(); return Command.SINGLE_SUCCESS; }))
				.then(ClientCommands.literal("run").then(ClientCommands.argument("token", StringArgumentType.word())
					.executes(c -> { run(StringArgumentType.getString(c, "token")); return Command.SINGLE_SUCCESS; })))
				.then(ClientCommands.literal("open").then(ClientCommands.argument("token", StringArgumentType.word())
					.executes(c -> { open(StringArgumentType.getString(c, "token")); return Command.SINGLE_SUCCESS; })))
				.then(ClientCommands.literal("do").executes(c -> { guide.pressDo(); return Command.SINGLE_SUCCESS; }))
				.then(ClientCommands.literal("done").executes(c -> { guide.pressDone(); return Command.SINGLE_SUCCESS; }))
				.then(ClientCommands.literal("skip").executes(c -> { guide.skipNow(); return Command.SINGLE_SUCCESS; }))
				.then(ClientCommands.literal("info").executes(c -> { guide.details(); return Command.SINGLE_SUCCESS; }))
				.then(ClientCommands.literal("plan").executes(c -> { guide.announced = false; guide.announceIfNew(); return Command.SINGLE_SUCCESS; }))
				.then(ClientCommands.literal("pet").executes(c -> { petGuard.status(); return Command.SINGLE_SUCCESS; }))
				.then(ClientCommands.literal("guard")
					.then(ClientCommands.literal("on").executes(c -> { petGuard.pause(0); return Command.SINGLE_SUCCESS; }))
					.then(ClientCommands.literal("pause").then(ClientCommands.argument("min", IntegerArgumentType.integer(1, 120))
						.executes(c -> { petGuard.pause(IntegerArgumentType.getInteger(c, "min")); return Command.SINGLE_SUCCESS; })))
					.executes(c -> { petGuard.status(); return Command.SINGLE_SUCCESS; }))
				.then(ClientCommands.literal("undo").executes(c -> { guide.undo(); return Command.SINGLE_SUCCESS; }))
				.then(ClientCommands.literal("hud")
					.then(ClientCommands.literal("reset").executes(c -> { setHud(4, -1); return Command.SINGLE_SUCCESS; }))
					.then(ClientCommands.argument("x", IntegerArgumentType.integer(0, 4000)).then(ClientCommands.argument("y", IntegerArgumentType.integer(0, 4000))
						.executes(c -> { setHud(IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y")); return Command.SINGLE_SUCCESS; }))))
				.then(ClientCommands.literal("reload").executes(c -> { feedMtime = -1; look = null; loadConfig(true); chatLine(prefix().append(Component.literal("reloaded").withStyle(ChatFormatting.GRAY))); return Command.SINGLE_SUCCESS; }))
				.executes(c -> { list(); return Command.SINGLE_SUCCESS; })));
		LOG.info("Herald ready: {}", feedFile);
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
		if (inWorld) guide.announceIfNew();
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
			for (String[] c : it.cmds) line.append(cmdButton(c[0], c[1]));
			if (it.cmds.size() == 1 && cfg.clipboard) {
				mc.keyboardHandler.setClipboard(it.cmds.get(0)[1]);
				line.append(Component.literal("  (copied)").withStyle(ChatFormatting.DARK_GRAY));
			}
			if (openable(it.url)) line.append(openButton(it.url));
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

	static MutableComponent prefix() {
		return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
			.append(Component.literal("Herald").withStyle(ChatFormatting.AQUA))
			.append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY));
	}

	static void chatLine(Component c) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui != null) mc.gui.hud.getChat().addClientSystemMessage(c);   // local only: never sent to the server
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

	/** A green [label] button that runs cmd through /herald run (allowlisted, his click). */
	MutableComponent cmdButton(String label, String cmd) {
		String tok = Integer.toString(++tokenSeq, 36);
		tokens.put(tok, cmd);
		return Component.literal("  [" + clean(label) + "]").withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)
			.withClickEvent(new ClickEvent.RunCommand("/herald run " + tok))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to run " + cmd))));
	}

	/** One allowlisted game command, sent on the next tick (only ever after his click or key press). */
	void queueCmd(String cmd) {
		if (cmd == null || !CMD_OK.matcher(cmd).matches()) { LOG.warn("blocked command: {}", cmd); return; }
		runQueue.add(cmd);
	}

	void toast(String title, String text) {
		Minecraft mc = Minecraft.getInstance();
		if (!cfg.toast || mc.gui == null) return;
		SystemToast.add(mc.gui.toastManager(), new SystemToast.SystemToastId(6000L),
			Component.literal(clean(title)).withStyle(ChatFormatting.GOLD), Component.literal(clean(text)));
	}

	void ping(String sound) {
		Minecraft mc = Minecraft.getInstance();
		if (!cfg.sound) return;
		net.minecraft.sounds.SoundEvent ev = switch (sound == null ? "" : sound) {
			case "bell" -> SoundEvents.NOTE_BLOCK_BELL.value();
			case "levelup" -> SoundEvents.PLAYER_LEVELUP;
			default -> SoundEvents.NOTE_BLOCK_PLING.value();
		};
		mc.getSoundManager().play(SimpleSoundInstance.forUI(ev, 1.0f, cfg.volume));
	}

	/** Moves the HUD and saves it in config.json (Herald keeps all other settings as they are). */
	private void setHud(int x, int y) {
		cfg.hudX = x; cfg.hudY = y;
		try { Files.writeString(configFile, GSON.toJson(cfg), StandardCharsets.UTF_8); } catch (Exception e) { LOG.warn("config: {}", e.toString()); }
		chatLine(prefix().append(Component.literal(y < 0 ? "HUD back to the left side" : "HUD moved to " + x + ", " + y).withStyle(ChatFormatting.GRAY)));
	}

	Guide guide() { return guide; }
	Config cfg() { return cfg; }

	String doKey() { return doKeyMap == null ? "H" : doKeyMap.getTranslatedKeyMessage().getString(); }
	String doneKey() { return doneKeyMap == null ? "K" : doneKeyMap.getTranslatedKeyMessage().getString(); }
	String infoKey() { return infoKeyMap == null ? "J" : infoKeyMap.getTranslatedKeyMessage().getString(); }

	/** 1.9.0: [Details] = the whole step in chat (its Todoist note too). */
	MutableComponent detailsButton() {
		return Component.literal("  [Details]").withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)
			.withClickEvent(new ClickEvent.RunCommand("/herald info"))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Show the whole step here, no Todoist needed"))));
	}

	/** 1.9.0: [Skip] in the details block. */
	MutableComponent skipButton() {
		return Component.literal("  [Skip]").withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)
			.withClickEvent(new ClickEvent.RunCommand("/herald skip"))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Skip this step (it stays skipped for 20 h)"))));
	}

	boolean openable(String url) { return url != null && TODOIST_URL.matcher(url).matches(); }

	MutableComponent openButton(String url) {
		String tok = Integer.toString(++tokenSeq, 36);
		links.put(tok, url);
		return Component.literal("  [Open]").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
			.withClickEvent(new ClickEvent.RunCommand("/herald open " + tok))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("browser".equals(cfg.openIn) ? "Open in Todoist (browser)" : "Open in the Todoist app"))));
	}

	/** Bottom line: "next 13:55 Minions ready to craft · synced 09:54" (1 timed item in the next 24 h, never traps). */
	private String nextTimers() {
		long now = System.currentTimeMillis();
		SimpleDateFormat f = new SimpleDateFormat("HH:mm");
		String next = null;
		for (Item it : feed) {
			if (it.at <= now || it.at > now + 86_400_000L || "live".equals(it.kind) || it.id.startsWith("timer:traps")) continue;
			next = "next " + f.format(new Date(it.at)) + " " + clean(it.title);
			break;
		}
		// 1.10.1: say it when the plan is old while he plays (bridge or laptop sync stuck), instead of a quiet time
		long age = gen > 0 ? now - gen : 0;
		String synced = gen <= 0 ? null : age > 45 * 60_000L ? "! plan " + (age / 60_000L) + " min old" : "synced " + f.format(new Date(gen));
		if (next == null && synced == null) return null;
		return next == null ? synced : synced == null ? next : next + "  ·  " + synced;
	}

	private void run(String tok) {
		String cmd = tokens.get(tok);
		if (cmd == null) { chatLine(prefix().append(Component.literal("that button expired").withStyle(ChatFormatting.GRAY))); return; }
		queueCmd(cmd);
	}

	/** Opens a Todoist task/project: in the desktop app via todoist:// (default) or the web page. */
	private void open(String tok) {
		String url = links.get(tok);
		if (url == null) { chatLine(prefix().append(Component.literal("that link expired").withStyle(ChatFormatting.GRAY))); return; }
		java.util.regex.Matcher m = TODOIST_URL.matcher(url);
		if (!m.matches()) { LOG.warn("blocked link from feed: {}", url); return; }
		String target = "browser".equals(cfg.openIn) ? url : "todoist://" + m.group(2) + "?id=" + m.group(3);
		try {
			String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
			ProcessBuilder pb = os.contains("win") ? new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", target)
				: os.contains("mac") ? new ProcessBuilder("open", target) : new ProcessBuilder("xdg-open", target);
			pb.redirectErrorStream(true).start().getOutputStream().close();
		} catch (Exception e) {
			LOG.warn("herald open: {}", e.toString());
			chatLine(prefix().append(Component.literal("couldn't open Todoist").withStyle(ChatFormatting.RED)));
		}
	}

	private void test() {
		Item it = new Item();
		it.id = "test|" + System.currentTimeMillis(); it.kind = "test"; it.sound = "pling";
		it.title = "Herald works"; it.text = "You'll see timers, crafts, contests and milestones like this";
		it.url = "https://app.todoist.com/app/project/6hw6qfjf5mVpPgGH";
		it.cmds.add(new String[] { "Warp home", "/warp home" });
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
				if (j.has("cmds") && j.get("cmds").isJsonArray()) {
					for (JsonElement ce : j.getAsJsonArray("cmds")) {
						JsonObject co = ce.getAsJsonObject();
						String label = str(co, "label"), cmd = str(co, "cmd");
						if (CMD_OK.matcher(cmd).matches()) it.cmds.add(new String[] { label.isEmpty() ? cmd : label, cmd });
					}
				}
				if (it.id != null && !it.id.isEmpty()) next.add(it);
			}
			next.sort((a, b) -> Long.compare(a.at, b.at));
			feed.clear(); feed.addAll(next);
			guide.update(o.has("guide") && o.get("guide").isJsonObject() ? o.getAsJsonObject("guide") : null);
			watch.update(o);
			petGuard.update(o);
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
