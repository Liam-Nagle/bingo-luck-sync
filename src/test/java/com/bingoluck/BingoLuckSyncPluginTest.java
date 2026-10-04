package com.bingoluck;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class BingoLuckSyncPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(BingoLuckSyncPlugin.class);
		RuneLite.main(args);
	}
}
