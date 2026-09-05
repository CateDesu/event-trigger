package gg.xp.xivsupport.events.triggers.seq;

import gg.xp.reevent.events.BaseEvent;
import gg.xp.reevent.events.EventDistributor;
import gg.xp.reevent.events.TestEventCollector;
import gg.xp.xivsupport.events.actlines.events.AbilityUsedEvent;
import gg.xp.xivsupport.events.actlines.events.WipeEvent;
import gg.xp.xivsupport.events.misc.EchoEvent;
import gg.xp.xivsupport.sys.XivMain;
import org.picocontainer.MutablePicoContainer;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

public class SequentialTriggerFailureGuardTest {

	private static SequentialTrigger<BaseEvent> parkingTrigger() {
		SequentialTrigger<BaseEvent> trigger = new SequentialTrigger<>(3_000, BaseEvent.class,
				e -> e instanceof EchoEvent ee && ee.getLine().startsWith("Foo"),
				(e1, s) -> s.waitEvent(AbilityUsedEvent.class, e -> false));
		trigger.setHandlerName("Test.parkingChain");
		return trigger;
	}

	private static EventDistributor freshDist() {
		MutablePicoContainer pico = XivMain.testingMinimalInit();
		return pico.getComponent(EventDistributor.class);
	}

	private static void awaitFailureCount(TestEventCollector coll, int count, long timeoutMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (coll.getEventsOf(SequentialTriggerFailedEvent.class).size() >= count) {
				return;
			}
			Thread.sleep(50);
		}
	}

	@Test
	void timeoutEmitsFailureEvent() throws InterruptedException {
		SequentialTrigger<BaseEvent> trigger = parkingTrigger();
		EventDistributor dist = freshDist();
		dist.registerHandler(BaseEvent.class, trigger::feed);
		TestEventCollector coll = new TestEventCollector();
		dist.registerHandler(coll);

		dist.acceptEvent(new EchoEvent("Foo1"));
		Thread.sleep(4_000);
		// Expiry is lazy, it trips when the next event arrives
		dist.acceptEvent(new EchoEvent("tick"));
		awaitFailureCount(coll, 1, 5_000);

		List<SequentialTriggerFailedEvent> failures = coll.getEventsOf(SequentialTriggerFailedEvent.class);
		Assert.assertEquals(failures.size(), 1);
		SequentialTriggerFailedEvent failure = failures.get(0);
		Assert.assertEquals(failure.getTriggerName(), "Test.parkingChain");
		Assert.assertEquals(failure.getPendingWait(), "AbilityUsedEvent");
		Assert.assertTrue(failure.getError() instanceof SequentialTriggerTimeoutException);
	}

	@Test
	void timeoutAfterWipeStaysQuiet() throws InterruptedException {
		SequentialTrigger<BaseEvent> trigger = parkingTrigger();
		EventDistributor dist = freshDist();
		dist.registerHandler(BaseEvent.class, trigger::feed);
		TestEventCollector coll = new TestEventCollector();
		dist.registerHandler(coll);

		dist.acceptEvent(new EchoEvent("Foo1"));
		dist.acceptEvent(new WipeEvent());
		Thread.sleep(4_000);
		dist.acceptEvent(new EchoEvent("tick"));
		awaitFailureCount(coll, 1, 2_000);

		Assert.assertEquals(coll.getEventsOf(SequentialTriggerFailedEvent.class).size(), 0);
	}

	@Test
	void forceExpireStaysQuiet() throws InterruptedException {
		SequentialTrigger<BaseEvent> trigger = parkingTrigger();
		EventDistributor dist = freshDist();
		dist.registerHandler(BaseEvent.class, trigger::feed);
		TestEventCollector coll = new TestEventCollector();
		dist.registerHandler(coll);

		dist.acceptEvent(new EchoEvent("Foo1"));
		trigger.forceExpire();
		awaitFailureCount(coll, 1, 2_000);

		Assert.assertEquals(coll.getEventsOf(SequentialTriggerFailedEvent.class).size(), 0);
	}
}
