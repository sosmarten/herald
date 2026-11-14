package dev.marten.herald;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🪤 Trap route (Herald 1.4.0, 14.11.2026).
 * The engine puts the current huntrap targets in feed.json as "route" (steps grouped by island).
 * One press of the Herald key = one command: the step's /warp first, then its /shnavigate (press again to re-show the path).
 * Hypixel's own chat line "You placed a Trap near a Shard location! (n/N Hunting Traps placed)" moves the route on:
 * once a step has its traps, the next press warps to the next island (or navigates, if it's the same island).
 * Nothing is ever sent without his key press; the chat is only read.
 */
final class TrapRoute {
	static final class Step { String label = "", warp = "", nav = "", spot = ""; int traps = 1; }

	private static final Pattern PLACED = Pattern.compile("You placed a Trap near a Shard location! \\((\\d+)/(\\d+) Hunting Traps placed\\)");
	private static final Pattern PICKED = Pattern.compile("You picked up .*?Huntrap and it got sent to your Hunting Toolkit!(?: \\((\\d+)\\))?");
	private static final Pattern FORMAT = Pattern.compile("§.");

	private final Herald h;
	private final Path stateFile;

	String id = "";
	int total = 0;
	final List<Step> steps = new ArrayList<>();
	String thenTitle = "";
	final List<String[]> thenCmds = new ArrayList<>();

	int idx = 0, placedInStep = 0;
	boolean warped = false, done = false, announced = false;

	TrapRoute(Herald h, Path dir) { this.h = h; this.stateFile = dir.resolve("route_state.json"); loadState(); }

	boolean active() { return !id.isEmpty() && !done && idx < steps.size(); }

	// ------------------------------------------------------------ feed

	/** Called whenever feed.json changes. route = the feed's "route" object, or null when there is none. */
	void update(JsonObject route) {
		if (route == null || !route.has("id")) {
			if (!id.isEmpty() && !active()) { id = ""; steps.clear(); saveState(); }   // finished route: forget it
			return;
		}
		String nid = route.get("id").getAsString();
		List<Step> ns = new ArrayList<>();
		JsonArray arr = route.has("steps") ? route.getAsJsonArray("steps") : new JsonArray();
		for (JsonElement e : arr) {
			JsonObject j = e.getAsJsonObject();
			Step s = new Step();
			s.label = Herald.clean(str(j, "label"));
			s.warp = Herald.CMD_OK.matcher(str(j, "warp")).matches() ? str(j, "warp") : "";
			s.nav = Herald.CMD_OK.matcher(str(j, "nav")).matches() ? str(j, "nav") : "";
			s.spot = Herald.clean(str(j, "spot"));
			s.traps = Math.max(1, j.has("traps") ? j.get("traps").getAsInt() : 1);
			ns.add(s);
		}
		if (ns.isEmpty()) return;
		thenTitle = ""; thenCmds.clear();
		if (route.has("then") && route.get("then").isJsonObject()) {
			JsonObject t = route.getAsJsonObject("then");
			thenTitle = Herald.clean(str(t, "title"));
			if (t.has("cmds")) for (JsonElement ce : t.getAsJsonArray("cmds")) {
				JsonObject co = ce.getAsJsonObject();
				String cmd = str(co, "cmd");
				if (Herald.CMD_OK.matcher(cmd).matches()) thenCmds.add(new String[] { str(co, "label").isEmpty() ? cmd : str(co, "label"), cmd });
			}
		}
		steps.clear(); steps.addAll(ns);
		total = 0; for (Step s : steps) total += s.traps;
		if (!nid.equals(id)) {            // a new round of traps: start from the top
			id = nid; idx = 0; placedInStep = 0; warped = false; done = false; announced = false;
		}
		if (idx > steps.size()) idx = steps.size();
		saveState();
	}

	/** Once per route, when he's in a world: one chat line that the route is ready. */
	void announceIfNew() {
		if (!active() || announced) return;
		announced = true; saveState();
		MutableComponent line = Herald.prefix()
			.append(Component.literal("Trap route ready").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
			.append(Component.literal("  " + steps.size() + " stops · " + total + " traps · press " + h.keyName() + " to start").withStyle(ChatFormatting.WHITE));
		Herald.chatLine(line);
	}

	// ------------------------------------------------------------ his key

	void press() {
		if (id.isEmpty()) { Herald.chatLine(Herald.prefix().append(Component.literal("no trap route right now").withStyle(ChatFormatting.GRAY))); return; }
		if (!active()) { showThen(); return; }
		Step s = steps.get(idx);
		if (!warped && !s.warp.isEmpty()) {
			h.queueCmd(s.warp); warped = true;
		} else if (!s.nav.isEmpty()) {
			h.queueCmd(s.nav);
		} else {
			Herald.chatLine(Herald.prefix().append(Component.literal(s.spot.isEmpty() ? s.label : s.spot).withStyle(ChatFormatting.WHITE)));
		}
		saveState();
	}

	// ------------------------------------------------------------ chat

	void onChat(String raw) {
		String line = FORMAT.matcher(raw).replaceAll("").trim();
		Matcher m = PLACED.matcher(line);
		if (m.find()) {
			if (!active()) return;
			int n = Integer.parseInt(m.group(1)), max = Integer.parseInt(m.group(2));
			placedInStep++;
			if (n >= max) { finish(); return; }
			if (placedInStep >= steps.get(idx).traps) advance();
			else h.ping("pling");
			saveState();
			return;
		}
		m = PICKED.matcher(line);
		if (m.find() && active() && idx == 0 && placedInStep == 0 && !warped) {
			int k = m.group(1) == null ? 1 : Integer.parseInt(m.group(1));
			if (k == total) {
				Step s = steps.get(0);
				h.toast("All " + k + " traps collected", "Press " + h.keyName() + ": " + action(s, false));
				h.ping("bell");
			}
		}
	}

	private void advance() {
		Step prev = steps.get(idx);
		idx++; placedInStep = 0;
		if (idx >= steps.size()) { finish(); return; }
		Step nx = steps.get(idx);
		warped = !nx.warp.isEmpty() && nx.warp.equals(prev.warp);   // same island: next press navigates
		h.toast("Next: " + nx.label + " (" + nx.traps + ")", "Press " + h.keyName() + ": " + action(nx, warped));
		h.ping("bell");
		saveState();
	}

	private void finish() {
		done = true; idx = steps.size(); saveState();
		h.toast("All " + total + " traps placed", thenTitle.isEmpty() ? "Trap route done" : "Next: " + thenTitle);
		h.ping("levelup");
		showThen();
	}

	private void showThen() {
		MutableComponent line = Herald.prefix().append(Component.literal("Traps done").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
		if (!thenTitle.isEmpty()) line.append(Component.literal("  Next: " + thenTitle).withStyle(ChatFormatting.WHITE));
		for (String[] c : thenCmds) line.append(h.cmdButton(c[0], c[1]));
		Herald.chatLine(line);
	}

	private static String action(Step s, boolean warped) {
		if (!warped && !s.warp.isEmpty()) return s.warp.replace("/warp ", "warp to ");
		if (!s.nav.isEmpty()) return "navigate to " + s.nav.replace("/shnavigate ", "");
		return s.spot;
	}

	/** HUD line, or null when there's no route in progress. */
	String hudText() {
		if (!active()) return null;
		Step s = steps.get(idx);
		return "Trap " + (idx + 1) + "/" + steps.size() + ": " + s.label + " " + placedInStep + "/" + s.traps + "  ·  " + h.keyName() + ": " + action(s, warped);
	}

	// ------------------------------------------------------------ state (survives a game restart)

	private void loadState() {
		try {
			if (!Files.exists(stateFile)) return;
			JsonObject o = JsonParser.parseString(Files.readString(stateFile, StandardCharsets.UTF_8)).getAsJsonObject();
			id = str(o, "id");
			idx = o.has("idx") ? o.get("idx").getAsInt() : 0;
			placedInStep = o.has("placedInStep") ? o.get("placedInStep").getAsInt() : 0;
			warped = o.has("warped") && o.get("warped").getAsBoolean();
			done = o.has("done") && o.get("done").getAsBoolean();
			announced = o.has("announced") && o.get("announced").getAsBoolean();
		} catch (Exception e) { Herald.LOG.warn("route_state.json: {}", e.toString()); }
	}

	private void saveState() {
		try {
			JsonObject o = new JsonObject();
			o.addProperty("id", id); o.addProperty("idx", idx); o.addProperty("placedInStep", placedInStep);
			o.addProperty("warped", warped); o.addProperty("done", done); o.addProperty("announced", announced);
			Files.writeString(stateFile, o.toString(), StandardCharsets.UTF_8);
		} catch (Exception e) { Herald.LOG.warn("route_state.json: {}", e.toString()); }
	}

	private static String str(JsonObject j, String k) {
		return j.has(k) && !j.get(k).isJsonNull() ? j.get(k).getAsString() : "";
	}
}
