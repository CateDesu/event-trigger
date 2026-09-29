package gg.xp.xivsupport.events.triggers.util;

import gg.xp.reevent.events.EventDistributor;
import gg.xp.xivsupport.events.ACTLogLineEvent;
import gg.xp.xivsupport.events.actlines.events.BuffApplied;
import gg.xp.xivsupport.events.actlines.events.EntityKilledEvent;
import gg.xp.xivsupport.events.actlines.parsers.FakeACTTimeSource;
import gg.xp.xivsupport.events.triggers.seq.SequentialTriggerFailedEvent;
import gg.xp.xivsupport.gui.imprt.EventIterator;
import gg.xp.xivsupport.triggers.Arcadion.M1sTest;
import gg.xp.xivsupport.triggers.Arcadion.M2sTest;
import gg.xp.xivsupport.triggers.ultimate.FRUTest;
import org.picocontainer.MutablePicoContainer;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public class CrossFightFaultInjectionTest {
	private record Scenario(String name, CalloutVerificationTest golden, Predicate<ACTLogLineEvent> select,
	                        Duration delay, List<Long> dependentCalls, boolean playerRecovers,
	                        Predicate<ACTLogLineEvent> alsoDrop, List<String> expectedFailures, boolean preserveEventTime) {
		Scenario(String name, CalloutVerificationTest golden, Predicate<ACTLogLineEvent> select,
		         Duration delay, List<Long> dependentCalls, boolean playerRecovers,
		         Predicate<ACTLogLineEvent> alsoDrop, List<String> expectedFailures) {
			this(name, golden, select, delay, dependentCalls, playerRecovers, alsoDrop, expectedFailures, false);
		}

		Scenario(String name, CalloutVerificationTest golden, Predicate<ACTLogLineEvent> select,
		         Duration delay, List<Long> dependentCalls, boolean playerRecovers) {
			this(name, golden, select, delay, dependentCalls, playerRecovers, null, List.of());
		}

		@Override
		public String toString() {
			return name;
		}
	}

	private static boolean field(ACTLogLineEvent event, int type, int index, String value) {
		return event.getLineNumber() == type && event.getRawFields()[index].equals(value);
	}

	private static boolean at(ACTLogLineEvent event, String timestamp) {
		return event.getRawFields()[1].equals(timestamp);
	}

	@DataProvider
	public Object[][] faults() {
		Predicate<ACTLogLineEvent> mouserMarker = e -> field(e, 27, 6, "021A")
				&& at(e, "2024-07-30T18:15:12.6370000-05:00");
		Predicate<ACTLogLineEvent> defamation = e -> field(e, 26, 2, "F5E") && e.getRawFields()[7].equals("10FF0001");
		Predicate<ACTLogLineEvent> powderMark = e -> field(e, 26, 2, "1046")
				&& at(e, "2024-11-29T23:05:11.5750000+00:00");
		Predicate<ACTLogLineEvent> relativityTether = e -> field(e, 35, 8, "0086")
				&& e.getRawFields()[2].equals("40010653");
		Predicate<ACTLogLineEvent> secondMouserMarker = e -> field(e, 27, 6, "021A")
				&& at(e, "2024-07-30T18:15:23.8170000-05:00");
		Predicate<ACTLogLineEvent> dmuTether = e -> field(e, 35, 8, "002D") && e.getRawFields()[4].equals("10031A09");
		return new Object[][]{
				{new Scenario("UMAD missing local tether", new DmuArrows(), dmuTether, null,
						List.of(19_101L), false)},
				{new Scenario("UMAD tether arriving after its burst", new DmuArrows(), dmuTether, Duration.ofMillis(500),
						List.of(19_101L), false)},
				{new Scenario("M1S missing first Mouser marker", new M1sTest(), mouserMarker, null,
						List.of(170_497L), true)},
				{new Scenario("M1S marker arriving after its cast", new M1sTest(), mouserMarker, Duration.ofMillis(250),
						List.of(170_497L), true)},
				{new Scenario("M1S stale marker with next marker missing", new M1sTest(), mouserMarker, Duration.ofMillis(250),
						List.of(170_497L, 181_677L), true, secondMouserMarker, List.of())},
				{new Scenario("M1S delayed delivery with original marker timestamp", new M1sTest(), mouserMarker, Duration.ofMillis(250),
						List.of(170_497L), true, null, List.of(), true)},
				{new Scenario("M2S missing local defamation", new M2sTest(), defamation, null,
						List.of(413_029L, 434_039L, 439_024L, 454_117L, 459_012L), false, null, List.of("M2S.hbl3"))},
				{new Scenario("M2S defamation arriving after its burst", new M2sTest(), defamation, Duration.ofMillis(500),
						List.of(413_029L, 434_039L, 439_024L, 454_117L, 459_012L), false, null, List.of("M2S.hbl3"))},
				{new Scenario("FRU missing first powder mark", new FRUTest(), powderMark, null,
						List.of(23_906L, 37_314L), false, null, List.of("FRU.powderMarkTrail"))},
				{new Scenario("FRU missing relativity tether", new FRUTest(), relativityTether, null,
						List.of(460_200L), false)},
				{new Scenario("FRU delayed relativity tether", new FRUTest(), relativityTether, Duration.ofMillis(500),
						List.of(460_200L), false)}
		};
	}

	@Test(dataProvider = "faults")
	public void laterCalloutsKeepTheirRecordedTextCountAndTime(Scenario scenario) {
		verifyScenario(scenario, UnaryOperator.identity());
	}

	@Test
	public void fruDuplicateTetherDoesNotReplaceMissingAnchor() {
		var scenario = new Scenario("FRU duplicate tether with third anchor missing", new FRUTest(),
				e -> field(e, 35, 8, "0086") && e.getRawFields()[2].equals("40010653"),
				null, List.of(460_200L), false);
		verifyScenario(scenario, events -> ReplayFaults.duplicate(events,
				e -> field(e, 35, 8, "0086") && e.getRawFields()[2].equals("40010650"), 1));
	}

	private void verifyScenario(Scenario scenario, UnaryOperator<EventIterator<ACTLogLineEvent>> additionalFault) {
		new CalloutVerificationTest() {
			private final List<Instant> delayedTimes = new ArrayList<>();
			private final List<EntityKilledEvent> deaths = new ArrayList<>();
			private final List<BuffApplied> recoveryBuffs = new ArrayList<>();
			private final List<SequentialTriggerFailedEvent> failures = new ArrayList<>();

			@Override
			protected String getFileName() {
				return scenario.golden.getFileName();
			}

			@Override
			protected EventIterator<ACTLogLineEvent> getEvents() {
				var events = scenario.preserveEventTime
						? ReplayFaults.delayDelivery(getFileName(), event -> {
							if (scenario.select.test(event)) {
								delayedTimes.add(event.getTimestamp().toInstant());
								return true;
							}
							return false;
						}, 1, scenario.delay)
						: ReplayFaults.mutate(getFileName(), scenario.select, 1, scenario.delay);
				return additionalFault.apply(scenario.alsoDrop == null ? events : ReplayFaults.mutate(events, scenario.alsoDrop, 1, null));
			}

			@Override
			protected FakeACTTimeSource createTimeSource() {
				return scenario.preserveEventTime ? new FakeACTTimeSource() {
					@Override
					public void setNewTime(Instant time) {
						// Clamp injected delayed records without changing unrelated replay timing.
						if (!delayedTimes.contains(time) || !time.isBefore(now())) {
							super.setNewTime(time);
						}
					}
				} : super.createTimeSource();
			}

			@Override
			protected List<CalloutInitialValues> getExpectedCalls() {
				return scenario.golden.getExpectedCalls().stream()
						.filter(call -> !scenario.dependentCalls.contains(call.ms())).toList();
			}

			@Override
			protected Instant getCalloutTimeOrigin(MutablePicoContainer pico) {
				return scenario.golden.getCalloutTimeOrigin(pico);
			}

			@Override
			protected long minimumMsBetweenCalls() {
				return 0;
			}

			@Override
			protected void configure(MutablePicoContainer pico) {
				var dist = pico.getComponent(EventDistributor.class);
				dist.registerHandler(EntityKilledEvent.class, (ctx, e) -> {
					if (e.getTarget().isThePlayer()) {
						deaths.add(e);
					}
				});
				dist.registerHandler(BuffApplied.class, (ctx, e) -> {
					if (e.getTarget().isThePlayer() && e.buffIdMatches(0x94, 0x2B, 0x2C)) {
						recoveryBuffs.add(e);
					}
				});
				dist.registerHandler(SequentialTriggerFailedEvent.class, (ctx, e) -> failures.add(e));
			}

			@Override
			protected void verifyReplay(MutablePicoContainer pico, List<CalloutInitialValues> calls) {
				System.out.println("FAULT_REPLAY " + scenario.name + " checked=" + calls.size()
						+ " failures=" + failures);
				Assert.assertEquals(failures.stream().map(SequentialTriggerFailedEvent::getTriggerName).toList(),
						scenario.expectedFailures, "Only the deliberately starved chain may fail");
				if (scenario.playerRecovers) {
					Assert.assertEquals(deaths.size(), 2, "The recording must exercise both local deaths");
					Assert.assertTrue(recoveryBuffs.stream().anyMatch(e -> e.buffIdMatches(0x94)), "Recorded raise offer");
					Assert.assertTrue(recoveryBuffs.stream().anyMatch(e -> e.buffIdMatches(0x2B, 0x2C)
							&& e.getHappenedAt().isAfter(deaths.get(0).getHappenedAt())), "Recorded return with weakness");
				}
			}
		}.doTheTest();
	}

	@Test
	public void dmuArrowsControl() {
		new DmuArrows().doTheTest();
	}

	private static class DmuArrows extends CalloutVerificationTest {
		@Override
		protected String getFileName() {
			return "/dmu-arrows.log";
		}

		@Override
		protected List<CalloutInitialValues> getExpectedCalls() {
			return List.of(
					call(1024, "Arrows", "Arrows (4.7)"),
					call(6776, "Double East", "Double East (7.0)"),
					call(19101, "Confusion Tether"),
					call(19101, "Spread for Confusion"),
					call(31320, "Fake Gaze"),
					call(35861, "Stack In Thunder, Look Towards"));
		}

		@Override
		protected Instant getCalloutTimeOrigin(MutablePicoContainer pico) {
			return Instant.parse("2026-09-16T02:06:25Z");
		}

		@Override
		protected long minimumMsBetweenCalls() {
			return 0;
		}
	}
}
