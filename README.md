# Bingo Luck Sync

The RuneLite companion for **OSRS Bingo Board**, a bingo, drop-tracking and luck-statistics service for
Old School RuneScape groups (Group Ironman teams, clans and friend groups). It lets each player
optionally send their **own** collection log, kill counters and raid results to their group's board, so
the board can show how lucky each player has really been against exact drop rates - something the
hiscores and the base game can't provide.

## Status

The Bingo Board is under active development. It is built for many groups - each group has its own
board and its own plugin token - and is currently in use by a single group. Other groups are being set
up on request while that work continues; if you would like to be considered, open an issue on this
repository. This plugin is the **start** of the RuneLite side of the project: today it covers the
collection log, a few kill counters that aren't on the hiscores, and raid results, and it will grow as
the service does.

It does nothing unless your group has a Bingo Board and you have a plugin token from your group's admin.

## What it does

- **Collection log:** when you open a collection log page in-game, the plugin reads the obtained
  items, their quantities and the kill count lines the game shows on that page.
  Pages you have not opened are never sent, so click through the log once to sync everything.
- **Kill counts:** after you have opened a collection log page once, the plugin keeps that page's
  kill/completion counts current by counting NPC loot events and reading the game's kill count chat
  messages, saves them in your RuneLite profile, and sends them in batches. Opening the page again
  re-syncs any drift. Only pages you have opened are tracked.
- **Raid completions:** when you finish Chambers of Xeric, Theatre of Blood or Tombs of Amascut it
  records the raid, mode, your completion count, and the values the game gives your client:
  - Chambers of Xeric: team points, your personal points, team size as displayed by the game.
  - Tombs of Amascut: your personal points (exact, as sent by the game; excludes the 5,000 starting
    points), raid level and team size.
  - Theatre of Blood: mode, team size, and from the end-of-raid performance board your own death
    count, the team's total deaths (a number only) and whether you were the raid MVP. Other
    players' names on that board are never stored or sent.

## Setup

Your group's admin creates a plugin token for the group's board. Paste it into the plugin's
**Plugin token** setting and switch on **Enable sync**. Without a token the plugin does nothing.

## What is sent, and where

Nothing is sent unless you turn on **Enable sync** and enter your group's plugin token. It is off by default.

When enabled, data is sent over HTTPS to the Bingo Board's server, which is run by the plugin author.
It is **not** controlled or verified by the RuneLite developers, and the server necessarily sees your IP
address. Each request contains:

- your RuneScape name,
- the collection log page, items (id, name, quantity, obtained) and kill count text,
- your kill/completion counters for the collection log pages you have opened (source name and count),
- or the raid completion fields listed above, plus the time it finished,
- your group's plugin token in the `X-Plugin-Token` header. The token is issued by your group's admin, only works for this plugin's upload endpoints (collection log, raids and kill counts), and the admin can rotate it at any time.

Only data about your own account is sent. The plugin does not read other players' collection logs
(it ignores a collection log opened through a player-owned house host book), does not send other
players' names, location, equipment or any other account information, does not read or write
files itself (the saved kill counters live in RuneLite's own profile settings), and does not run on
seasonal, Deadman, tournament, fresh start or beta worlds.

## Source and licence

BSD 2-Clause. See `LICENSE`. This plugin is not affiliated with Jagex or RuneLite.
