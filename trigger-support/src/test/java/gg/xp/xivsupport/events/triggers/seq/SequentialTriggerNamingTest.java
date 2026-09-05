package gg.xp.xivsupport.events.triggers.seq;

import gg.xp.reevent.context.StateStore;
import gg.xp.reevent.events.BaseEvent;
import gg.xp.reevent.events.Event;
import gg.xp.reevent.events.EventContext;
import gg.xp.reevent.events.InitEvent;
import gg.xp.reevent.scan.AutoChildEventHandler;
import gg.xp.reevent.scan.AutoFeed;
import org.testng.Assert;
import org.testng.annotations.Test;

public class SequentialTriggerNamingTest {

	private static class MyPack extends AutoChildEventHandler {
		@AutoFeed
		private final SequentialTrigger<BaseEvent> myChain = new SequentialTrigger<>(1_000, BaseEvent.class, e -> false, (e, s) -> {
		});
	}

	@Test
	void autoFeedFieldNamesTheTrigger() {
		MyPack pack = new MyPack();
		pack.initAutoChildHandler(new DummyEventContext(), new InitEvent());
		Assert.assertEquals(pack.myChain.getHandlerName(), "MyPack.myChain");
	}

	@Test
	void explicitNameIsNotOverwritten() {
		MyPack pack = new MyPack();
		pack.myChain.setHandlerName("Custom.name");
		pack.initAutoChildHandler(new DummyEventContext(), new InitEvent());
		Assert.assertEquals(pack.myChain.getHandlerName(), "Custom.name");
	}

	private static final class DummyEventContext implements EventContext {

		@Override
		public void accept(Event event) {

		}

		@Override
		public void enqueue(Event event) {

		}

		@Override
		public StateStore getStateInfo() {
			return null;
		}
	}
}
