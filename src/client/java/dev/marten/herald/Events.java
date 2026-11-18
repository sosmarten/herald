package dev.marten.herald;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.11.0 (18.11.2026): what Herald saw, for the engine. Written to config/herald/events.json; the PC bridge (v12)
 * forwards new entries, so the engine knows which steps he ticked or skipped (and stops re-suggesting them for 20 h),
 * which pet is out, and which island he has stayed on for 3+ min (the session focus follows it).
 *
 * Kinds: step {id, title, how: tick|key|undo, ctx: chat lines before a key press}, pet {name, lvl, src},
 * area {name}, guard {title, lore} (a claim Pet Guard stopped, to check Hypixel's real wording).
 * Only game data, never player chat (the chat lines are the same Hypixel lines the bridge already sends).
 */
final class Events {
	private static final long KEEP_MS = 24 * 3600_000L;
	private static final Pattern AREA = Pattern.compile("^(?:Area|Dungeon): (.+)$");

	private final Path file;
	private final ArrayDeque<JsonObject> list = new ArrayDeque<>();
	private boolean dirty = false;
	private long savedAt = 0;
	private String areaNow = "", areaSent = ""; private long areaSince = 0;

	Events(Path dir) {
		file = dir.resolve("events.json");
		try {
			if (Files.exists(file)) for (JsonElement e : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray())
				if (e.isJsonObject()) list.addLast(e.getAsJsonObject());
		} catch (Exception e) { Herald.LOG.warn("events.json: {}", e.toString()); }
		for (JsonObject o : list) if ("area".equals(Guide.str(o, "kind"))) areaSent = Guide.str(o, "name");
	}

	private JsonObject add(String kind) {
		JsonObject o = new JsonObject();
		o.addProperty("t", System.currentTimeMillis());
		o.addProperty("kind", kind);
		list.addLast(o);
		while (list.size() > 300) list.pollFirst();
		dirty = true;
		return o;
	}

	void step(Guide.Step s, String how, List<String> ctx) {
		JsonObject o = add("step");
		o.addProperty("id", s.id); o.addProperty("title", Herald.clean(s.title)); o.addProperty("how", how);
		if (ctx != null && !ctx.isEmpty()) { JsonArray a = new JsonArray(); for (String l : ctx) a.add(l); o.add("ctx", a); }
		save(true);
	}

	void undo(String id) { JsonObject o = add("step"); o.addProperty("id", id); o.addProperty("how", "undo"); save(true); }

	void pet(String name, int lvl, String src) {
		JsonObject o = add("pet"); o.addProperty("name", name); o.addProperty("lvl", lvl); o.addProperty("src", src);
	}

	void guard(String title, List<String> lore) {
		JsonObject o = add("guard"); o.addProperty("title", title);
		JsonArray a = new JsonArray(); for (String l : lore.subList(0, Math.min(8, lore.size()))) a.add(l); o.add("lore", a);
		save(true);
	}

	/** Once a second: the tab list "Area:" line; an island counts once he has stayed 3 min. */
	void tick(Minecraft mc) {
		try {
			if (mc.getConnection() != null) {
				String a = "";
				for (PlayerInfo pi : mc.getConnection().getListedOnlinePlayers()) {
					Component dn = pi.getTabListDisplayName();
					if (dn == null) continue;
					Matcher m = AREA.matcher(Guide.plain(dn.getString()));
					if (m.find()) { a = m.group(1).trim(); break; }
				}
				long now = System.currentTimeMillis();
				if (!a.equals(areaNow)) { areaNow = a; areaSince = now; }
				else if (!a.isEmpty() && !a.equals(areaSent) && now - areaSince > 3 * 60_000L) {
					areaSent = a;
					JsonObject o = add("area"); o.addProperty("name", a);
					save(true);
				}
			}
		} catch (Exception e) { Herald.LOG.debug("area: {}", e.toString()); }
		save(false);
	}

	String area() { return areaNow; }

	private void save(boolean force) {
		long now = System.currentTimeMillis();
		if (!dirty || (!force && now - savedAt < 10_000)) return;
		dirty = false; savedAt = now;
		try {
			JsonArray a = new JsonArray();
			for (JsonObject o : list) if (now - o.get("t").getAsLong() < KEEP_MS) a.add(o);
			Path tmp = file.resolveSibling("events.json.tmp");
			Files.writeString(tmp, a.toString(), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) { Herald.LOG.warn("events.json write: {}", e.toString()); }
	}
}
