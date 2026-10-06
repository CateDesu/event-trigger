package gg.xp.reevent.events;

import gg.xp.compmonitor.CompMonitor;
import gg.xp.reevent.context.NoOpStateStore;
import gg.xp.reevent.scan.AutoHandlerConfig;
import gg.xp.reevent.scan.AutoScan;
import gg.xp.reevent.topology.BaseToggleableTopo;
import gg.xp.reevent.topology.TopologyInfo;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class MonitoringEventDistributorTest {

	private static final class Tick extends BaseEvent {}

	public static final class Component implements EventHandler<Event> {
		final AtomicInteger calls = new AtomicInteger();
		public Component() {}
		@Override public void handle(EventContext context, Event event) { calls.incrementAndGet(); }
	}

	private static final TopologyInfo TOPOLOGY = new TopologyInfo() {
		@Override public boolean isEnabled(BaseToggleableTopo item) { return true; }
		@Override public void setEnabled(BaseToggleableTopo item, boolean enabled) {}
	};

	private static AutoScan scanner() {
		return new AutoScan(null, new AutoHandlerConfig()) {
			@Override public void doScanIfNeeded() {}
		};
	}

	private static final class PausedDistributor extends MonitoringEventDistributor {
		final CountDownLatch snapshotTaken = new CountDownLatch(1);
		final CountDownLatch finishReload = new CountDownLatch(1);
		volatile boolean pause;

		PausedDistributor(CompMonitor monitor) {
			super(NoOpStateStore.INSTANCE, scanner(), TOPOLOGY, monitor, new AutoHandlerConfig());
		}

		@Override protected void sortHandlers() {
			if (pause) {
				snapshotTaken.countDown();
				await(finishReload);
			}
			super.sortHandlers();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			Assert.assertTrue(latch.await(5, TimeUnit.SECONDS), "Latch timed out");
		}
		catch (InterruptedException e) { throw new RuntimeException(e); }
	}

	private static void join(Thread thread) throws InterruptedException {
		thread.join(5_000);
		Assert.assertFalse(thread.isAlive(), "Worker did not finish");
	}

	private static void awaitBlockedOrFinished(Thread thread) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED) {
			Assert.assertTrue(System.nanoTime() < deadline, "Registration never reached the reload");
			Thread.yield();
		}
	}

	@DataProvider
	public Object[][] registrations() { return new Object[][]{{false}, {true}}; }

	@Test(dataProvider = "registrations")
	public void registrationDuringReloadSurvivesTheNextEvent(boolean automatic) throws Exception {
		CompMonitor monitor = new CompMonitor();
		PausedDistributor distributor = new PausedDistributor(monitor);
		AtomicInteger initialCalls = new AtomicInteger();
		distributor.registerHandler((context, event) -> initialCalls.incrementAndGet());
		distributor.acceptEvent(new Tick());
		distributor.registerHandler((context, event) -> {});
		distributor.pause = true;
		AtomicReference<Throwable> error = new AtomicReference<>();
		Thread reloading = new Thread(() -> {
			try { distributor.acceptEvent(new Tick()); }
			catch (Throwable e) { error.set(e); }
		}, "test-reload");
		reloading.start();
		await(distributor.snapshotTaken);
		Component late = new Component();
		CountDownLatch registrationStarted = new CountDownLatch(1);
		Thread registration = new Thread(() -> {
			registrationStarted.countDown();
			try {
				if (automatic) {
					monitor.instantiated(null, null, Component.class.getConstructor(), late, new Object[0], 0);
				}
				else {
					distributor.registerHandler(late);
				}
			}
			catch (Throwable e) { error.set(e); }
		}, "test-registration");
		try {
			registration.start();
			await(registrationStarted);
			awaitBlockedOrFinished(registration);
		}
		finally { distributor.finishReload.countDown(); }
		join(reloading);
		join(registration);
		Assert.assertNull(error.get());
		distributor.pause = false;
		distributor.acceptEvent(new Tick());
		Assert.assertEquals(late.calls.get(), 1, "A completed registration must receive the next event");
		Assert.assertEquals(initialCalls.get(), 3, "Reload must preserve existing handlers");
		distributor.acceptEvent(new Tick());
		Assert.assertEquals(late.calls.get(), 2, "Cached dispatch must retain the new handler exactly once");
	}

	@Test
	public void scanningMayRegisterFromAnotherThread() {
		AtomicReference<MonitoringEventDistributor> distributor = new AtomicReference<>();
		AtomicBoolean scanned = new AtomicBoolean();
		AtomicInteger calls = new AtomicInteger();
		CountDownLatch registered = new CountDownLatch(1);
		AutoScan scan = new AutoScan(null, new AutoHandlerConfig()) {
			@Override public void doScanIfNeeded() {
				if (scanned.compareAndSet(false, true)) {
					Thread registration = new Thread(() -> {
						distributor.get().registerHandler((context, event) -> calls.incrementAndGet());
						registered.countDown();
					}, "test-scanner-registration");
					registration.start();
					await(registered);
				}
			}
		};
		distributor.set(new MonitoringEventDistributor(NoOpStateStore.INSTANCE, scan, TOPOLOGY,
				new CompMonitor(), new AutoHandlerConfig()));
		distributor.get().acceptEvent(new Tick());
		Assert.assertEquals(calls.get(), 1);
	}
}
