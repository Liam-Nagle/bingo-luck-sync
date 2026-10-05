package com.bingoluck;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Checks the retry behaviour against a throwaway local server (no network, no real data). */
public class SyncClientTest
{
	private HttpServer server;
	private ScheduledExecutorService executor;
	private SyncClient client;
	private final AtomicInteger requests = new AtomicInteger();
	private volatile int[] script = {200};
	private CountDownLatch done;

	@Before
	public void setUp() throws Exception
	{
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/plugin/kc", exchange ->
		{
			int n = requests.getAndIncrement();
			int code = script[Math.min(n, script.length - 1)];
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(code, -1);
			exchange.close();
			if (code == 200 || code == 401)
			{
				done.countDown();
			}
		});
		server.start();

		executor = Executors.newSingleThreadScheduledExecutor();
		client = new SyncClient(new OkHttpClient(), new Gson(), executor);
		client.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
		client.retryDelaysSeconds = new long[]{0, 0, 0};
		client.start();
	}

	@After
	public void tearDown()
	{
		client.stop();
		executor.shutdownNow();
		server.stop(0);
	}

	private void send()
	{
		client.postKillCounts("token", new KillCountData("Tester", Collections.singletonList(new KillCountData.Count("Tormented Demon", 5))));
	}

	@Test
	public void retriesWhenTheServerIsBusyThenSucceeds() throws Exception
	{
		script = new int[]{503, 429, 200};
		done = new CountDownLatch(1);
		send();
		assertTrue("never succeeded", done.await(10, TimeUnit.SECONDS));
		assertEquals(3, requests.get());
	}

	@Test
	public void doesNotRetryARefusedToken() throws Exception
	{
		script = new int[]{401};
		done = new CountDownLatch(1);
		send();
		assertTrue(done.await(10, TimeUnit.SECONDS));
		Thread.sleep(500);
		assertEquals(1, requests.get());
	}

	@Test
	public void givesUpAfterTheLastRetry() throws Exception
	{
		script = new int[]{503};
		done = new CountDownLatch(1);
		send();
		Thread.sleep(1500);
		assertEquals(4, requests.get());          // the first attempt plus three retries
	}

	@Test
	public void stopCancelsPendingRetries() throws Exception
	{
		client.retryDelaysSeconds = new long[]{2, 2, 2};
		script = new int[]{503};
		done = new CountDownLatch(1);
		send();
		Thread.sleep(300);
		client.stop();
		Thread.sleep(2500);
		assertEquals(1, requests.get());
	}

	/** Counts what the client reports to its listener. */
	private static final class CountingListener implements SyncClient.Listener
	{
		final AtomicInteger rejected = new AtomicInteger();
		final AtomicInteger gaveUp = new AtomicInteger();

		@Override
		public void onTokenRejected()
		{
			rejected.incrementAndGet();
		}

		@Override
		public void onGaveUp(String path)
		{
			gaveUp.incrementAndGet();
		}
	}

	@Test
	public void reportsARefusedTokenButIsNotTreatedAsAFailure() throws Exception
	{
		CountingListener listener = new CountingListener();
		client.setListener(listener);
		script = new int[]{401};
		done = new CountDownLatch(1);
		send();
		assertTrue(done.await(10, TimeUnit.SECONDS));
		Thread.sleep(500);
		assertEquals(1, listener.rejected.get());
		assertEquals("a refused token is not 'couldn't reach the server'", 0, listener.gaveUp.get());
	}

	@Test
	public void reportsGivingUpOnlyAfterEveryRetryFailed() throws Exception
	{
		CountingListener listener = new CountingListener();
		client.setListener(listener);
		script = new int[]{503};
		done = new CountDownLatch(1);
		send();
		Thread.sleep(1500);
		assertEquals(4, requests.get());
		assertEquals(1, listener.gaveUp.get());
		assertEquals(0, listener.rejected.get());
	}

	@Test
	public void staysQuietWhenARetrySucceeds() throws Exception
	{
		CountingListener listener = new CountingListener();
		client.setListener(listener);
		script = new int[]{503, 429, 200};
		done = new CountDownLatch(1);
		send();
		assertTrue(done.await(10, TimeUnit.SECONDS));
		Thread.sleep(300);
		assertEquals(0, listener.gaveUp.get());
		assertEquals(0, listener.rejected.get());
	}

	@Test
	public void staysQuietAfterStop() throws Exception
	{
		CountingListener listener = new CountingListener();
		client.setListener(listener);
		client.retryDelaysSeconds = new long[]{1, 1, 1};
		script = new int[]{503};
		done = new CountDownLatch(1);
		send();
		Thread.sleep(200);
		client.stop();
		Thread.sleep(1500);
		assertEquals("a plugin that has shut down must not talk", 0, listener.gaveUp.get());
	}
}
