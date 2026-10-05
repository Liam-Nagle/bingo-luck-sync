# Changelog

What has changed in each version of Bingo Luck Sync, newest first. When the plugin updates, it shows a short
message in your game chat once, pointing here.

## 1.1

- **Theatre of Blood deaths are now counted during the raid** from the game's own "has died" chat messages,
  so your raid can be reported with deaths even if you never open the performance board. If you do open
  it, its figures are used instead. This hasn't been tested in a live raid yet.
- **The plugin tells you in the game chat when something is wrong:** once if your plugin token is
  refused, and once if uploads keep failing. Nothing else is shown in chat, and each message appears at
  most once per session.
- This changelog, and a one-time chat message after an update.

## 1.0

First release on the Plugin Hub.

- Sends your own collection log pages, kill counters, Doom of Mokhaiotl delve completions per level and
  Chambers of Xeric, Theatre of Blood and Tombs of Amascut completions to your group's OSRS Bingo Board.
- Keeps kill counters across logouts and resends anything that hasn't been uploaded yet.
- Retries uploads when the server is busy or unreachable.
