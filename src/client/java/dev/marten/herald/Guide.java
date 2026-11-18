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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 🧭 Session guide (Herald 1.5.0, 14.11.2026): the engine's plan for this session as steps, in order
 * (traps stop by stop, Experimentation Table, each minion upgrade, claims, gear, the next goals).
 * Everything comes from feed.json "guide", so new kinds of steps need no new mod:
 *   {id, steps: [{id, title, text, url, cmds: [{label, cmd}], done: {pattern, count}}]}
 * Do-key (H): runs the current step's next command (one command per press). Done-key (K): marks the step done.
 * A step with done.pattern finishes by itself when Hypixel's chat line shows up done.count times; a line counts
 * for the FIRST open step whose pattern matches (so 2 trap stops with the same line fill up in order).
 */
final class Guide {
	static final class Step {
		String id = "", title = "", text = "", url = "";
		String pet = "";                                   // 1.10.0: the pet this step needs ("pet" key or "Pet: Tiger" in its text)
		final List<String[]> cmds = new ArrayList<>();
		final List<String> info = new ArrayList<>();      // 1.9.0: the Todoist note as plain lines (shown in game)
		Pattern done; int need = 1;
	}

	private static final Pattern FORMAT = Pattern.compile("§.");

	private final Herald h;
	private final Path stateFile;
	String id = "";
	final List<Step> steps = new ArrayList<>();
	final Set<String> doneIds = new HashSet<>();
	final Map<String, Integer> counts = new HashMap<>(), presses = new HashMap<>();
	/** 1.9.0: when each step was done or skipped. A new session plan keeps those (20 h), so a skipped step doesn't come back next time. */
	final Map<String, Long> doneAt = new HashMap<>();
	static final long CARRY_MS = 20 * 3600_000L;
	boolean announced = false, finished = false;

	/**
	 * 1.10.0: recent chat, kept on disk (chat_recent.json, last few hours), so a step whose line came BEFORE the step
	 * existed (traps collected before the session plan arrived, or before a game restart) still counts.
	 * A line counts for one step only, ever ("used"), so nothing is counted twice.
	 */
	static final class Line { long at; String text; boolean used; Line(long at, String text, boolean used) { this.at = at; this.text = text; this.used = used; } }
	private final java.util.ArrayDeque<Line> recent = new java.util.ArrayDeque<>();
	private final Path recentFile;
	private boolean recentDirty = false;
	private long recentSaved = 0;
	private final Set<String> replayed = new HashSet<>();   // step ids already checked against recent chat in this plan

	Guide(Herald h, Path dir) {
		this.h = h; this.stateFile = dir.resolve("guide_state.json"); this.recentFile = dir.resolve("chat_recent.json");
		loadState(); loadRecent();
	}

	static String plain(String raw) { return FORMAT.matcher(raw == null ? "" : raw).replaceAll("").trim(); }

	// ------------------------------------------------------------ feed

	void update(JsonObject g) {
		if (g == null || !g.has("id")) { steps.clear(); return; }
		String nid = g.get("id").getAsString();
		List<Step> ns = new ArrayList<>();
		JsonArray arr = g.has("steps") ? g.getAsJsonArray("steps") : new JsonArray();
		for (JsonElement e : arr) {
			JsonObject j = e.getAsJsonObject();
			Step s = new Step();
			s.id = str(j, "id"); s.title = Herald.clean(str(j, "title")); s.text = Herald.clean(str(j, "text")); s.url = str(j, "url");
			if (s.id.isEmpty() || s.title.isEmpty()) continue;
			s.pet = PetGuard.stepPet(j);
			if (j.has("info") && j.get("info").isJsonArray()) for (JsonElement ie : j.getAsJsonArray("info")) { String l = Herald.clean(ie.getAsString()); if (!l.isEmpty() && s.info.size() < 10) s.info.add(l); }
			if (j.has("cmds")) for (JsonElement ce : j.getAsJsonArray("cmds")) {
				JsonObject co = ce.getAsJsonObject();
				String cmd = str(co, "cmd");
				if (Herald.CMD_OK.matcher(cmd).matches()) s.cmds.add(new String[] { str(co, "label").isEmpty() ? cmd : str(co, "label"), cmd });
			}
			if (j.has("done") && j.get("done").isJsonObject()) {
				JsonObject d = j.getAsJsonObject("done");
				String p = str(d, "pattern");
				if (!p.isEmpty() && p.length() < 300) {
					try { s.done = Pattern.compile(p); } catch (PatternSyntaxException x) { Herald.LOG.warn("guide pattern: {}", p); }
				}
				s.need = Math.max(1, d.has("count") ? d.get("count").getAsInt() : 1);
			}
			ns.add(s);
		}
		boolean same = nid.equals(id) && announced;
		List<String> gone = new ArrayList<>(), added = new ArrayList<>();
		if (same) {   // 🔄 background update from the engine (his Todoist ticks, game data, chat): say what changed
			Set<String> newIds = new HashSet<>(); for (Step s : ns) newIds.add(s.id);
			Set<String> oldIds = new HashSet<>(); for (Step s : steps) oldIds.add(s.id);
			for (Step s : steps) if (!newIds.contains(s.id) && !doneIds.contains(s.id)) gone.add(s.title);
			for (Step s : ns) if (!oldIds.contains(s.id)) added.add(s.title);
		}
		Step before = current();
		if (!nid.equals(id)) {
			id = nid; doneIds.clear(); counts.clear(); presses.clear(); announced = false; finished = false; replayed.clear();
			long now = System.currentTimeMillis();
			doneAt.entrySet().removeIf(e -> now - e.getValue() > CARRY_MS);
			for (Step s : ns) if (doneAt.containsKey(s.id)) doneIds.add(s.id);   // done/skipped in the last 20 h: stays done
		}
		steps.clear(); steps.addAll(ns);
		replay();
		if (!gone.isEmpty() || !added.isEmpty()) {
			MutableComponent line = Herald.prefix().append(Component.literal("Plan updated").withStyle(ChatFormatting.AQUA));
			if (!gone.isEmpty()) line.append(Component.literal("  done: " + String.join(", ", gone.subList(0, Math.min(3, gone.size()))) + (gone.size() > 3 ? " +" + (gone.size() - 3) : "")).withStyle(ChatFormatting.GREEN));
			if (!added.isEmpty()) line.append(Component.literal("  new: " + String.join(", ", added.subList(0, Math.min(3, added.size()))) + (added.size() > 3 ? " +" + (added.size() - 3) : "")).withStyle(ChatFormatting.YELLOW));
			Herald.chatLine(line);
			Step now = current();
			if (now != null && (before == null || !before.id.equals(now.id))) { h.toast("Next: " + now.title, h.doKey() + ": " + action(now)); h.ping("pling"); }
		}
		if (current() != null) finished = false;
		saveState();
	}

	Step current() {
		for (Step s : steps) if (!doneIds.contains(s.id)) return s;
		return null;
	}

	int position(Step s) { return steps.indexOf(s) + 1; }

	/** Once per session, when he's in a world: the plan in one chat block. */
	void announceIfNew() {
		if (steps.isEmpty() || announced || current() == null) return;
		announced = true; saveState();
		Herald.chatLine(Herald.prefix().append(Component.literal("Session plan").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
			.append(Component.literal("  " + steps.size() + " steps · " + h.doKey() + " = do, " + h.doneKey() + " = skip").withStyle(ChatFormatting.GRAY)));
		int i = 0;
		for (Step s : steps) {
			i++;
			if (i > 12) { Herald.chatLine(Component.literal("   +" + (steps.size() - 12) + " more").withStyle(ChatFormatting.DARK_GRAY)); break; }
			Herald.chatLine(Component.literal("   " + i + ". ").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal(s.title).withStyle(doneIds.contains(s.id) ? ChatFormatting.DARK_GRAY : ChatFormatting.WHITE)));
		}
		Step c = current();
		if (c != null) h.toast("Step 1: " + c.title, h.doKey() + ": " + action(c));
	}

	// ------------------------------------------------------------ keys

	void pressDo() {
		Step s = current();
		if (s == null) { Herald.chatLine(Herald.prefix().append(Component.literal(steps.isEmpty() ? "no session plan right now" : "plan done for now").withStyle(ChatFormatting.GRAY))); return; }
		int n = presses.getOrDefault(s.id, 0);
		if (s.cmds.isEmpty()) {
			details(s);
		} else {
			h.queueCmd(s.cmds.get(Math.min(n, s.cmds.size() - 1))[1]);
		}
		presses.put(s.id, n + 1);
		saveState();
	}

	/** 1.9.0: the current step in chat, in full: what to do, the Todoist note, buttons. No need to open Todoist. */
	void details() {
		Step s = current();
		if (s == null) { Herald.chatLine(Herald.prefix().append(Component.literal(steps.isEmpty() ? "no session plan right now" : "plan done for now").withStyle(ChatFormatting.GRAY))); return; }
		details(s);
	}

	void details(Step s) {
		Herald.chatLine(Herald.prefix().append(Component.literal("Step " + position(s) + "/" + steps.size() + "  ").withStyle(ChatFormatting.GRAY))
			.append(Component.literal(s.title).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
		if (!s.text.isEmpty()) Herald.chatLine(Component.literal("   " + s.text).withStyle(ChatFormatting.WHITE));
		for (String l : s.info) {
			boolean head = !l.startsWith("·");
			Herald.chatLine(Component.literal("   " + l).withStyle(head ? ChatFormatting.AQUA : ChatFormatting.GRAY));
		}
		MutableComponent btn = Component.literal("  ");
		for (String[] c : s.cmds) btn.append(h.cmdButton(c[0], c[1]));
		if (h.openable(s.url)) btn.append(h.openButton(s.url));
		btn.append(h.skipButton());
		Herald.chatLine(btn);
	}

	private long skipArmedAt = 0; private String skipArmedId = "";
	private final java.util.ArrayDeque<String> undoStack = new java.util.ArrayDeque<>();

	/** K needs a second press within 3 s, so a stray K never skips a step. */
	void pressDone() {
		Step s = current();
		if (s == null) return;
		long now = System.currentTimeMillis();
		if (!s.id.equals(skipArmedId) || now - skipArmedAt > 3000) {
			skipArmedId = s.id; skipArmedAt = now;
			Herald.chatLine(Herald.prefix().append(Component.literal("Press " + h.doneKey() + " again to skip: ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(s.title).withStyle(ChatFormatting.WHITE)));
			return;
		}
		skipArmedId = "";
		complete(s, true);
	}

	/** 1.9.0: the [Skip] button in chat (a click is already deliberate, so no second press). */
	void skipNow() { Step s = current(); if (s != null) complete(s, true); }

	/** /herald undo: puts the last finished step back. */
	void undo() {
		String last = undoStack.pollLast();
		if (last == null) { Herald.chatLine(Herald.prefix().append(Component.literal("nothing to undo").withStyle(ChatFormatting.GRAY))); return; }
		doneIds.remove(last); doneAt.remove(last); counts.remove(last); presses.remove(last); finished = false;
		Step s = current();
		Herald.chatLine(Herald.prefix().append(Component.literal("Back to: ").withStyle(ChatFormatting.GRAY)).append(Component.literal(s == null ? last : s.title).withStyle(ChatFormatting.GOLD)));
		saveState();
	}

	// ------------------------------------------------------------ chat

	private static final Pattern WARP_FAIL = Pattern.compile("^(Couldn't warp you!|The water prevents you from teleporting|You can't warp|You are not allowed to warp|You haven't unlocked this fast travel destination)");
	private static final Pattern NAV_MISS = Pattern.compile("SkyHanni Navigation Locations Matching|No locations? found", Pattern.CASE_INSENSITIVE);

	void onChat(String raw) {
		String line = plain(raw);
		if (line.isEmpty()) return;
		Line rec = remember(line);
		Step cur = current();
		if (cur != null && !cur.cmds.isEmpty() && cur.cmds.get(0)[1].startsWith("/warp ") && presses.getOrDefault(cur.id, 0) > 0
				&& (WARP_FAIL.matcher(line).find() || NAV_MISS.matcher(line).find())) {
			presses.put(cur.id, 0); saveState();     // the warp didn't land (cooldown, water, wrong island): H warps again
			h.toast("Not on the right island yet", h.doKey() + ": " + cur.cmds.get(0)[0]);
		}
		for (Step s : steps) {
			if (doneIds.contains(s.id) || s.done == null) continue;
			if (!s.done.matcher(line).find()) continue;
			int c = counts.getOrDefault(s.id, 0) + 1;
			counts.put(s.id, c);
			rec.used = true; recentDirty = true;
			if (c >= s.need) complete(s, false);
			else { h.ping("pling"); saveState(); }
			return;                        // one line = one step
		}
	}

	private void complete(Step s, boolean byKey) {
		doneIds.add(s.id); doneAt.put(s.id, System.currentTimeMillis()); undoStack.add(s.id); if (undoStack.size() > 20) undoStack.pollFirst();
		Step nx = current();
		if (nx == null) {
			if (!finished) {
				finished = true;
				h.toast("Plan done", byKey ? "Skipped: " + s.title : "Last: " + s.title);
				h.ping("levelup");
				Herald.chatLine(Herald.prefix().append(Component.literal("Session plan done").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
					.append(Component.literal("  " + steps.size() + " steps").withStyle(ChatFormatting.GRAY)));
			}
		} else {
			h.toast((byKey ? "Skipped: " : "Done: ") + s.title, "Next: " + nx.title + " · " + h.doKey() + ": " + action(nx));
			h.ping(byKey ? "pling" : "bell");
			MutableComponent line = Herald.prefix().append(Component.literal("Next: ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(nx.title).withStyle(ChatFormatting.GOLD));
			if (!nx.text.isEmpty()) line.append(Component.literal("  " + nx.text).withStyle(ChatFormatting.WHITE));
			for (String[] c : nx.cmds) line.append(h.cmdButton(c[0], c[1]));
			if (!nx.info.isEmpty()) line.append(h.detailsButton());
			if (h.openable(nx.url)) line.append(h.openButton(nx.url));
			Herald.chatLine(line);
		}
		saveState();
	}

	String action(Step s) {
		if (s.cmds.isEmpty()) return s.text.isEmpty() ? "show what to do" : s.text;
		int n = presses.getOrDefault(s.id, 0);
		return s.cmds.get(Math.min(n, s.cmds.size() - 1))[0];
	}

	/** HUD lines for the current step (empty = nothing to show). */
	List<String> hud() {
		List<String> out = new ArrayList<>();
		Step s = current();
		if (s == null) return out;
		String prog = s.done != null && s.need > 1 ? "  " + counts.getOrDefault(s.id, 0) + "/" + s.need : "";
		out.add(s.title + prog);
		out.add(s.text.isEmpty() ? "" : s.text);
		out.add(h.doKey() + ": " + (s.cmds.isEmpty() ? "details" : action(s)) + "   " + h.infoKey() + ": details   " + h.doneKey() + ": skip");
		int i = steps.indexOf(s);
		for (int k = i + 1; k < steps.size(); k++) {
			if (!doneIds.contains(steps.get(k).id)) { out.add("then: " + steps.get(k).title); break; }
		}
		return out;
	}

	/** "■■■□□□□□ 4/22" for the HUD header, or "" when there's no plan. */
	String progress() {
		Step s = current();
		if (steps.isEmpty()) return "";
		int done = 0; for (Step x : steps) if (doneIds.contains(x.id)) done++;
		int n = 10, f = (int) Math.round(n * (double) done / steps.size());
		return "■".repeat(f) + "□".repeat(n - f) + "  " + (s == null ? steps.size() + "/" + steps.size() + " done" : position(s) + "/" + steps.size());
	}

	// ------------------------------------------------------------ 1.10.0 chat replay

	private Line remember(String line) {
		Line l = new Line(System.currentTimeMillis(), line, false);
		recent.addLast(l);
		while (recent.size() > 800) recent.pollFirst();
		recentDirty = true;
		return l;
	}

	/** New steps with a done-pattern: count matching lines from the last replayMin minutes that no step has used yet. */
	private void replay() {
		List<Step> todo = new ArrayList<>();
		for (Step s : steps) {
			if (s.done == null || doneIds.contains(s.id) || replayed.contains(s.id)) continue;
			replayed.add(s.id); todo.add(s);
		}
		if (todo.isEmpty() || recent.isEmpty()) return;
		long from = System.currentTimeMillis() - Math.max(5, h.cfg().replayMin) * 60_000L;
		List<String> got = new ArrayList<>();
		for (Line l : recent) {
			if (l.used || l.at < from) continue;
			for (Step s : todo) {                                 // same rule as live: first open step whose pattern matches
				if (doneIds.contains(s.id) || !s.done.matcher(l.text).find()) continue;
				int c = counts.getOrDefault(s.id, 0) + 1;
				counts.put(s.id, c);
				l.used = true; recentDirty = true;
				if (c >= s.need) { doneIds.add(s.id); doneAt.put(s.id, System.currentTimeMillis()); got.add(s.title + (s.need > 1 ? " (" + c + "/" + s.need + ")" : "")); }
				break;
			}
		}
		boolean partial = false;
		for (Step s : todo) if (!doneIds.contains(s.id) && counts.getOrDefault(s.id, 0) > 0) partial = true;
		if (got.isEmpty() && !partial) return;
		MutableComponent line = Herald.prefix().append(Component.literal("Already done before the plan came in").withStyle(ChatFormatting.AQUA));
		if (!got.isEmpty()) line.append(Component.literal("  " + String.join(", ", got.subList(0, Math.min(4, got.size()))) + (got.size() > 4 ? " +" + (got.size() - 4) : "")).withStyle(ChatFormatting.GREEN));
		for (Step s : todo) if (!doneIds.contains(s.id) && counts.getOrDefault(s.id, 0) > 0)
			line.append(Component.literal("  " + s.title + " " + counts.get(s.id) + "/" + s.need).withStyle(ChatFormatting.YELLOW));
		Herald.chatLine(line);
		if (current() == null && !steps.isEmpty()) finished = true;
		saveRecent(true);
	}

	/** Called once a second by Herald: writes chat_recent.json at most every 30 s. */
	void tickSave() { saveRecent(false); }

	private void loadRecent() {
		try {
			if (!Files.exists(recentFile)) return;
			long from = System.currentTimeMillis() - 6 * 3600_000L;
			for (JsonElement e : JsonParser.parseString(Files.readString(recentFile, StandardCharsets.UTF_8)).getAsJsonArray()) {
				JsonObject o = e.getAsJsonObject();
				long at = o.get("at").getAsLong();
				if (at >= from) recent.addLast(new Line(at, str(o, "t"), o.has("u") && o.get("u").getAsBoolean()));
			}
		} catch (Exception e) { Herald.LOG.warn("chat_recent.json: {}", e.toString()); }
	}

	private void saveRecent(boolean force) {
		long now = System.currentTimeMillis();
		if (!recentDirty || (!force && now - recentSaved < 30_000)) return;
		recentSaved = now; recentDirty = false;
		try {
			long from = now - 6 * 3600_000L;
			JsonArray a = new JsonArray();
			for (Line l : recent) {
				if (l.at < from) continue;
				JsonObject o = new JsonObject(); o.addProperty("at", l.at); o.addProperty("t", l.text); if (l.used) o.addProperty("u", true);
				a.add(o);
			}
			Path tmp = recentFile.resolveSibling("chat_recent.json.tmp");
			Files.writeString(tmp, a.toString(), StandardCharsets.UTF_8);
			Files.move(tmp, recentFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) { Herald.LOG.warn("chat_recent.json write: {}", e.toString()); }
	}

	// ------------------------------------------------------------ state

	private void loadState() {
		try {
			if (!Files.exists(stateFile)) return;
			JsonObject o = JsonParser.parseString(Files.readString(stateFile, StandardCharsets.UTF_8)).getAsJsonObject();
			id = str(o, "id");
			announced = o.has("announced") && o.get("announced").getAsBoolean();
			finished = o.has("finished") && o.get("finished").getAsBoolean();
			if (o.has("done")) for (JsonElement e : o.getAsJsonArray("done")) doneIds.add(e.getAsString());
			if (o.has("counts")) for (String k : o.getAsJsonObject("counts").keySet()) counts.put(k, o.getAsJsonObject("counts").get(k).getAsInt());
			if (o.has("presses")) for (String k : o.getAsJsonObject("presses").keySet()) presses.put(k, o.getAsJsonObject("presses").get(k).getAsInt());
			if (o.has("doneAt")) for (String k : o.getAsJsonObject("doneAt").keySet()) doneAt.put(k, o.getAsJsonObject("doneAt").get(k).getAsLong());
			else { long now = System.currentTimeMillis(); for (String d : doneIds) doneAt.put(d, now); }   // from 1.8.x: today's done steps carry over
		} catch (Exception e) { Herald.LOG.warn("guide_state.json: {}", e.toString()); }
	}

	private void saveState() {
		try {
			JsonObject o = new JsonObject();
			o.addProperty("id", id); o.addProperty("announced", announced); o.addProperty("finished", finished);
			JsonArray d = new JsonArray(); for (String x : doneIds) d.add(x); o.add("done", d);
			JsonObject c = new JsonObject(); counts.forEach(c::addProperty); o.add("counts", c);
			JsonObject p = new JsonObject(); presses.forEach(p::addProperty); o.add("presses", p);
			JsonObject da = new JsonObject(); doneAt.forEach(da::addProperty); o.add("doneAt", da);
			Files.writeString(stateFile, o.toString(), StandardCharsets.UTF_8);
		} catch (Exception e) { Herald.LOG.warn("guide_state.json: {}", e.toString()); }
	}

	static String str(JsonObject j, String k) {
		return j.has(k) && !j.get(k).isJsonNull() ? j.get(k).getAsString() : "";
	}

	@SuppressWarnings("unused")
	private static boolean matches(Matcher m) { return m.find(); }
}
