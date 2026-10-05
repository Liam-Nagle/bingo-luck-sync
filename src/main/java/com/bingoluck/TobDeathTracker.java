package com.bingoluck;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Counts Theatre of Blood deaths from the game's own chat lines, so a raid can be reported even when
 * the score board was never opened. Only a count for you and a count for the team are kept - other
 * players' names are compared against yours and then forgotten.
 */
class TobDeathTracker
{
	static final String ENTRY_MESSAGE = "You enter the Theatre of Blood";
	private static final Pattern DEATH = Pattern.compile("^(.+?) has died\\. Death count: ?([0-9,]+)");

	private boolean inRaid;
	private int myDeaths;
	private int teamDeaths;

	/** True once a raid has been entered, so there are live numbers to fall back on. */
	boolean isActive()
	{
		return inRaid;
	}

	int getMyDeaths()
	{
		return myDeaths;
	}

	int getTeamDeaths()
	{
		return teamDeaths;
	}

	void reset()
	{
		inRaid = false;
		myDeaths = 0;
		teamDeaths = 0;
	}

	/** Feed every game message (colour tags already removed). {@code me} is your sanitized name. */
	void onGameMessage(String message, String me)
	{
		if (message.contains(ENTRY_MESSAGE))
		{
			reset();
			inRaid = true;
			return;
		}
		if (!inRaid)
		{
			return;
		}
		Matcher m = DEATH.matcher(message);
		if (!m.find())
		{
			return;
		}
		int reported;
		try
		{
			reported = Integer.parseInt(m.group(2).replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return;
		}
		// The line carries the running team total; counting lines too covers a total that is missing or lags.
		teamDeaths = Math.max(teamDeaths + 1, reported);
		if (me != null && me.equals(m.group(1).replace('\u00A0', ' ').trim()))
		{
			myDeaths++;
		}
	}
}
