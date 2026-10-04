package com.bingoluck;

import java.util.List;
import lombok.Value;

/** The player's own kill/completion counters, as last known by the plugin. */
@Value
class KillCountData
{
	String player;
	List<Count> counts;

	@Value
	static class Count
	{
		/** Source name without the "kills"/"completions" suffix, e.g. "Tormented Demon". */
		String name;
		int kc;
	}
}
