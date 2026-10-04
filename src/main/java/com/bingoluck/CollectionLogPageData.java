package com.bingoluck;

import java.util.List;
import lombok.Value;

/**
 * One collection log page as shown in-game: obtained items with quantities and the
 * kill count lines the game displays for that page.
 */
@Value
class CollectionLogPageData
{
	String player;
	String page;
	List<Item> items;
	List<String> killCounts;

	@Value
	static class Item
	{
		int id;
		String name;
		int quantity;
		boolean obtained;
	}
}
