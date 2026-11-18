package dev.marten.herald;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 🐾 Pet Guard (Herald 1.10.0, 18.11.2026): never again claim XP on the wrong pet.
 * (17.11 he claimed Bestiary 30–135 with the Lion out instead of the Tiger; ~11.5M Combat XP went to the Lion.)
 *
 * Knows the active pet from Hypixel's own lines ("You summoned your …", "Autopet equipped your …"), the tab list
 * "Pet:" widget and the /pets menu ("Click to despawn"). The pet that SHOULD be out comes from the engine:
 *   - feed alerts with a "pet" key: {id, pet, gui (menu title regex), pattern (chat regex), title, text}
 *   - a guide step with "pet" or "Pet: Name" in its text (that step's pet counts while it's the current step)
 * Then:
 *   1. click guard: a click on an XP reward ("Click to claim" + "+N Combat Experience", or any claim in a guarded menu)
 *      with the wrong / unknown pet is cancelled on his side (nothing reaches Hypixel), with a red toast + sound.
 *      Clicking the same item again within 4 s claims anyway. No plan + a big claim (≥ petGuardMinXp) = one confirm per menu.
 *   2. menu check: opening a guarded menu with the wrong pet warns right away.
 *   3. XP watch (backup): "+N Combat …" in chat or the action bar on the wrong pet = loud STOP alert.
 *   4. HUD line: "Pet: Lion ✗ need Tiger" (red) / "Pet: Tiger ✓" (green) while a pet plan is active.
 * Client-only: it only reads what the game shows and cancels his own click; it never clicks, moves or sends anything.
 */
final class PetGuard {
	static final class Rule { String id = "", pet = "", title = "", text = ""; Pattern gui, chat; long seenAt = 0; }

	private static final Pattern SUMMON = Pattern.compile("^You summoned your (?:\\[Lvl (\\d+)\\] )?(.+?)!?$");
	private static final Pattern AUTOPET = Pattern.compile("^Autopet equipped your \\[Lvl (\\d+)\\] (.+?)!");
	private static final Pattern DESPAWN = Pattern.compile("^You despawned your (.+?)!?$");
	private static final Pattern TAB_PET = Pattern.compile("^\\[Lvl (\\d+)\\] ([A-Za-z][A-Za-z' \\-]*[A-Za-z])");
	private static final Pattern MARKER = Pattern.compile("(?i)\\bpet:\\s*([A-Za-z][A-Za-z' \\-]{1,30}?)\\s*(?=[·|,;()!.]|$)");
	private static final Pattern XP = Pattern.compile("\\+([\\d,]+(?:\\.\\d+)?)\\s*([kKmM])?\\s+(Combat|Farming|Mining|Foraging|Fishing|Enchanting|Alchemy|Hunting)(?:\\s+(?:Experience|XP|Exp)\\b|\\s*\\()");
	private static final Pattern CLAIM = Pattern.compile("(?i)click to claim|claim (?:all|reward|rewards)|to claim|claimable");
	private static final long CONFIRM_MS = 4000;

	private final Herald h;
	String pet = "";            // active pet name ("" = unknown, "none" = no pet out)
	int petLvl = 0;
	String petSrc = "";
	long petAt = 0;
	final List<Rule> rules = new ArrayList<>();
	long pausedUntil = 0;

	private Screen okScreen;                 // menu where he already confirmed a claim on purpose
	private Screen armScreen; private int armSlot = -1; private long armAt = 0;
	private long lastStop = 0, lastSoft = 0, lastOpenWarn = 0;
	private String lastMismatch = "";
	private Field hovered; private boolean hoveredTried = false;
	private Screen openScreen;                // the menu that's open now (from Fabric screen events)

	PetGuard(Herald h) { this.h = h; }

	// ------------------------------------------------------------ plan from the feed

	void update(JsonObject feed) {
		java.util.Map<String, Long> seen = new java.util.HashMap<>();
		for (Rule r : rules) seen.put(r.id, r.seenAt);
		rules.clear();
		if (feed == null || !feed.has("alerts") || !feed.get("alerts").isJsonArray()) return;
		for (JsonElement e : feed.getAsJsonArray("alerts")) {
			if (!e.isJsonObject()) continue;
			JsonObject j = e.getAsJsonObject();
			String p = Guide.str(j, "pet");
			if (p.isEmpty()) continue;
			Rule r = new Rule();
			r.id = Guide.str(j, "id"); r.pet = Herald.clean(p).trim();
			r.title = Guide.str(j, "title"); r.text = Guide.str(j, "text");
			r.gui = compile(Guide.str(j, "gui")); r.chat = compile(Guide.str(j, "pattern"));
			r.seenAt = seen.getOrDefault(r.id, 0L);
			rules.add(r);
		}
	}

	/** "Pet: Tiger" in a guide step's raw text (or its "pet" key). */
	static String stepPet(JsonObject j) {
		String p = Guide.str(j, "pet");
		if (!p.isEmpty()) return Herald.clean(p).trim();
		for (String k : new String[] { "text", "title" }) {
			Matcher m = MARKER.matcher(Guide.str(j, k));
			if (m.find()) return m.group(1).trim();
		}
		if (j.has("info") && j.get("info").isJsonArray()) for (JsonElement ie : j.getAsJsonArray("info")) {
			if (!ie.isJsonPrimitive()) continue;
			Matcher m = MARKER.matcher(ie.getAsString());
			if (m.find()) return m.group(1).trim();
		}
		return "";
	}

	private static Pattern compile(String p) {
		if (p == null || p.isEmpty() || p.length() > 300) return null;
		try { return Pattern.compile(p); } catch (PatternSyntaxException x) { Herald.LOG.warn("pet guard pattern: {}", p); return null; }
	}

	/** The pet the plan wants for this menu title (null = no plan). */
	private String wanted(String menuTitle) {
		if (menuTitle != null) for (Rule r : rules) if (r.gui != null && r.gui.matcher(menuTitle).find()) return r.pet;
		Guide.Step s = h.guide().current();
		if (s != null && !s.pet.isEmpty()) return s.pet;
		return null;
	}

	/** The pet the plan wants for a chat/action-bar XP line (rule chat pattern, else the current step). */
	private String wantedForLine(String line) {
		for (Rule r : rules) if (r.chat != null && r.chat.matcher(line).find()) return r.pet;
		Guide.Step s = h.guide().current();
		return s != null && !s.pet.isEmpty() ? s.pet : null;
	}

	/**
	 * The pet plan that matters right now (HUD line + "wrong pet" toasts): the current step's pet, else a rule whose
	 * menu is open or whose chat line showed up in the last 5 min (e.g. "You have 21 pending Bestiary Milestones").
	 * So mining with the Mithril Golem never nags about the Tiger.
	 */
	String activePlan() {
		Guide.Step s = h.guide().current();
		if (s != null && !s.pet.isEmpty()) return s.pet;
		String title = openScreen == null ? null : Guide.plain(openScreen.getTitle().getString());
		long now = System.currentTimeMillis();
		for (Rule r : rules) {
			if (title != null && r.gui != null && r.gui.matcher(title).find()) return r.pet;
			if (now - r.seenAt < 5 * 60_000L) return r.pet;
		}
		return null;
	}

	static boolean same(String want, String have) {
		String a = norm(want), b = norm(have);
		return !a.isEmpty() && !b.isEmpty() && (b.equals(a) || b.contains(a));
	}

	private static String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""); }

	boolean paused() { return System.currentTimeMillis() < pausedUntil || !h.cfg().petGuard; }

	// ------------------------------------------------------------ knowing the active pet

	void onChat(String raw) {
		String line = Guide.plain(raw);
		if (line.isEmpty()) return;
		Matcher m = AUTOPET.matcher(line);
		if (m.find()) { setPet(m.group(2), parse(m.group(1)), "autopet"); }
		else if ((m = SUMMON.matcher(line)).find()) { setPet(m.group(2), parse(m.group(1)), "summon"); }
		else if ((m = DESPAWN.matcher(line)).find()) { setPet("none", 0, "despawn"); }
		boolean fresh = false;
		for (Rule r : rules) if (r.chat != null && r.chat.matcher(line).find()) { fresh |= System.currentTimeMillis() - r.seenAt > 5 * 60_000L; r.seenAt = System.currentTimeMillis(); }
		if (fresh && !paused()) {           // e.g. the pending-Bestiary line on join: say right away whether the right pet is out
			String want = activePlan();
			if (want != null && !same(want, pet)) warnSoft(pet.isEmpty() ? "Which pet is out? (open /pets once)" : "Wrong pet for this: " + pet, "plan needs " + want);
		}
		xpLine(line);
	}

	/** Action bar ("+1,000,000 Combat (45%)"): only the XP watch. */
	void onOverlay(String raw) { String line = Guide.plain(raw); if (!line.isEmpty()) xpLine(line); }

	private void setPet(String name, int lvl, String src) {
		String n = cleanName(name);
		if (n.isEmpty()) return;
		boolean changed = !n.equals(pet);
		pet = n; petLvl = lvl > 0 ? lvl : (changed ? 0 : petLvl); petSrc = src; petAt = System.currentTimeMillis();
		if (changed) {
			lastMismatch = "";
			String want = activePlan();
			if (want != null && !"none".equals(n) && !paused()) {
				if (same(want, n)) { h.toast("Pet OK: " + n, "matches the plan (" + want + ")"); }
				else warnSoft("Wrong pet out: " + n, "plan needs " + want);
			}
		}
	}

	private static String cleanName(String s) {
		String t = Herald.clean(s).replaceAll("\\[Lvl \\d+\\]", "").replace("✦", "").trim();
		Matcher m = Pattern.compile("([A-Za-z][A-Za-z' \\-]*[A-Za-z])").matcher(t);
		return m.find() ? m.group(1).trim() : "";
	}

	private static int parse(String s) { try { return s == null ? 0 : Integer.parseInt(s); } catch (Exception e) { return 0; } }

	/** Once a second: the tab list "Pet:" widget and an open /pets menu. */
	void tick(Minecraft mc) {
		try {
			if (mc.getConnection() != null) {
				boolean next = false;
				for (PlayerInfo pi : mc.getConnection().getListedOnlinePlayers()) {
					Component dn = pi.getTabListDisplayName();
					if (dn == null) continue;
					String s = Guide.plain(dn.getString());
					if (s.equals("Pet:")) { next = true; continue; }
					Matcher m = TAB_PET.matcher(s);
					if (m.find() && (next || s.length() < 40)) { setPet(m.group(2), parse(m.group(1)), "tab"); break; }
				}
			}
		} catch (Exception e) { Herald.LOG.debug("pet tab: {}", e.toString()); }
		try {
			if (openScreen instanceof AbstractContainerScreen<?> cs && cs.getTitle().getString().startsWith("Pets")) {
				for (Slot sl : cs.getMenu().slots) {
					ItemStack st = sl.getItem();
					if (st.isEmpty()) continue;
					for (String l : lore(st)) if (l.contains("Click to despawn")) {
						Matcher m = TAB_PET.matcher(Guide.plain(st.getHoverName().getString()));
						if (m.find()) setPet(m.group(2), parse(m.group(1)), "pets menu");
						return;
					}
				}
			}
		} catch (Exception e) { Herald.LOG.debug("pet menu: {}", e.toString()); }
	}

	// ------------------------------------------------------------ guarding

	/** A guarded menu just opened: warn before he clicks anything. */
	void onOpen(Screen screen) {
		openScreen = screen;
		if (paused() || !(screen instanceof AbstractContainerScreen<?>)) return;
		String title = Guide.plain(screen.getTitle().getString());
		String want = null;
		for (Rule r : rules) if (r.gui != null && r.gui.matcher(title).find()) { want = r.pet; break; }
		if (want == null || same(want, pet)) return;
		long now = System.currentTimeMillis();
		if (now - lastOpenWarn < 3000) return;
		lastOpenWarn = now;
		red("STOP: " + (pet.isEmpty() ? "pet unknown" : pet + " is out"), "summon " + want + " before you claim (/pets)");
		alarm();
	}

	/** Fabric allowMouseClick: false = his click is cancelled (only ever for an XP claim with the wrong pet). */
	boolean allowClick(AbstractContainerScreen<?> cs) {
		try {
			if (paused()) return true;
			Slot sl = hoveredSlot(cs);
			if (sl == null || !sl.hasItem()) return true;
			ItemStack st = sl.getItem();
			List<String> lore = lore(st);
			String all = String.join("\n", lore);
			String title = Guide.plain(cs.getTitle().getString());
			boolean claim = CLAIM.matcher(all).find();
			long xp = maxXp(all);
			String skill = skillOf(all);
			String want = wanted(title);
			boolean guardedMenu = false;
			for (Rule r : rules) if (r.gui != null && r.gui.matcher(title).find()) { guardedMenu = true; break; }
			if (!claim || (xp <= 0 && !guardedMenu)) return true;           // not an XP reward
			if (cs == okScreen) return true;                                 // he already confirmed in this menu
			long now = System.currentTimeMillis();
			boolean ok = want != null ? same(want, pet) : xp < h.cfg().petGuardMinXp;
			if (ok) return true;
			if (cs == armScreen && sl.index == armSlot && now - armAt < CONFIRM_MS) {   // second click = on purpose
				okScreen = cs; armScreen = null;
				Herald.chatLine(Herald.prefix().append(Component.literal("Claimed on " + (pet.isEmpty() ? "an unknown pet" : pet) + " (you confirmed)").withStyle(ChatFormatting.GRAY)));
				return true;
			}
			armScreen = cs; armSlot = sl.index; armAt = now;
			String xs = xp > 0 ? String.format(Locale.ROOT, "%,d", xp) + " " + skill + " XP" : "this reward";
			if (want != null) {
				red("BLOCKED: " + (pet.isEmpty() ? "pet unknown" : pet + " is out") + " · need " + want,
					xs + " would go to " + (pet.isEmpty() ? "?" : pet) + " · click again to claim anyway");
				alarm();
				chatBlock(want, xs);
			} else {
				h.toast("Check pet: " + (pet.isEmpty() ? "unknown" : pet), xs + " goes to this pet · click again to claim");
				h.ping("pling");
			}
			return false;
		} catch (Exception e) {
			Herald.LOG.warn("pet guard click: {}", e.toString());
			return true;                        // never lock him out of a menu because of a Herald bug
		}
	}

	void onClose(Screen s) { if (s == openScreen) openScreen = null; if (s == okScreen) okScreen = null; if (s == armScreen) armScreen = null; }

	/** Backup: XP already landed on the wrong pet → loud STOP (works even if the click guard missed the item). */
	private void xpLine(String line) {
		if (paused()) return;
		Matcher m = XP.matcher(line);
		if (!m.find()) return;
		long xp = amount(m.group(1), m.group(2));
		String want = wantedForLine(line);
		if (want == null || same(want, pet)) return;
		long now = System.currentTimeMillis();
		if (xp >= 50_000) {
			if (now - lastStop < 15_000) return;
			lastStop = now;
			red("STOP CLAIMING: +" + String.format(Locale.ROOT, "%,d", xp) + " " + m.group(3) + " XP → " + (pet.isEmpty() ? "unknown pet" : pet),
				"summon " + want + " first (/pets)");
			alarm();
			chatBlock(want, String.format(Locale.ROOT, "%,d", xp) + " " + m.group(3) + " XP");
		} else if (now - lastSoft > 10 * 60_000L) {
			lastSoft = now;
			warnSoft(m.group(3) + " XP going to " + (pet.isEmpty() ? "an unknown pet" : pet), "plan needs " + want);
		}
	}

	private void warnSoft(String title, String text) {
		h.toast(title, text);
		h.ping("pling");
		MutableComponent c = Herald.prefix().append(Component.literal(title).withStyle(ChatFormatting.RED)).append(Component.literal("  " + text).withStyle(ChatFormatting.WHITE));
		c.append(h.cmdButton("Pets", "/pets"));
		Herald.chatLine(c);
	}

	private void chatBlock(String want, String xs) {
		MutableComponent c = Herald.prefix().append(Component.literal("Pet Guard").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
			.append(Component.literal("  " + xs + " · " + (pet.isEmpty() ? "pet unknown" : pet + " is out") + " · plan: " + want).withStyle(ChatFormatting.WHITE));
		c.append(h.cmdButton("Pets", "/pets"));
		Herald.chatLine(c);
	}

	private void red(String title, String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui == null) return;
		SystemToast.add(mc.gui.toastManager(), new SystemToast.SystemToastId(8000L),
			Component.literal(Herald.clean(title)).withStyle(ChatFormatting.RED, ChatFormatting.BOLD), Component.literal(Herald.clean(text)));
	}

	private void alarm() {
		Minecraft mc = Minecraft.getInstance();
		if (!h.cfg().sound) return;
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 0.5f, Math.max(0.6f, h.cfg().volume)));
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 0.5f, Math.max(0.6f, h.cfg().volume)));
	}

	// ------------------------------------------------------------ HUD + /herald pet

	/** One HUD line while a pet plan is active: green when right, red when wrong/unknown. Null = nothing to show. */
	Component hudLine() {
		String want = activePlan();
		if (want == null) return null;
		if (paused()) return Component.literal("Pet Guard paused").withStyle(ChatFormatting.DARK_GRAY);
		if (same(want, pet)) return Component.literal("Pet: " + pet + (petLvl > 0 ? " " + petLvl : "") + " ✔").withStyle(ChatFormatting.GREEN);
		String have = pet.isEmpty() ? "unknown (open /pets)" : pet + (petLvl > 0 ? " " + petLvl : "");
		return Component.literal("Pet: " + have + " ✘ need " + want).withStyle(ChatFormatting.RED);
	}

	void status() {
		String want = activePlan();
		Herald.chatLine(Herald.prefix().append(Component.literal("Pet Guard " + (paused() ? "paused" : "on")).withStyle(paused() ? ChatFormatting.GRAY : ChatFormatting.GREEN, ChatFormatting.BOLD)));
		Herald.chatLine(Component.literal("   active pet: " + (pet.isEmpty() ? "unknown" : pet + (petLvl > 0 ? " (lvl " + petLvl + ")" : "")) + (petSrc.isEmpty() ? "" : " · from " + petSrc)).withStyle(ChatFormatting.WHITE));
		Herald.chatLine(Component.literal("   plan: " + (want == null ? "none right now" : want)).withStyle(ChatFormatting.WHITE));
		for (Rule r : rules) Herald.chatLine(Component.literal("   · " + r.id + ": " + r.pet + (r.gui != null ? " in menus /" + r.gui.pattern() + "/" : "")).withStyle(ChatFormatting.GRAY));
		Herald.chatLine(Component.literal("   big claims without a plan ask once per menu (" + String.format(Locale.ROOT, "%,d", h.cfg().petGuardMinXp) + "+ XP) · /herald guard pause 10").withStyle(ChatFormatting.DARK_GRAY));
	}

	void pause(int min) {
		pausedUntil = min <= 0 ? 0 : System.currentTimeMillis() + min * 60_000L;
		Herald.chatLine(Herald.prefix().append(Component.literal(min <= 0 ? "Pet Guard on" : "Pet Guard paused for " + min + " min").withStyle(ChatFormatting.GRAY)));
	}

	// ------------------------------------------------------------ item helpers

	private Slot hoveredSlot(AbstractContainerScreen<?> cs) {
		if (!hoveredTried) {
			hoveredTried = true;
			for (Class<?> c = cs.getClass(); c != null && hovered == null; c = c.getSuperclass()) {
				for (Field f : c.getDeclaredFields()) {
					if (Slot.class.isAssignableFrom(f.getType()) && (f.getName().equals("hoveredSlot") || c == AbstractContainerScreen.class)) {
						try { f.setAccessible(true); hovered = f; if (f.getName().equals("hoveredSlot")) break; } catch (Exception ignored) { }
					}
				}
			}
			if (hovered == null) Herald.LOG.warn("pet guard: no hovered-slot field, click guard off (menu + XP warnings still work)");
		}
		if (hovered == null) return null;
		try { return (Slot) hovered.get(cs); } catch (Exception e) { return null; }
	}

	static List<String> lore(ItemStack st) {
		List<String> out = new ArrayList<>();
		ItemLore l = st.get(DataComponents.LORE);
		if (l != null) for (Component c : l.lines()) out.add(Guide.plain(c.getString()));
		return out;
	}

	private static long maxXp(String s) {
		long best = 0;
		Matcher m = XP.matcher(s);
		while (m.find()) best = Math.max(best, amount(m.group(1), m.group(2)));
		return best;
	}

	private static String skillOf(String s) { Matcher m = XP.matcher(s); return m.find() ? m.group(3) : "skill"; }

	private static long amount(String n, String suf) {
		double v;
		try { v = Double.parseDouble(n.replace(",", "")); } catch (Exception e) { return 0; }
		if (suf != null) v *= switch (suf.toLowerCase(Locale.ROOT)) { case "k" -> 1_000; case "m" -> 1_000_000; default -> 1; };
		return Math.round(v);
	}
}
