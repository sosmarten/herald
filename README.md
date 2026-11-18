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
