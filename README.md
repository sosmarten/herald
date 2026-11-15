# Herald

Client-only Fabric mod (Minecraft 26.2) that brings the ⛏️ SkyBlock Engine into the game: each notification shows as a toast, a chat line with an [Open] link to the Todoist task, and a sound.

- Reads `config/herald/feed.json` (written every 5 min by the SkyBlock Engine bridge script).
- Shows each item once, at its time; marks it in `config/herald/shown.json`.
- Buttons: each notification can carry game commands from the engine (`/warp`, `/shnavigate`, `/recipe`, `/hub`, `/is` only). Clicking a button runs it exactly as if you typed it. When there's only one command it's also copied to your clipboard.
- Settings: `config/herald/config.json` (`toast`, `chat`, `sound`, `clipboard`, `volume`, `kinds` → e.g. `{"minions": false}`, `openIn`: `"app"` opens [Open] links in the Todoist desktop app via `todoist://`, `"browser"` in the web app).
- Commands (local only): `/herald` or `/herald list`, `/herald test`, `/herald reload`.

It never sends anything to Hypixel on its own and never touches gameplay.

HUD (1.8.0): the panel copies the look of your SkyHanni Custom Scoreboard (see-through dark box, rounded corners, padding) straight from `config/skyhanni/config.json`, so changing the scoreboard background changes Herald too. Long lines end in "…" (`hudMaxWidth` in config.json, default 190). The bottom line shows the next timer (traps left out) and the last sync time.
