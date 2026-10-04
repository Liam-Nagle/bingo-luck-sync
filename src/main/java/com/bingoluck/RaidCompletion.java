package com.bingoluck;

import lombok.Builder;
import lombok.Value;

/**
 * A finished raid as seen by the local player. Fields that the game does not expose
 * for a given raid are left null/-1 and the server decides how to handle them.
 */
@Value
@Builder(toBuilder = true)
class RaidCompletion
{
	/** COX, TOB or TOA. */
	String raid;
	/** NORMAL, CHALLENGE, STORY, HARD, ENTRY or EXPERT. */
	String mode;
	String player;
	/** Completion count for this raid and mode, or -1 if it could not be read. */
	int killCount;
	/** Epoch seconds when the completion was recorded locally. */
	long completedAt;
	/** Team total points (CoX only; the game exposes it), otherwise -1. */
	int totalPoints;
	/** Personal points. For ToA this excludes the 5,000 starting points; -1 if unknown. */
	int personalPoints;
	/** ToA raid level, otherwise -1. */
	int raidLevel;
	/** Number of players, or -1 if unknown. CoX is sent as the game displays it (e.g. "24+"). */
	int teamSize;
	String teamSizeLabel;
	/** ToB only: your own death count from the end-of-raid performance board, otherwise -1. */
	int deaths;
	/** ToB only: total deaths across the whole team (a number, no names), otherwise -1. */
	int teamDeaths;
	/** ToB only: 1 if you were the raid MVP, 0 if not, -1 if unknown. */
	int mvp;
}
