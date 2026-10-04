package com.bingoluck;

import com.google.gson.Gson;
import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Sends the player's own data to the configured server. All calls are asynchronous
 * (OkHttp thread pool) and never run on the client thread.
 */
@Slf4j
@Singleton
class SyncClient
{
	private static final String BASE_URL = "https://osrsbingobot.onrender.com";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private final OkHttpClient okHttpClient;
	private final Gson gson;

	@Inject
	SyncClient(OkHttpClient okHttpClient, Gson gson)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
	}

	void postCollectionLogPage(String token, CollectionLogPageData page)
	{
		post("/plugin/collection-log", token, page);
	}

	void postRaid(String token, RaidCompletion raid)
	{
		post("/plugin/raid", token, raid);
	}

	void postKillCounts(String token, KillCountData data)
	{
		post("/plugin/kc", token, data);
	}

	private void post(String path, String token, Object body)
	{
		HttpUrl url = HttpUrl.parse(BASE_URL + path);
		if (url == null || token == null || token.isEmpty())
		{
			return;
		}

		String json = gson.toJson(body);
		log.debug("Sending to {}: {}", path, json);

		Request request = new Request.Builder()
			.url(url)
			.header("X-Plugin-Token", token)
			.post(RequestBody.create(JSON, json))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Sync to {} failed", path, e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						log.debug("Sync to {} returned HTTP {}", path, r.code());
					}
				}
			}
		});
	}
}
