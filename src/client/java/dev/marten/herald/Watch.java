package dev.marten.herald;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 👀 Chat watchers (Herald 1.5.0), both defined by the engine in feed.json so they grow without a new mod:
 *  counters: [{id, pattern, template}]  → a live HUD line, e.g. "Enderman slayer 4 · 12,450 XP to next · 3 bosses".
 *            template: {n} = matches this game session, {1}..{9} = groups of the last match, {sum1} = running sum of group 1.
 *  alerts:   [{id, pattern, title, text, cmds, cooldownMin, min}] → toast + chat line with buttons when Hypixel says it
 *            (min = only when group 1 is at least this number). Placeholders {1}..{9} work in title/text.
 */
final class Watch {
	static final class Counter { String id = "", template = ""; Pattern p; int n = 0; double sum = 0; String[] last = new String[0]; }
	static final class Alert { String id = "", title = "", text = ""; Pattern p; long cooldownMs = 0; double min = 0; final List<String[]> cmds = new ArrayList<>(); }

	private final Herald h;
	private final List<Counter> counters = new ArrayList<>();
	private final List<Alert> alerts = new ArrayList<>();
	private final Map<String, Counter> keep = new HashMap<>();      // counts survive feed reloads
	private final Map<String, Long> lastAlert = new HashMap<>();

	Watch(Herald h) { this.h = h; }

	void update(JsonObject feed) {
		counters.clear(); alerts.clear();
		if (feed.has("counters") && feed.get("counters").isJsonArray()) for (JsonElement e : feed.getAsJsonArray("counters")) {
			JsonObject j = e.getAsJsonObject();
			Pattern p = compile(Guide.str(j, "pattern"));
			if (p == null) continue;
			String id = Guide.str(j, "id");
			Counter c = keep.computeIfAbsent(id, k -> new Counter());
			c.id = id; c.p = p; c.template = Guide.str(j, "template");
			counters.add(c);
		}
		if (feed.has("alerts") && feed.get("alerts").isJsonArray()) for (JsonElement e : feed.getAsJsonArray("alerts")) {
			JsonObject j = e.getAsJsonObject();
			if (!Guide.str(j, "pet").isEmpty()) continue;           // 1.10.0: pet rules belong to the Pet Guard (it only speaks up on the wrong pet)
			Pattern p = compile(Guide.str(j, "pattern"));
			if (p == null) continue;
			Alert a = new Alert();
			a.id = Guide.str(j, "id"); a.p = p; a.title = Guide.str(j, "title"); a.text = Guide.str(j, "text");
			a.cooldownMs = (long) ((j.has("cooldownMin") ? j.get("cooldownMin").getAsDouble() : 10) * 60_000);
			a.min = j.has("min") ? j.get("min").getAsDouble() : 0;
			if (j.has("cmds")) for (JsonElement ce : j.getAsJsonArray("cmds")) {
				JsonObject co = ce.getAsJsonObject();
				String cmd = Guide.str(co, "cmd");
				if (Herald.CMD_OK.matcher(cmd).matches()) a.cmds.add(new String[] { Guide.str(co, "label").isEmpty() ? cmd : Guide.str(co, "label"), cmd });
			}
			alerts.add(a);
		}
	}

	private static Pattern compile(String p) {
		if (p.isEmpty() || p.length() > 300) return null;
		try { return Pattern.compile(p); } catch (PatternSyntaxException x) { Herald.LOG.warn("watch pattern: {}", p); return null; }
	}

	void onChat(String raw) {
		String line = Guide.plain(raw);
		if (line.isEmpty()) return;
		for (Counter c : counters) {
			Matcher m = c.p.matcher(line);
			if (!m.find()) continue;
			c.n++;
			c.last = groups(m);
			if (m.groupCount() >= 1) c.sum += num(m.group(1));
		}
		long now = System.currentTimeMillis();
		for (Alert a : alerts) {
			Matcher m = a.p.matcher(line);
			if (!m.find()) continue;
			String[] g = groups(m);
			if (a.min > 0 && (g.length < 2 || num(g[1]) < a.min)) continue;
			if (now - lastAlert.getOrDefault(a.id, 0L) < a.cooldownMs) continue;
			lastAlert.put(a.id, now);
			String title = fill(a.title, g, 0, 0), text = fill(a.text, g, 0, 0);
			h.toast(title, text);
			h.ping("pling");
			MutableComponent cl = Herald.prefix().append(Component.literal(Herald.clean(title)).withStyle(ChatFormatting.GOLD));
			if (!text.isEmpty()) cl.append(Component.literal("  " + Herald.clean(text)).withStyle(ChatFormatting.WHITE));
			for (String[] c : a.cmds) cl.append(h.cmdButton(c[0], c[1]));
			Herald.chatLine(cl);
		}
	}

	/** HUD lines: counters that have seen their line at least once this game session. */
	List<String> hud() {
		List<String> out = new ArrayList<>();
		for (Counter c : counters) if (c.n > 0 && !c.template.isEmpty()) out.add(Herald.clean(fill(c.template, c.last, c.n, c.sum)));
		return out;
	}

	private static String[] groups(Matcher m) {
		String[] g = new String[m.groupCount() + 1];
		for (int i = 0; i <= m.groupCount(); i++) g[i] = m.group(i) == null ? "" : m.group(i);
		return g;
	}

	private static double num(String s) {
		try { return Double.parseDouble(s.replace(",", "")); } catch (Exception e) { return 0; }
	}

	private static String fill(String t, String[] g, int n, double sum) {
		String s = t.replace("{n}", Integer.toString(n)).replace("{sum1}", String.format(java.util.Locale.ROOT, "%,d", Math.round(sum)));
		for (int i = 1; i < 10; i++) s = s.replace("{" + i + "}", i < g.length ? g[i] : "");
		return s;
	}
}
