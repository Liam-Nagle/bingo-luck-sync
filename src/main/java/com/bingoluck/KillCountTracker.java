package com.bingoluck;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Keeps the player's own kill/completion counters current without needing the collection log
 * to be reopened. A counter starts from an exact value (a collection log page, or a kill count
 * chat message) and is then advanced by NPC loot events, the same approach Dink uses. Opening
 * the collection log page again re-syncs any drift.
 *
 * Only counters that have been seeded are ever advanced, and only the player's own counters are
 * kept. State is saved per RuneLite profile so it survives restarts.
 */
@Slf4j
@Singleton
class KillCountTracker
{
	private static final String CONFIG_KEY = "killCounts";
	/** Collection log lines look like "Tormented Demon kills: 1,124" or "Tombs of Amascut (Expert) completions: 48". */
	private static final Pattern PAGE_LINE = Pattern.compile("^(.+?): ?([0-9][0-9,]*)$");
	private static final Pattern SUFFIX = Pattern.compile("\\s+(kills|completions|kill count|harvests|opened)$", Pattern.CASE_INSENSITIVE);

	private final ConfigManager configManager;

	/** display name -> kc, in insertion order. */
	private final Map<String, Integer> counts = new LinkedHashMap<>();
	/** lower-case name -> display name, for matching NPC and chat names to a counter. */
	private final Map<String, String> byLowerName = new LinkedHashMap<>();
	private boolean loaded;
	private boolean dirty;

	@Inject
	KillCountTracker(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	/** Loads saved counters for the current profile; call once logged in. */
	synchronized void loadIfNeeded()
	{
		if (loaded)
		{
			return;
		}
		String saved = configManager.getRSProfileConfiguration(BingoLuckSyncConfig.GROUP, CONFIG_KEY);
		if (saved != null)
		{
			for (String line : saved.split("\n"))
			{
				int tab = line.lastIndexOf('\t');
				if (tab > 0)
				{
					try
					{
						put(line.substring(0, tab), Integer.parseInt(line.substring(tab + 1).trim()), false);
					}
					catch (NumberFormatException ignored)
					{
						// skip a corrupt line
					}
				}
			}
		}
		loaded = true;
	}

	synchronized void reset()
	{
		counts.clear();
		byLowerName.clear();
		loaded = false;
		dirty = false;
	}

	/** Seeds/overwrites counters from a collection log page's kill count lines. */
	synchronized void onPageLines(List<String> lines)
	{
		for (String line : lines)
		{
			Matcher m = PAGE_LINE.matcher(line.trim());
			if (!m.matches() || line.startsWith("Personal Best"))
			{
				continue;
			}
			try
			{
				put(stripSuffix(m.group(1)), Integer.parseInt(m.group(2).replace(",", "")), true);
			}
			catch (NumberFormatException ignored)
			{
				// not a number we can use
			}
		}
	}

	/** Adds a kill when a tracked NPC dies and drops loot. Ignored unless that counter was seeded. */
	synchronized void onNpcLoot(String npcName)
	{
		String display = npcName == null ? null : byLowerName.get(npcName.toLowerCase(Locale.ROOT));
		if (display != null)
		{
			put(display, counts.get(display) + 1, true);
		}
	}

	/**
	 * A "Your X kill count is: N" chat message is exact, so it corrects a counter. It only
	 * touches counters a collection log page has already seeded, so a chat name that differs
	 * from the page's own label can't create a duplicate.
	 */
	synchronized void onChatKillCount(String boss, int kc)
	{
		String display = byLowerName.get(boss.toLowerCase(Locale.ROOT));
		if (display != null)
		{
			put(display, kc, true);
		}
	}

	/** A raid completion message is exact too. Names match the collection log's own labels. */
	synchronized void onRaidKillCount(String raid, String mode, int kc)
	{
		put(raidCounterName(raid, mode), kc, true);
	}

	static String raidCounterName(String raid, String mode)
	{
		switch (mode)
		{
			case "CHALLENGE":
				return raid + " (CM)";
			case "HARD":
				return raid + " (Hard)";
			case "ENTRY":
				return raid + " (Entry)";
			case "EXPERT":
				return raid + " (Expert)";
			case "STORY":
				return raid + " (Story)";
			default:
				return raid;
		}
	}

	/** Snapshot to send, or null if nothing changed since the last one. */
	synchronized List<KillCountData.Count> takeDirtySnapshot()
	{
		if (!dirty || counts.isEmpty())
		{
			return null;
		}
		dirty = false;
		List<KillCountData.Count> out = new ArrayList<>(counts.size());
		for (Map.Entry<String, Integer> e : counts.entrySet())
		{
			out.add(new KillCountData.Count(e.getKey(), e.getValue()));
		}
		return out;
	}

	/** Saves counters to the RuneLite profile. Call off the hot path (it is a config write). */
	synchronized void save()
	{
		if (!loaded)
		{
			return;
		}
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Integer> e : counts.entrySet())
		{
			sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
		}
		configManager.setRSProfileConfiguration(BingoLuckSyncConfig.GROUP, CONFIG_KEY, sb.toString());
	}

	private void put(String name, int kc, boolean markDirty)
	{
		if (name.isEmpty() || kc < 0)
		{
			return;
		}
		Integer old = counts.put(name, kc);
		byLowerName.put(name.toLowerCase(Locale.ROOT), name);
		if (markDirty && (old == null || old != kc))
		{
			dirty = true;
		}
	}

	private static String stripSuffix(String label)
	{
		return SUFFIX.matcher(label.trim()).replaceFirst("").trim();
	}
}
