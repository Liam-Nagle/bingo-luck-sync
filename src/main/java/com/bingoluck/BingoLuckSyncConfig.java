package com.bingoluck;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(BingoLuckSyncConfig.GROUP)
public interface BingoLuckSyncConfig extends Config
{
	String GROUP = "bingoluck-sync";

	@ConfigSection(
		name = "Sync",
		description = "Settings for sending your data to your group's website",
		position = 0
	)
	String syncSection = "sync";

	@ConfigItem(
		keyName = "enableSync",
		name = "Enable sync",
		description = "Send your own collection log, kill counters and raid completions to your group's OSRS Bingo Board. "
			+ "Only data about your own account is sent: your RuneScape name, collection log items and kill counts, "
			+ "and raid results (raid, mode, kill count, points, raid level, team size).",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		section = syncSection,
		position = 0
	)
	default boolean enableSync()
	{
		return false;
	}

	@ConfigItem(
		keyName = "pluginToken",
		name = "Plugin token",
		description = "The plugin token from your group's OSRS Bingo Board admin. It only lets this plugin send your data.",
		secret = true,
		section = syncSection,
		position = 1
	)
	default String pluginToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "syncCollectionLog",
		name = "Sync collection log",
		description = "Send collection log pages (items and kill counts) when you open them in-game.",
		section = syncSection,
		position = 2
	)
	default boolean syncCollectionLog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncKillCounts",
		name = "Sync kill counts",
		description = "Keep your kill and completion counts up to date after you have opened a collection log page once, "
			+ "using NPC loot and kill count messages, and send them.",
		section = syncSection,
		position = 3
	)
	default boolean syncKillCounts()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncRaids",
		name = "Sync raid completions",
		description = "Send Chambers of Xeric, Theatre of Blood and Tombs of Amascut completions.",
		section = syncSection,
		position = 4
	)
	default boolean syncRaids()
	{
		return true;
	}
}
