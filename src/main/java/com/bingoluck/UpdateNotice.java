package com.bingoluck;

/**
 * Decides whether to tell the player, once, that the plugin has been updated. Works only from the
 * version stored in the player's own settings, so it needs no network call.
 */
final class UpdateNotice
{
	/** Bump this and {@link #SUMMARY} with each release, and add the matching entry to CHANGELOG.md. */
	static final String VERSION = "1.1";
	static final String SUMMARY = "Theatre of Blood deaths are now counted during the raid, and you'll be told in chat if syncing stops working.";
	static final String CHANGELOG_HINT = "Full list: github.com/Liam-Nagle/bingo-luck-sync (CHANGELOG.md)";

	private UpdateNotice()
	{
	}

	/**
	 * @param lastSeen     the version stored the last time this was checked, empty if never
	 * @param setUpAlready whether sync was already switched on or a token entered before this check -
	 *                     what tells someone updating from the first release from a brand-new install
	 */
	static boolean shouldAnnounce(String lastSeen, String current, boolean setUpAlready)
	{
		if (current.equals(lastSeen))
		{
			return false;
		}
		// Nothing stored: either a new install (say nothing) or an update from the first release (say so).
		return lastSeen != null && !lastSeen.isEmpty() || setUpAlready;
	}

	static String message()
	{
		return "updated to v" + VERSION + ". " + SUMMARY + " " + CHANGELOG_HINT;
	}
}
