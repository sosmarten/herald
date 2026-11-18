# Herald

Client-only Fabric mod (Minecraft 26.2) that brings the ⛏️ SkyBlock Engine into the game: each notification shows as a toast, a chat line with an [Open] link to the Todoist task, and a sound.

- Reads `config/herald/feed.json` (written every 5 min by the SkyBlock Engine bridge script).
- Shows each item once, at its time; marks it in `config/herald/shown.json`.
- Buttons: each notification can carry game commands from the engine (`/warp`, `/shnavigate`, `/recipe`, `/hub`, `/is`, `/pickupstash`, `/pets` only). Clicking a button runs it exactly as if you typed it. When there's only one command it's also copied to your clipboard.
- Settings: `config/herald/config.json` (`toast`, `chat`, `sound`, `clipboard`, `volume`, `kinds` → e.g. `{"minions": false}`, `openIn`: `"app"` opens [Open] links in the Todoist desktop app via `todoist://`, `"browser"` in the web app).
- Commands (local only): `/herald` or `/herald list`, `/herald test`, `/herald reload`.

It never sends anything to Hypixel on its own and never touches gameplay.

HUD (1.8.0): the panel copies the look of your SkyHanni Custom Scoreboard (see-through dark box, rounded corners, padding) straight from `config/skyhanni/config.json`, so changing the scoreboard background changes Herald too. Long lines end in "…" (`hudMaxWidth` in config.json, default 190). The bottom line shows the next timer (traps left out) and the last sync time.

1.9.0 (17.11.2026): a step you finished or skipped stays done for 20 h, also in the next session's plan (it used to come back). The step title and what-to-do line wrap over 2–3 lines instead of being cut. New J key and `/herald info`: the whole step in chat, with its Todoist note, buttons and [Skip], so you don't have to open Todoist. Minion steps now carry a craft button for the enchanted items Supercraft has to make first.

1.10.0 (18.11.2026) 🐾 Pet Guard, after 17.11 when Bestiary 30–135 went to the Lion instead of the Tiger:
- Knows your active pet from Hypixel's lines ("You summoned your …", "Autopet equipped your …"), the tab list "Pet:" widget and the /pets menu.
- The engine says which pet a plan needs: a guide step with `Pet: Tiger` in its text, or a feed alert with a `pet` key (`{"pet":"Tiger","gui":"(?i)bestiary","pattern":"…"}`).
- Click guard: clicking an XP reward ("Click to claim" + "+N Combat Experience", or any claim in a guarded menu) with the wrong or unknown pet is cancelled on your side (nothing is sent), with a red toast and a low double note. Click the same item again within 4 s to claim anyway. Big claims (250,000+ XP, `petGuardMinXp`) with no plan ask once per menu.
- Opening a guarded menu with the wrong pet warns right away; "+N Combat XP" in chat/action bar on the wrong pet gives a STOP alert (backup).
- HUD: "Pet: Tiger ✔" / "Pet: Lion ✘ need Tiger" under the step. `/herald pet` = status, `/herald guard pause 10` / `/herald guard on`, `petGuard: false` in config.json turns it off.
- Steps now also finish from chat lines that came BEFORE the step existed: Herald keeps the last few hours of chat in `config/herald/chat_recent.json` (survives restarts) and, when a plan or a new step arrives, counts matching lines from the last 90 min (`replayMin`) that no step has used yet. E.g. traps collected before the session plan arrived now tick "Collect all 5 traps". Each line counts for one step only.
- "Collect all 5 traps" also finishes on Hypixel's real collect lines ("You caught a Mossybit Shard!" ×5 from the Hunting Toolkit); the engine only listened for "…Huntrap and it got sent to your Hunting Toolkit". `extraDone` in config.json adds more (step id prefix → regex).
- The pet HUD line and "wrong pet" toasts only show while a pet step is on, a guarded menu is open, or the plan's chat line (e.g. "You have 21 pending Bestiary Milestones") came in the last 5 min, so other activities never nag.
- A done/skipped step whose text the engine changes (a recalibrated plan) comes back instead of staying done for 20 h (`doneTitle` in guide_state.json).

1.10.1 (18.11.2026):
- Pet Guard: Hypixel's repeating "21 pending Bestiary Milestones" line no longer keeps a red "need Tiger" line on the HUD while you do something else (1 min window, a reminder at most every 30 min). Rules can be per skill too (`{"pet":"Frog","pattern":"\\+[\\d,.]+ Foraging"}`): Foraging XP on another pet = one quiet heads-up every 10 min, never a block.
- H on a step with no command shows its details once; pressing again within 20 s only shows a small toast (no more 7 copies in chat).
- The bottom line says "! plan 47 min old" when the engine's plan hasn't refreshed for 45+ min (bridge or sync stuck).

1.11.0 (18.11.2026) — Herald talks back to the engine:
- Writes `config/herald/events.json` (last 24 h): every step ticked (chat), finished with K (with the 8 chat lines of the 3 min before, so the engine can learn which line means done), or undone; the active pet when it changes; the island from the tab list once you've stayed 3 min; any claim Pet Guard stopped (item name + lore, to match Hypixel's real wording).
- The PC bridge (v12) forwards new entries. The engine keeps a step you ticked or skipped out of the plan for 20 h, and switches the session focus to the island you stayed on.
- Steps can tick themselves from the ⚙️ Actions "done" column (a chat regex, optional " ×N" count).
