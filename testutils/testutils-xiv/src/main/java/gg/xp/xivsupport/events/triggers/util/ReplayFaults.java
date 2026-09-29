package gg.xp.xivsupport.events.triggers.util;

import gg.xp.xivsupport.events.ACTLogLineEvent;
import gg.xp.xivsupport.eventstorage.EventReader;
import gg.xp.xivsupport.gui.imprt.EventIterator;
import gg.xp.xivsupport.gui.imprt.ListEventIterator;
import org.testng.Assert;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

public final class ReplayFaults {
	private ReplayFaults() {
	}

	/** Remove selected records when delay is null, or shift both their time and delivery. */
	public static EventIterator<ACTLogLineEvent> mutate(String resource, Predicate<ACTLogLineEvent> select,
	                                                 int expectedCount, Duration delay) {
		return mutate(EventReader.readActLogResource(resource), select, expectedCount, delay);
	}

	public static EventIterator<ACTLogLineEvent> mutate(EventIterator<ACTLogLineEvent> original,
	                                                 Predicate<ACTLogLineEvent> select, int expectedCount, Duration delay) {
		return mutate(original, select, expectedCount, delay, false);
	}

	/** Preserve event times while delaying delivery. The replay clock must clamp these older times. */
	public static EventIterator<ACTLogLineEvent> delayDelivery(String resource, Predicate<ACTLogLineEvent> select,
	                                                        int expectedCount, Duration delay) {
		return mutate(EventReader.readActLogResource(resource), select, expectedCount, delay, true);
	}

	private record Delayed(ACTLogLineEvent event, ZonedDateTime arrival) {
	}

	public static EventIterator<ACTLogLineEvent> duplicate(EventIterator<ACTLogLineEvent> original,
	                                                    Predicate<ACTLogLineEvent> select, int expectedCount) {
		List<ACTLogLineEvent> result = new ArrayList<>();
		int selected = 0;
		while (original.hasMore()) {
			var event = original.getNext();
			result.add(event);
			if (select.test(event)) {
				selected++;
				result.add(event);
			}
		}
		Assert.assertEquals(selected, expectedCount, "Duplicate only the intended recorded signals");
		return renumber(result);
	}

	private static EventIterator<ACTLogLineEvent> mutate(EventIterator<ACTLogLineEvent> original,
	                                                  Predicate<ACTLogLineEvent> select, int expectedCount,
	                                                  Duration delay, boolean preserveEventTime) {
		List<ACTLogLineEvent> retained = new ArrayList<>();
		List<Delayed> delayed = new ArrayList<>();
		int selected = 0;
		while (original.hasMore()) {
			var event = original.getNext();
			if (!select.test(event)) {
				retained.add(event);
				continue;
			}
			selected++;
			if (delay != null) {
				Assert.assertTrue(!delay.isNegative() && !delay.isZero(), "A delayed signal must arrive later");
				var fields = event.getRawFields().clone();
				var arrival = event.getTimestamp().plus(delay);
				if (!preserveEventTime) {
					fields[1] = arrival.toString();
				}
				delayed.add(new Delayed(new ACTLogLineEvent(String.join("|", fields)), arrival));
			}
		}
		Assert.assertEquals(selected, expectedCount, "Fault must affect the intended recorded signals");
		delayed.sort(Comparator.comparing(Delayed::arrival));
		List<ACTLogLineEvent> result = new ArrayList<>();
		int next = 0;
		for (var event : retained) {
			// Delivery-only delays use the next recorded event to advance the monotonic clock.
			if (preserveEventTime) {
				result.add(event);
			}
			while (next < delayed.size() && !delayed.get(next).arrival.isAfter(event.getTimestamp())) {
				result.add(delayed.get(next++).event);
			}
			if (!preserveEventTime) {
				result.add(event);
			}
		}
		if (preserveEventTime) {
			Assert.assertEquals(next, delayed.size(), "Recording must extend past delayed delivery");
		}
		result.addAll(delayed.subList(next, delayed.size()).stream().map(Delayed::event).toList());
		return renumber(result);
	}

	private static EventIterator<ACTLogLineEvent> renumber(List<ACTLogLineEvent> result) {
		List<ACTLogLineEvent> numbered = new ArrayList<>();
		for (var event : result) {
			numbered.add(new ACTLogLineEvent(event.getLogLine(), numbered.size()));
		}
		return new ListEventIterator<>(numbered);
	}
}
