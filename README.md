# Herald

Client-only Fabric mod (Minecraft 26.2) that brings the ⛏️ SkyBlock Engine into the game: each notification shows as a toast, a chat line with an [Open] link to the Todoist task, and a sound.

- Reads `config/herald/feed.json` (written every 5 min by the SkyBlock Engine bridge script).
- Shows each item once, at its time; marks it in `config/herald/shown.json`.
- Buttons: each notification can carry game commands from the engine (`/warp`, `/shnavigate`, `/recipe`, `/hub`, `/is` only). Clicking a button runs it exactly as if you typed it. When there's only one command it's also copied to your clipboard.
- Settings: `config/herald/config.json` (`toast`, `chat`, `sound`, `clipboard`, `volume`, `kinds` → e.g. `{"minions": false}`, `openIn`: `"app"` opens [Open] links in the Todoist desktop app via `todoist://`, `"browser"` in the web app).
- Commands (local only): `/herald` or `/herald list`, `/herald test`, `/herald reload`.

It never sends anything to Hypixel on its own and never touches gameplay.

HUD (1.8.0): the panel copies the look of your SkyHanni Custom Scoreboard (see-through dark box, rounded corners, padding) straight from `config/skyhanni/config.json`, so changing the scoreboard background changes Herald too. Long lines end in "…" (`hudMaxWidth` in config.json, default 190). The bottom line shows the next timer (traps left out) and the last sync time.

1.9.0 (17.11.2026): a step you finished or skipped stays done for 20 h, also in the next session's plan (it used to come back). The step title and what-to-do line wrap over 2–3 lines instead of being cut. New J key and `/herald info`: the whole step in chat, with its Todoist note, buttons and [Skip], so you don't have to open Todoist. Minion steps now carry a craft button for the enchanted items Supercraft has to make first.
