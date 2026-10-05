package com.bingoluck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TobDeathTrackerTest
{
	@Test
	public void ignoresDeathsOutsideARaid()
	{
		TobDeathTracker t = new TobDeathTracker();
		t.onGameMessage("Bob has died. Death count: 1", "Me");
		assertFalse(t.isActive());
		assertEquals(0, t.getTeamDeaths());
	}

	@Test
	public void countsYourDeathsAndTheTeams()
	{
		TobDeathTracker t = new TobDeathTracker();
		t.onGameMessage("You enter the Theatre of Blood (Normal Mode).", "Me");
		t.onGameMessage("Bob has died. Death count: 1", "Me");
		t.onGameMessage("Me has died. Death count: 2", "Me");
		t.onGameMessage("Me has died. Death count: 3", "Me");
		assertTrue(t.isActive());
		assertEquals(2, t.getMyDeaths());
		assertEquals(3, t.getTeamDeaths());
	}

	@Test
	public void entryResetsThePreviousRaid()
	{
		TobDeathTracker t = new TobDeathTracker();
		t.onGameMessage("You enter the Theatre of Blood", "Me");
		t.onGameMessage("Me has died. Death count: 1", "Me");
		t.onGameMessage("You enter the Theatre of Blood", "Me");
		assertEquals(0, t.getMyDeaths());
		assertEquals(0, t.getTeamDeaths());
	}

	@Test
	public void namesWithNonBreakingSpacesStillMatch()
	{
		TobDeathTracker t = new TobDeathTracker();
		t.onGameMessage("You enter the Theatre of Blood", "IM Thorgrim");
		t.onGameMessage("IM\u00A0Thorgrim has died. Death count: 1", "IM Thorgrim");
		assertEquals(1, t.getMyDeaths());
	}

	@Test
	public void unrelatedMessagesAreIgnored()
	{
		TobDeathTracker t = new TobDeathTracker();
		t.onGameMessage("You enter the Theatre of Blood", "Me");
		t.onGameMessage("Oh dear, you are dead!", "Me");
		t.onGameMessage("Bob has died. Death count: lots", "Me");
		assertEquals(0, t.getTeamDeaths());
	}
}
