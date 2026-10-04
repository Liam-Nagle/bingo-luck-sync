package com.bingoluck;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.ScriptID;
import net.runelite.api.WorldType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Bingo Luck Sync",
	description = "Companion for OSRS Bingo Board: optionally sends your own collection log, kill counters and raid results to your group's board",
	tags = {"collection log", "raids", "luck", "bingo", "gim", "sync"}
)
public class BingoLuckSyncPlugin extends Plugin
{
	private static final String COX_COMPLETE_MESSAGE = "Congratulations - your raid is complete!";
	private static final Pattern COX_TEAM_SIZE = Pattern.compile("Team size: ?(.+?) ?Duration");
	private static final Pattern CHAT_KC = Pattern.compile("^Your (.+?) (?:kill|success) count is: ?([0-9,]+)");
	private static final int KC_FLUSH_TICKS = 100;
	private static final Pattern RAID_KC = Pattern.compile(
		"^Your completed (Chambers of Xeric|Theatre of Blood|Tombs of Amascut):? ?(.*?) ?count is: ?([0-9,]+)");

	/** Raid data can arrive in either order around the kill count message; wait this long for the other half. */
	private static final int PAIR_WINDOW_TICKS = 50;
	private static final int PAIR_TIMEOUT_TICKS = 30;
	/** ToA only sends your points at the very end of the raid, well after the kill count message. */
	private static final int TOA_PAIR_TIMEOUT_TICKS = 100;
	/** How long after a raid was sent a late value (points, board) will still update it. */
	private static final int LATE_UPDATE_TICKS = 3000;
	/** "Never happened": far enough back that tickCount - NEVER cannot overflow or look recent. */
	private static final int NEVER = -1_000_000;

	private static final EnumSet<WorldType> UNSUPPORTED_WORLDS = EnumSet.of(
		WorldType.SEASONAL, WorldType.DEADMAN, WorldType.TOURNAMENT_WORLD,
		WorldType.FRESH_START_WORLD, WorldType.NOSAVE_MODE, WorldType.BETA_WORLD);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private BingoLuckSyncConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private SyncClient syncClient;

	@Inject
	private KillCountTracker killCountTracker;

	@Inject
	private DoomDelveTracker doomDelveTracker;

	@Inject
	private Gson gson;

	/** Last payload sent per collection log page, so unchanged pages are not re-sent. */
	private final Map<String, String> lastSentPages = new HashMap<>();
	private final Map<String, Integer> lastObtainedCounts = new HashMap<>();

	private int tickCount;

	// Raid state
	private PendingKc pendingKc;
	private int coxTotalPoints = -1;
	private int coxPersonalPoints = -1;
	private String coxTeamSizeLabel;
	private int coxSnapshotTick = NEVER;
	private int toaPersonalPoints = -1;
	private int toaPersonalTick = NEVER;
	private RaidCompletion lastToaSent;
	private int lastToaSentTick = NEVER;
	private int lastToaRaidLevel = -1;
	private int lastToaTeamSize = -1;
	private int lastTobTeamSize = -1;

	// ToB performance board (shown after Verzik): deaths for you and the team, and whether you were MVP
	private int tobDeaths = -1;
	private int tobTeamDeaths = -1;
	private int tobMvp = -1;
	private int tobBoardTick = NEVER;
	private RaidCompletion lastTobSent;
	private int lastTobSentTick = NEVER;

	@Override
	protected void startUp()
	{
		syncClient.start();
		resetSessionState();
	}

	@Override
	protected void shutDown()
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			killCountTracker.save();
			doomDelveTracker.save();
		}
		resetSessionState();
		killCountTracker.reset();
		doomDelveTracker.reset();
		syncClient.stop();
	}

	@Provides
	BingoLuckSyncConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BingoLuckSyncConfig.class);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			resetSessionState();
			killCountTracker.reset();
			doomDelveTracker.reset();
		}
	}

	// ---------------------------------------------------------------- collection log

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.COLLECTION_DRAW_LIST && config.syncCollectionLog() && canSync())
		{
			clientThread.invokeLater(this::readCollectionLogPage);
		}
	}

	private void readCollectionLogPage()
	{
		// Never read a collection log that belongs to someone else (e.g. a POH host's book).
		if (client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) != 0)
		{
			return;
		}

		Widget header = client.getWidget(InterfaceID.Collection.HEADER_TEXT);
		Widget itemsContainer = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
		if (header == null || itemsContainer == null)
		{
			return;
		}

		Widget[] headerChildren = header.getDynamicChildren();
		Widget[] itemWidgets = itemsContainer.getDynamicChildren();
		if (headerChildren == null || headerChildren.length == 0 || itemWidgets == null || itemWidgets.length == 0)
		{
			return;
		}

		String pageName = Text.removeTags(headerChildren[0].getText());
		if (pageName.isEmpty())
		{
			return;
		}

		List<String> killCounts = new ArrayList<>();
		for (int i = 2; i < headerChildren.length; i++)
		{
			String text = Text.removeTags(headerChildren[i].getText());
			if (!text.isEmpty())
			{
				killCounts.add(text);
			}
		}

		List<CollectionLogPageData.Item> items = new ArrayList<>();
		int obtainedCount = 0;
		for (Widget w : itemWidgets)
		{
			if (w.getItemId() <= 0)
			{
				continue;
			}
			ItemComposition comp = itemManager.getItemComposition(w.getItemId());
			boolean obtained = w.getOpacity() == 0;
			int quantity = obtained ? w.getItemQuantity() : 0;
			if (obtained)
			{
				obtainedCount++;
			}
			items.add(new CollectionLogPageData.Item(w.getItemId(), comp.getMembersName(), quantity, obtained));
		}

		// When pages are opened in quick succession the item data can be half-loaded and look
		// like nothing is obtained; never send a page that has fewer obtained items than before.
		Integer previous = lastObtainedCounts.get(pageName);
		if (previous != null && obtainedCount < previous)
		{
			return;
		}

		String player = playerName();
		if (player == null)
		{
			return;
		}

		if (config.syncKillCounts())
		{
			killCountTracker.loadIfNeeded();
			killCountTracker.onPageLines(killCounts);
		}

		CollectionLogPageData data = new CollectionLogPageData(player, pageName, items, killCounts);
		String json = gson.toJson(data);
		if (json.equals(lastSentPages.get(pageName)))
		{
			return;
		}

		lastSentPages.put(pageName, json);
		lastObtainedCounts.put(pageName, obtainedCount);
		syncClient.postCollectionLogPage(config.pluginToken(), data);
	}

	// ---------------------------------------------------------------- raids

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tickCount++;

		if (!canSync())
		{
			return;
		}

		// The raid varbits are reset once the raid ends, so keep the last values seen inside it.
		int toaLevel = client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL);
		if (toaLevel > 0)
		{
			lastToaRaidLevel = toaLevel;
			lastToaTeamSize = toaTeamSize();
		}
		int tobSize = tobTeamSize();
		if (tobSize > 0)
		{
			lastTobTeamSize = tobSize;
		}

		if (config.syncKillCounts())
		{
			killCountTracker.loadIfNeeded();
			doomDelveTracker.loadIfNeeded();
			if (tickCount % KC_FLUSH_TICKS == 0)
			{
				flushKillCounts();
				flushDoomDelves();
			}
		}

		int timeout = pendingKc != null && "Tombs of Amascut".equals(pendingKc.raid)
			? TOA_PAIR_TIMEOUT_TICKS : PAIR_TIMEOUT_TICKS;
		if (pendingKc != null && tickCount - pendingKc.tick > timeout)
		{
			finalizeRaid(true);
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.TOB_INFOBOARD && config.syncRaids() && canSync())
		{
			// Child text is filled in on the tick after the interface loads.
			clientThread.invokeLater(this::readTobBoard);
		}
		else if (event.getGroupId() == InterfaceID.DOM_SCOREBOARD && config.syncKillCounts() && canSync())
		{
			clientThread.invokeLater(this::readDoomScoreboard);
		}
	}

	/** Reads the player's own per-level delve completions from the Doom of Mokhaiotl scoreboard. */
	private void readDoomScoreboard()
	{
		int[] widgetIds = {
			InterfaceID.DomScoreboard.P_TOTAL_LEVEL_1_VAL, InterfaceID.DomScoreboard.P_TOTAL_LEVEL_2_VAL,
			InterfaceID.DomScoreboard.P_TOTAL_LEVEL_3_VAL, InterfaceID.DomScoreboard.P_TOTAL_LEVEL_4_VAL,
			InterfaceID.DomScoreboard.P_TOTAL_LEVEL_5_VAL, InterfaceID.DomScoreboard.P_TOTAL_LEVEL_6_VAL,
			InterfaceID.DomScoreboard.P_TOTAL_LEVEL_7_VAL, InterfaceID.DomScoreboard.P_TOTAL_LEVEL_8_VAL};
		int[] counts = new int[widgetIds.length];
		for (int i = 0; i < widgetIds.length; i++)
		{
			Widget w = client.getWidget(widgetIds[i]);
			if (w == null || w.getText() == null)
			{
				return;                                // not drawn yet; don't seed from a half-loaded board
			}
			counts[i] = parseCount(w.getText());
		}
		Widget deep = client.getWidget(InterfaceID.DomScoreboard.P_TOTAL_LEVEL_8__VAL);
		int past8 = deep == null || deep.getText() == null ? 0 : Math.max(0, parseCount(deep.getText()));
		for (int i = 0; i < counts.length; i++)
		{
			counts[i] = Math.max(0, counts[i]);
		}
		log.debug("Doom scoreboard: levels={} past8={}", Arrays.toString(counts), past8);
		doomDelveTracker.loadIfNeeded();
		doomDelveTracker.seed(counts, past8);
	}

	private void readTobBoard()
	{
		String me = client.getLocalPlayer() == null ? null : Text.sanitize(client.getLocalPlayer().getName());
		if (me == null)
		{
			return;
		}

		int[] nameIds = {
			InterfaceID.TobInfoboard.PLAYERNAME0, InterfaceID.TobInfoboard.PLAYERNAME1,
			InterfaceID.TobInfoboard.PLAYERNAME2, InterfaceID.TobInfoboard.PLAYERNAME3,
			InterfaceID.TobInfoboard.PLAYERNAME4};
		int[] deathIds = {
			InterfaceID.TobInfoboard.DEATHS0, InterfaceID.TobInfoboard.DEATHS1,
			InterfaceID.TobInfoboard.DEATHS2, InterfaceID.TobInfoboard.DEATHS3,
			InterfaceID.TobInfoboard.DEATHS4};

		// Only your own row and the totals are kept; other players' names are never stored or sent.
		int myDeaths = -1;
		for (int i = 0; i < nameIds.length; i++)
		{
			Widget name = client.getWidget(nameIds[i]);
			Widget deaths = client.getWidget(deathIds[i]);
			if (name != null && deaths != null && me.equals(Text.sanitize(name.getText())))
			{
				myDeaths = parseCount(deaths.getText());
				break;
			}
		}

		Widget total = client.getWidget(InterfaceID.TobInfoboard.TOTALDEATHS);
		Widget mvp = client.getWidget(InterfaceID.TobInfoboard.MVP_PLAYER);
		int teamDeaths = total == null ? -1 : parseCount(total.getText());
		int mvpFlag = mvp == null || mvp.getText() == null || mvp.getText().isEmpty()
			? -1 : (me.equals(Text.sanitize(mvp.getText())) ? 1 : 0);

		log.debug("ToB board: myDeaths={} teamDeaths={} mvp={}", myDeaths, teamDeaths, mvpFlag);
		if (myDeaths < 0 && teamDeaths < 0)
		{
			return;
		}

		tobDeaths = myDeaths;
		tobTeamDeaths = teamDeaths;
		tobMvp = mvpFlag;
		tobBoardTick = tickCount;

		if (pendingKc != null)
		{
			finalizeRaid(false);
		}
		else if (lastTobSent != null && lastTobSent.getDeaths() < 0 && tickCount - lastTobSentTick < LATE_UPDATE_TICKS)
		{
			// The raid was already sent without deaths (board opened late): send the completed version.
			lastTobSent = lastTobSent.toBuilder().deaths(tobDeaths).teamDeaths(tobTeamDeaths).mvp(tobMvp).build();
			syncClient.postRaid(config.pluginToken(), lastTobSent);
		}
	}

	private static int parseCount(String text)
	{
		try
		{
			return Integer.parseInt(Text.removeTags(text).trim().replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (config.syncKillCounts() && canSync() && event.getNpc() != null)
		{
			killCountTracker.onNpcLoot(event.getNpc().getName());
		}
	}

	private void flushDoomDelves()
	{
		String player = playerName();
		DoomDelveData snapshot = player == null ? null : doomDelveTracker.takeDirtySnapshot(player);
		if (snapshot != null)
		{
			doomDelveTracker.save();
			syncClient.postDoomDelves(config.pluginToken(), snapshot);
		}
	}

	private void flushKillCounts()
	{
		List<KillCountData.Count> snapshot = killCountTracker.takeDirtySnapshot();
		String player = playerName();
		if (snapshot != null && player != null)
		{
			killCountTracker.save();
			syncClient.postKillCounts(config.pluginToken(), new KillCountData(player, snapshot));
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		// Transmitted to the client only at the very end of a Tombs of Amascut raid.
		// Excludes the 5,000 starting points that the game adds when rolling loot.
		if (event.getVarpId() == VarPlayerID.TOA_PERSONAL_CONTRIBUTION && event.getValue() > 0)
		{
			log.debug("ToA personal contribution varp changed to {}", event.getValue());
			toaPersonalPoints = event.getValue();
			toaPersonalTick = tickCount;
			if (pendingKc != null)
			{
				finalizeRaid(false);
			}
			else if (lastToaSent != null && lastToaSent.getPersonalPoints() < 0
				&& tickCount - lastToaSentTick < LATE_UPDATE_TICKS)
			{
				// The raid was already sent without points (they arrived late): send the completed version.
				lastToaSent = lastToaSent.toBuilder().personalPoints(toaPersonalPoints).build();
				syncClient.postRaid(config.pluginToken(), lastToaSent);
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		// Delve completions arrive as game or spam messages: "Delve level: 5 duration: 1:23 ..."
		if ((event.getType() == ChatMessageType.GAMEMESSAGE || event.getType() == ChatMessageType.SPAM)
			&& config.syncKillCounts() && canSync() && event.getMessage().contains("Delve level"))
		{
			doomDelveTracker.loadIfNeeded();
			doomDelveTracker.onGameMessage(Text.removeTags(event.getMessage()));
		}

		if (event.getType() != ChatMessageType.GAMEMESSAGE || !canSync())
		{
			return;
		}

		String message = Text.removeTags(event.getMessage());

		if (config.syncKillCounts())
		{
			Matcher chatKc = CHAT_KC.matcher(message);
			if (chatKc.find())
			{
				killCountTracker.loadIfNeeded();
				killCountTracker.onChatKillCount(chatKc.group(1), Integer.parseInt(chatKc.group(2).replace(",", "")));
			}
		}

		if (!config.syncRaids())
		{
			return;
		}

		if (message.startsWith(COX_COMPLETE_MESSAGE))
		{
			coxTotalPoints = client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSCORE);
			coxPersonalPoints = client.getVarpValue(VarPlayerID.RAIDS_PLAYERSCORE);
			Matcher size = COX_TEAM_SIZE.matcher(message);
			coxTeamSizeLabel = size.find() ? size.group(1) : null;
			coxSnapshotTick = tickCount;
			finalizeRaid(false);
			return;
		}

		Matcher kc = RAID_KC.matcher(message);
		if (kc.find())
		{
			log.debug("Raid kill count message: {}", message);
			String mode = modeFromText(kc.group(2));
			int raidKc = Integer.parseInt(kc.group(3).replace(",", ""));
			if (config.syncKillCounts())
			{
				killCountTracker.loadIfNeeded();
				killCountTracker.onRaidKillCount(kc.group(1), mode, raidKc);
			}
			pendingKc = new PendingKc(
				kc.group(1),
				mode,
				raidKc,
				tickCount,
				Instant.now().getEpochSecond());
			finalizeRaid(false);
		}
	}

	/**
	 * Sends the pending raid once the data it needs has arrived. {@code force} sends it with
	 * whatever is known (unknown values are -1) after waiting long enough for the rest.
	 */
	private void finalizeRaid(boolean force)
	{
		PendingKc pending = this.pendingKc;
		String player = playerName();
		if (pending == null || player == null)
		{
			return;
		}

		RaidCompletion.RaidCompletionBuilder builder = RaidCompletion.builder()
			.mode(pending.mode)
			.player(player)
			.killCount(pending.kc)
			.completedAt(pending.epochSecond)
			.totalPoints(-1)
			.personalPoints(-1)
			.raidLevel(-1)
			.teamSize(-1)
			.deaths(-1)
			.teamDeaths(-1)
			.mvp(-1);

		switch (pending.raid)
		{
			case "Chambers of Xeric":
				boolean coxFresh = tickCount - coxSnapshotTick <= PAIR_WINDOW_TICKS;
				if (!coxFresh && !force)
				{
					return;
				}
				builder.raid("COX");
				if (coxFresh)
				{
					builder.totalPoints(coxTotalPoints)
						.personalPoints(coxPersonalPoints)
						.teamSizeLabel(coxTeamSizeLabel);
				}
				break;

			case "Tombs of Amascut":
				boolean toaFresh = tickCount - toaPersonalTick <= PAIR_WINDOW_TICKS;
				if (!toaFresh && !force)
				{
					return;
				}
				int level = client.getVarbitValue(VarbitID.TOA_CLIENT_RAID_LEVEL);
				int size = toaTeamSize();
				builder.raid("TOA")
					.personalPoints(toaFresh ? toaPersonalPoints : -1)
					.raidLevel(level > 0 ? level : lastToaRaidLevel)
					.teamSize(size > 0 ? size : lastToaTeamSize);
				break;

			case "Theatre of Blood":
				boolean tobFresh = tickCount - tobBoardTick <= PAIR_WINDOW_TICKS;
				if (!tobFresh && !force)
				{
					return;
				}
				int tob = tobTeamSize();
				builder.raid("TOB").teamSize(tob > 0 ? tob : lastTobTeamSize);
				if (tobFresh)
				{
					builder.deaths(tobDeaths).teamDeaths(tobTeamDeaths).mvp(tobMvp);
				}
				break;

			default:
				this.pendingKc = null;
				return;
		}

		this.pendingKc = null;
		RaidCompletion completion = builder.build();
		if ("TOB".equals(completion.getRaid()))
		{
			lastTobSent = completion;
			lastTobSentTick = tickCount;
		}
		else if ("TOA".equals(completion.getRaid()))
		{
			lastToaSent = completion;
			lastToaSentTick = tickCount;
		}
		syncClient.postRaid(config.pluginToken(), completion);
	}

	private static String modeFromText(String text)
	{
		switch (text.trim())
		{
			case "Challenge Mode":
				return "CHALLENGE";
			case "Hard Mode":
				return "HARD";
			case "Story Mode":
				return "STORY";
			case "Entry Mode":
				return "ENTRY";
			case "Expert Mode":
				return "EXPERT";
			default:
				return "NORMAL";
		}
	}

	private int toaTeamSize()
	{
		return Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P0), 1)
			+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P1), 1)
			+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P2), 1)
			+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P3), 1)
			+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P4), 1)
			+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P5), 1)
			+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P6), 1)
			+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P7), 1);
	}

	private int tobTeamSize()
	{
		return Math.min(client.getVarbitValue(VarbitID.TOB_CLIENT_P0), 1)
			+ Math.min(client.getVarbitValue(VarbitID.TOB_CLIENT_P1), 1)
			+ Math.min(client.getVarbitValue(VarbitID.TOB_CLIENT_P2), 1)
			+ Math.min(client.getVarbitValue(VarbitID.TOB_CLIENT_P3), 1)
			+ Math.min(client.getVarbitValue(VarbitID.TOB_CLIENT_P4), 1);
	}

	// ---------------------------------------------------------------- helpers

	private boolean canSync()
	{
		if (!config.enableSync() || config.pluginToken().isEmpty() || client.getGameState() != GameState.LOGGED_IN)
		{
			return false;
		}
		for (WorldType type : client.getWorldType())
		{
			if (UNSUPPORTED_WORLDS.contains(type))
			{
				return false;
			}
		}
		return true;
	}

	private String playerName()
	{
		return client.getLocalPlayer() == null ? null : client.getLocalPlayer().getName();
	}

	private void resetSessionState()
	{
		lastSentPages.clear();
		lastObtainedCounts.clear();
		pendingKc = null;
		coxSnapshotTick = NEVER;
		toaPersonalTick = NEVER;
		lastToaSent = null;
		lastToaRaidLevel = -1;
		lastToaTeamSize = -1;
		lastTobTeamSize = -1;
		tobBoardTick = NEVER;
		lastTobSent = null;
	}

	private static final class PendingKc
	{
		final String raid;
		final String mode;
		final int kc;
		final int tick;
		final long epochSecond;

		PendingKc(String raid, String mode, int kc, int tick, long epochSecond)
		{
			this.raid = raid;
			this.mode = mode;
			this.kc = kc;
			this.tick = tick;
			this.epochSecond = epochSecond;
		}
	}
}
