package gg.xp.xivsupport.events.triggers.seq;

import gg.xp.reevent.context.StateStore;
import gg.xp.reevent.events.BaseEvent;
import gg.xp.reevent.events.Event;
import gg.xp.reevent.events.EventContext;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class SequentialTriggerLifecycleTest {

	private static class Start extends BaseEvent {}
	private static class Finish extends BaseEvent {}

	private static class Context implements EventContext {
		private final List<Event> events = new ArrayList<>();

		@Override
		public void accept(Event event) {
			events.add(event);
		}

		@Override
		public void enqueue(Event event) {
			events.add(event);
		}

		@Override
		public StateStore getStateInfo() {
			return null;
		}
	}

	@Test
	public void immediateCompletionAllowsConsecutiveStarts() {
		Context context = new Context();
		AtomicInteger starts = new AtomicInteger();
		SequentialTrigger<BaseEvent> trigger = new SequentialTrigger<>(5_000, BaseEvent.class,
				e -> e instanceof Start, (e, s) -> starts.incrementAndGet());

		trigger.feed(context, new Start());
		Assert.assertFalse(trigger.isActive());
		trigger.feed(context, new Start());
		trigger.feed(context, new Start());
		Assert.assertEquals(starts.get(), 3);
		Assert.assertFalse(trigger.isActive());
	}

	@Test
	public void initialFailureAllowsTheNextStart() {
		Context context = new Context();
		AtomicInteger starts = new AtomicInteger();
		SequentialTrigger<BaseEvent> trigger = new SequentialTrigger<>(5_000, BaseEvent.class,
				e -> e instanceof Start, (e, s) -> {
					starts.incrementAndGet();
					throw new IllegalStateException("Test failure");
				});

		trigger.feed(context, new Start());
		Assert.assertFalse(trigger.isActive());
		trigger.feed(context, new Start());
		Assert.assertEquals(starts.get(), 2);
		Assert.assertEquals(context.events.stream()
				.filter(SequentialTriggerFailedEvent.class::isInstance).count(), 2L);
		Assert.assertFalse(trigger.isActive());
	}

	@Test
	public void normalCompletionAllowsTheNextStart() {
		Context context = new Context();
		AtomicInteger starts = new AtomicInteger();
		SequentialTrigger<BaseEvent> trigger = new SequentialTrigger<>(5_000, BaseEvent.class,
				e -> e instanceof Start, (e, s) -> {
					starts.incrementAndGet();
					s.waitEvent(Finish.class);
				});

		trigger.feed(context, new Start());
		Assert.assertTrue(trigger.isActive());
		trigger.feed(context, new Finish());
		Assert.assertFalse(trigger.isActive());
		trigger.feed(context, new Start());
		trigger.feed(context, new Finish());
		Assert.assertEquals(starts.get(), 2);
		Assert.assertFalse(trigger.isActive());
	}

	@Test
	public void activeSequenceStillBlocksNewStarts() {
		Context context = new Context();
		AtomicInteger starts = new AtomicInteger();
		SequentialTrigger<BaseEvent> trigger = new SequentialTrigger<>(5_000, BaseEvent.class,
				e -> e instanceof Start, (e, s) -> {
					starts.incrementAndGet();
					s.waitEvent(Finish.class);
				});

		trigger.feed(context, new Start());
		trigger.feed(context, new Start());
		Assert.assertEquals(starts.get(), 1);
		Assert.assertTrue(trigger.isActive());
		trigger.feed(context, new Finish());
		Assert.assertFalse(trigger.isActive());
	}

	@Test
	public void completingEventDoesNotAlsoStartANewSequence() {
		Context context = new Context();
		AtomicInteger starts = new AtomicInteger();
		SequentialTrigger<BaseEvent> trigger = new SequentialTrigger<>(5_000, BaseEvent.class,
				e -> e instanceof Start, (e, s) -> {
					starts.incrementAndGet();
					s.waitEvent(Start.class);
				});

		trigger.feed(context, new Start());
		trigger.feed(context, new Start());
		Assert.assertEquals(starts.get(), 1);
		Assert.assertFalse(trigger.isActive());
		trigger.feed(context, new Start());
		trigger.feed(context, new Start());
		Assert.assertEquals(starts.get(), 2);
		Assert.assertFalse(trigger.isActive());
	}

	@Test
	public void otherConcurrencyModesStillStartEveryMatchingEvent() {
		for (SequentialTriggerConcurrencyMode mode : List.of(
				SequentialTriggerConcurrencyMode.REPLACE_OLD,
				SequentialTriggerConcurrencyMode.CONCURRENT)) {
			Context context = new Context();
			AtomicInteger starts = new AtomicInteger();
			SequentialTrigger<BaseEvent> trigger = new SequentialTrigger<BaseEvent>(5_000, BaseEvent.class,
					e -> e instanceof Start, (e, s) -> {
						starts.incrementAndGet();
						s.waitEvent(Finish.class);
					}).setConcurrency(mode);

			trigger.feed(context, new Start());
			trigger.feed(context, new Start());
			Assert.assertEquals(starts.get(), 2, mode.name());
			Assert.assertTrue(trigger.isActive(), mode.name());
			trigger.feed(context, new Finish());
			Assert.assertFalse(trigger.isActive(), mode.name());
		}
	}
}
