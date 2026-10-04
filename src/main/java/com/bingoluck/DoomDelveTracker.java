package com.bingoluck;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Tracks how many times the player has completed each Doom of Mokhaiotl delve level.
 *
 * The hiscores only count deep delves, but uniques can drop from delve 2 onwards at rates that change
 * with every level, so exact per-level completions are what drop luck needs. The in-game scoreboard
 * (opened from the lobby) shows the player's exact count for each level; that seeds the counters. After
 * that each "Delve level: N duration: ..." game message adds one, so the scoreboard doesn't need to be
 * reopened. Nothing is counted until the scoreboard has been read once, because there'd be no starting
 * point to count from.
 *
 * Only the player's own counters are kept, saved in the RuneLite profile.
 */
@Slf4j
@Singleton
class DoomDelveTracker
{
	static final int LEVELS = 8;                       // delve levels 1..8 are counted separately
	private static final String CONFIG_KEY = "doomDelves";
	private static final Pattern COMPLETION = Pattern.compile("Delve level:\\s*(\\d+)(\\+)?\\s*duration:");

	private final ConfigManager configManager;

	private final int[] levels = new int[LEVELS];      // levels[0] is delve level 1
	private int past8;                                 // completions of every level beyond 8 (the "8+" row)
	private boolean seeded;
	private boolean loaded;
	private boolean dirty;

	@Inject
	DoomDelveTracker(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	synchronized void loadIfNeeded()
	{
		if (loaded)
		{
			return;
		}
		String saved = configManager.getRSProfileConfiguration(BingoLuckSyncConfig.GROUP, CONFIG_KEY);
		if (saved != null)
		{
			// format: "<past8>;<level1>,<level2>,...,<level8>"
			String[] halves = saved.trim().split(";");
			if (halves.length == 2)
			{
				String[] parts = halves[1].split(",");
				if (parts.length == LEVELS)
				{
					try
					{
						past8 = Integer.parseInt(halves[0]);
						for (int i = 0; i < LEVELS; i++)
						{
							levels[i] = Integer.parseInt(parts[i]);
						}
						seeded = true;
					}
					catch (NumberFormatException e)
					{
						seeded = false;
					}
				}
			}
		}
		loaded = true;
	}

	synchronized void reset()
	{
		for (int i = 0; i < LEVELS; i++)
		{
			levels[i] = 0;
		}
		past8 = 0;
		seeded = false;
		loaded = false;
		dirty = false;
	}

	/** The scoreboard is the game's own record, so it replaces whatever has been counted so far. */
	synchronized void seed(int[] scoreboardLevels, int scoreboardPast8)
	{
		boolean changed = !seeded || past8 != scoreboardPast8;
		for (int i = 0; i < LEVELS; i++)
		{
			changed |= levels[i] != scoreboardLevels[i];
			levels[i] = scoreboardLevels[i];
		}
		past8 = scoreboardPast8;
		seeded = true;
		if (changed)
		{
			dirty = true;
		}
	}

	/** Counts a completion from a game message. Returns true if the message was a delve completion. */
	synchronized boolean onGameMessage(String message)
	{
		Matcher m = COMPLETION.matcher(message);
		if (!m.find())
		{
			return false;
		}
		if (!seeded)
		{
			return true;                               // a delve message, but nothing to count from yet
		}
		int level = Integer.parseInt(m.group(1));
		if (m.group(2) != null || level > LEVELS)
		{
			past8++;
		}
		else if (level >= 1)
		{
			levels[level - 1]++;
		}
		dirty = true;
		return true;
	}

	/** A copy to send, or null if there's nothing new. */
	synchronized DoomDelveData takeDirtySnapshot(String player)
	{
		if (!seeded || !dirty)
		{
			return null;
		}
		dirty = false;
		List<Integer> copy = new ArrayList<>(LEVELS);
		for (int count : levels)
		{
			copy.add(count);
		}
		return new DoomDelveData(player, copy, past8);
	}

	/** Puts a snapshot back after a failed send so it is tried again. */
	synchronized void markDirty()
	{
		dirty = true;
	}

	synchronized void save()
	{
		if (!loaded || !seeded)
		{
			return;
		}
		StringBuilder sb = new StringBuilder().append(past8).append(';');
		for (int i = 0; i < LEVELS; i++)
		{
			sb.append(i == 0 ? "" : ",").append(levels[i]);
		}
		configManager.setRSProfileConfiguration(BingoLuckSyncConfig.GROUP, CONFIG_KEY, sb.toString());
	}
}
