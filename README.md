# Engine Notify

Client-only Fabric mod (Minecraft 26.2) that shows SkyBlock Engine notifications in game: a toast, a chat line with an [Open] link to the Todoist task, and a sound.

- Reads `config/engine-notify/feed.json` (written every 5 min by the SkyBlock Engine bridge script).
- Shows each item once, at its time; marks it in `config/engine-notify/shown.json`.
- Settings: `config/engine-notify/config.json` (`toast`, `chat`, `sound`, `volume`, `kinds` → e.g. `{"minions": false}`).
- Commands (local only): `/enginenotify` or `/enginenotify list`, `/enginenotify test`, `/enginenotify reload`.

It never sends anything to Hypixel or any server and never touches gameplay.
