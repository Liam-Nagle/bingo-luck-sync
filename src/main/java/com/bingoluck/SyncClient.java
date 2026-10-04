package com.bingoluck;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
 *
 * A request that fails because the server is busy, asleep or unreachable (429, 5xx or a network
 * error) is retried a few times with a growing delay, so a page opened in the collection log or a
 * raid that just finished isn't silently lost. The server treats every upload as an upsert, so a
 * retry can never double count. Requests the server refuses outright (a bad token, a bad payload)
 * are not retried.
 */
@Slf4j
@Singleton
class SyncClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	// Not final so tests can point the client at a local server and shorten the waits.
	String baseUrl = "https://osrsbingobot.onrender.com";
	long[] retryDelaysSeconds = {5, 30, 120};

	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final Set<ScheduledFuture<?>> pendingRetries = ConcurrentHashMap.newKeySet();
	private volatile boolean stopped;

	@Inject
	SyncClient(OkHttpClient okHttpClient, Gson gson, ScheduledExecutorService executor)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
		this.executor = executor;
	}

	void start()
	{
		stopped = false;
	}

	/** Cancels retries that are still waiting. Called when the plugin shuts down. */
	void stop()
	{
		stopped = true;
		for (ScheduledFuture<?> future : pendingRetries)
		{
			future.cancel(false);
		}
		pendingRetries.clear();
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

	void postDoomDelves(String token, DoomDelveData data)
	{
		post("/plugin/doom", token, data);
	}

	private void post(String path, String token, Object body)
	{
		HttpUrl url = HttpUrl.parse(baseUrl + path);
		if (url == null || token == null || token.isEmpty())
		{
			return;
		}

		String json = gson.toJson(body);
		log.debug("Sending to {}: {}", path, json);
		send(url, path, token, json, 0);
	}

	private void send(HttpUrl url, String path, String token, String json, int attempt)
	{
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
				retry(url, path, token, json, attempt);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.isSuccessful())
					{
						return;
					}
					log.debug("Sync to {} returned HTTP {}", path, r.code());
					if (r.code() == 429 || r.code() >= 500)
					{
						retry(url, path, token, json, attempt);
					}
				}
			}
		});
	}

	private void retry(HttpUrl url, String path, String token, String json, int attempt)
	{
		if (stopped || attempt >= retryDelaysSeconds.length)
		{
			return;
		}

		long delay = retryDelaysSeconds[attempt];
		log.debug("Will retry {} in {}s (attempt {} of {})", path, delay, attempt + 2, retryDelaysSeconds.length + 1);
		pendingRetries.removeIf(ScheduledFuture::isDone);
		pendingRetries.add(executor.schedule(() ->
		{
			if (!stopped)
			{
				send(url, path, token, json, attempt + 1);
			}
		}, delay, TimeUnit.SECONDS));
	}
}
