package com.bingoluck;

import java.util.List;
import lombok.Value;

/** How many times the player has completed each Doom of Mokhaiotl delve level. */
@Value
class DoomDelveData
{
	String player;
	/** Completions of delve levels 1 to 8, in order. */
	List<Integer> levels;
	/** Completions of every level beyond 8 (the scoreboard's "8+" row). */
	int past8;
}
