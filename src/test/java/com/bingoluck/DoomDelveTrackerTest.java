package com.bingoluck;

import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DoomDelveTrackerTest
{
	// The tracker only touches the RuneLite config when loading/saving, which these tests don't do.
	private final DoomDelveTracker tracker = new DoomDelveTracker(null);

	@Test
	public void countsNothingUntilTheScoreboardHasBeenRead()
	{
		assertTrue(tracker.onGameMessage("Delve level: 5 duration: 1:23"));      // recognised, but no starting point
		assertNull(tracker.takeDirtySnapshot("Tester"));
	}

	@Test
	public void seedsFromTheScoreboardThenCountsEachCompletionByLevel()
	{
		tracker.seed(new int[]{10, 9, 8, 7, 6, 5, 4, 3}, 2);
		tracker.takeDirtySnapshot("Tester");                                     // clear the seed's change
		tracker.onGameMessage("Delve level: 5 duration: 1:23");
		tracker.onGameMessage("Delve level: 5 duration: 1:30");
		tracker.onGameMessage("Delve level: 8 duration: 2:00");
		tracker.onGameMessage("Delve level: 8+ duration: 2:10");
		tracker.onGameMessage("Delve level: 11 duration: 3:00");                 // deeper than 8 counts as 8+

		DoomDelveData data = tracker.takeDirtySnapshot("Tester");
		assertNotNull(data);
		assertEquals(Arrays.asList(10, 9, 8, 7, 8, 5, 4, 4), data.getLevels());
		assertEquals(4, data.getPast8());
		assertNull("nothing new since the last snapshot", tracker.takeDirtySnapshot("Tester"));
	}

	@Test
	public void ignoresUnrelatedMessages()
	{
		tracker.seed(new int[]{1, 1, 1, 1, 1, 1, 1, 1}, 0);
		tracker.takeDirtySnapshot("Tester");
		assertFalse(tracker.onGameMessage("Your completed Tombs of Amascut count is: 60."));
		assertFalse(tracker.onGameMessage("Delve level reached"));
		assertNull(tracker.takeDirtySnapshot("Tester"));
	}

	@Test
	public void theScoreboardCorrectsAnyDrift()
	{
		tracker.seed(new int[]{5, 5, 5, 5, 5, 5, 5, 5}, 0);
		tracker.onGameMessage("Delve level: 3 duration: 1:00");
		tracker.seed(new int[]{5, 5, 5, 5, 5, 5, 5, 5}, 0);                      // the game's record says level 3 is still 5
		assertEquals(5, tracker.takeDirtySnapshot("Tester").getLevels().get(2).intValue());
	}
}
