package com.bingoluck;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class UpdateNoticeTest
{
	@Test
	public void brandNewInstallIsLeftAlone()
	{
		assertFalse(UpdateNotice.shouldAnnounce("", "1.1", false));
		assertFalse(UpdateNotice.shouldAnnounce(null, "1.1", false));
	}

	@Test
	public void updatingFromTheFirstReleaseIsAnnounced()
	{
		assertTrue(UpdateNotice.shouldAnnounce("", "1.1", true));
	}

	@Test
	public void anOlderStoredVersionIsAnnounced()
	{
		assertTrue(UpdateNotice.shouldAnnounce("1.0", "1.1", false));
	}

	@Test
	public void theSameVersionIsNotAnnouncedAgain()
	{
		assertFalse(UpdateNotice.shouldAnnounce("1.1", "1.1", true));
	}
}
