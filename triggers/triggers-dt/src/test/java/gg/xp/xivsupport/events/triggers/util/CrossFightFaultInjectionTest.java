package gg.xp.xivsupport.events.triggers.util;

import gg.xp.reevent.events.EventDistributor;
import gg.xp.xivsupport.events.ACTLogLineEvent;
import gg.xp.xivsupport.events.actlines.events.BuffApplied;
import gg.xp.xivsupport.events.actlines.events.EntityKilledEvent;
import gg.xp.xivsupport.events.actlines.parsers.FakeACTTimeSource;
import gg.xp.xivsupport.events.triggers.seq.SequentialTriggerFailedEvent;
import gg.xp.xivsupport.gui.imprt.EventIterator;
import gg.xp.xivsupport.gui.imprt.ListEventIterator;
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
import java.util.Comparator;
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
				var events = scenario.select == null ? scenario.golden.getEvents() : scenario.preserveEventTime
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

	@Test
	public void dmuMissingArrowPreservesGazeAndElements() {
		var scenario = new Scenario("UMAD missing second local arrow", new DmuArrows(),
				e -> field(e, 26, 2, "130E") && e.getRawFields()[7].equals("10031A09")
						&& e.getRawFields()[5].equals("400055B7"),
				null, List.of(6_776L), false);
		verifyScenario(scenario, events -> ReplayFaults.mutate(events,
				e -> e.getLineNumber() == 38 && e.getRawFields()[2].equals("10031A09")
						&& java.util.Arrays.asList(e.getRawFields()).contains("130E"), 11, null));
	}

	@Test
	public void dmuMissingGazeCuePreservesKnownElements() {
		var golden = new DmuArrows() {
			@Override
			protected List<CalloutInitialValues> getExpectedCalls() {
				return super.getExpectedCalls().stream().map(call -> call.ms() == 35_861L
						? call(35_861L, "Stack In Thunder") : call).toList();
			}
		};
		verifyScenario(new Scenario("UMAD missing gaze cue", golden,
				e -> field(e, 273, 3, "019D") && e.getRawFields()[4].equals("40")
						&& e.getRawFields()[5].equals("80"),
				null, List.of(31_320L), false), UnaryOperator.identity());
	}

	@Test
	public void dmuMissingBothArrowsPreservesIndependentCallouts() {
		var scenario = new Scenario("UMAD missing both local arrows", new DmuArrows(), null,
				null, List.of(6_776L), false);
		verifyScenario(scenario, events -> ReplayFaults.mutate(ReplayFaults.mutate(events,
				e -> field(e, 26, 2, "130E") && e.getRawFields()[7].equals("10031A09"), 2, null),
				e -> e.getLineNumber() == 38 && e.getRawFields()[2].equals("10031A09")
						&& java.util.Arrays.asList(e.getRawFields()).contains("130E"), 11, null));
	}

	@Test
	public void dmuMissingAllTethersPreservesGazeAndElements() {
		verifyScenario(new Scenario("UMAD missing all tethers", new DmuArrows(), null,
				null, List.of(19_101L), false), events -> ReplayFaults.mutate(events,
				e -> field(e, 35, 8, "002D"), 8, null));
	}

	@Test
	public void dmuDuplicateStartPreservesCalloutCounts() {
		verifyScenario(new Scenario("UMAD duplicate Tele-trouncing cast", new DmuArrows(), null,
				null, List.of(), false), events -> ReplayFaults.duplicate(events,
				e -> field(e, 20, 4, "BAB9"), 1));
	}

	@Test
	public void dmuDuplicateElementPreservesCategoryPair() {
		verifyScenario(new Scenario("UMAD duplicate fire marker", new DmuArrows(), null,
				null, List.of(), false), events -> ReplayFaults.duplicate(events,
				e -> field(e, 27, 6, "02A2"), 1));
	}

	@Test
	public void dmuKefkaDuplicateIcePreservesLaterConesAndLines() {
		verifyScenario(new Scenario("UMAD Kefka Says duplicate first ice marker", new DmuKefka(), null,
				null, List.of(), false), events -> ReplayFaults.duplicate(events,
				e -> field(e, 27, 6, "02A4") && at(e, "2026-09-15T21:21:48.5060000-05:00"), 1));
	}

	@Test
	public void dmuKefkaMissingFirstPairPreservesLaterConesAndLines() {
		verifyScenario(new Scenario("UMAD Kefka Says missing first element pair", new DmuKefka(), null,
				null, List.of(13_506L), false), events -> ReplayFaults.mutate(events,
				e -> e.getLineNumber() == 27 && at(e, "2026-09-15T21:21:48.5060000-05:00"), 2, null));
	}

	@Test
	public void dmuKefkaDelayedIceKeepsItsOriginalPair() {
		var golden = new DmuKefka() {
			@Override
			protected List<CalloutInitialValues> getExpectedCalls() {
				return super.getExpectedCalls().stream().map(call -> call.ms() == 13_506L
						? new CalloutInitialValues(14_083L, call.tts(), call.text(), null) : call)
						.sorted(Comparator.comparingLong(CalloutInitialValues::ms)).toList();
			}
		};
		verifyScenario(new Scenario("UMAD Kefka Says delayed ice with original timestamp", golden,
				e -> field(e, 27, 6, "02A4") && at(e, "2026-09-15T21:21:48.5060000-05:00"),
				Duration.ofMillis(500), List.of(), false, null, List.of(), true), UnaryOperator.identity());
	}

	@Test
	public void dmuExdeathVfxAfterWoundsRetainsReceivedBuffs() {
		verifyScenario(new Scenario("UMAD Exdeath VFX delivered after its wound statuses", new DmuKefka(),
				e -> field(e, 26, 2, "808") && e.getRawFields()[7].equals("4000601E")
						&& at(e, "2026-09-15T21:22:18.9280000-05:00"),
				Duration.ofMillis(10_520), List.of(), false, null, List.of(), true), UnaryOperator.identity());
	}

	@Test
	public void dmuExdeathLateVfxAndMissingWoundPreserveIndependentCallouts() {
		verifyScenario(new Scenario("UMAD late Exdeath VFX with missing local Allagan Field", new DmuKefka(),
				e -> field(e, 26, 2, "808") && e.getRawFields()[7].equals("4000601E")
						&& at(e, "2026-09-15T21:22:18.9280000-05:00"),
				Duration.ofMillis(10_520), List.of(54_439L, 60_184L), false,
				e -> field(e, 26, 2, "1C6") && e.getRawFields()[7].equals("10031A09")
						&& at(e, "2026-09-15T21:22:29.4380000-05:00"), List.of(), true), UnaryOperator.identity());
	}

	@Test
	public void dmuExdeathMissingThirdVfxCannotStealTheFourth() {
		verifyScenario(new Scenario("UMAD missing third Exdeath truth cue", new DmuKefka(),
				e -> field(e, 26, 2, "808") && e.getRawFields()[7].equals("4000601E")
						&& at(e, "2026-09-15T21:22:18.9280000-05:00"),
				null, List.of(), false), UnaryOperator.identity());
	}

	@Test
	public void dmuKefkaQuietGapPreservesTheNextElementPair() {
		var golden = new DmuKefka() {
			@Override
			protected EventIterator<ACTLogLineEvent> getEvents() {
				List<ACTLogLineEvent> retained = new ArrayList<>();
				var events = super.getEvents();
				while (events.hasMore()) {
					var event = events.getNext();
					if (event.getTimestamp().toInstant().isBefore(Instant.parse("2026-09-16T02:21:39.016Z"))
							|| field(event, 20, 4, "C2DC") || field(event, 20, 4, "BAA4")
							|| event.getLineNumber() == 27 && event.getRawFields()[2].equals("40005DE9")
							&& event.getTimestamp().toInstant().isBefore(Instant.parse("2026-09-16T02:22:45.788Z"))) {
						retained.add(event);
					}
				}
				return new ListEventIterator<>(retained);
			}

			@Override
			protected List<CalloutInitialValues> getExpectedCalls() {
				return super.getExpectedCalls().stream()
						.filter(call -> List.of(4_016L, 13_506L, 28_472L, 43_617L).contains(call.ms())).toList();
			}
		};
		verifyScenario(new Scenario("UMAD Kefka Says missing ice with a quiet gap", golden, null,
				null, List.of(13_506L), false), events -> ReplayFaults.mutate(events,
				e -> field(e, 27, 6, "02A4") && at(e, "2026-09-15T21:21:48.5060000-05:00"), 1, null));
	}

	@Test
	public void dmuMissingArrowCannotBlockLaterGraven() {
		var golden = new DmuArrows() {
			@Override
			protected List<CalloutInitialValues> getExpectedCalls() {
				List<CalloutInitialValues> calls = new ArrayList<>(super.getExpectedCalls());
				new DmuGraven().getExpectedCalls().forEach(call -> calls.add(
						new CalloutInitialValues(call.ms() + 55_000L, call.tts(), call.text(), null)));
				return calls;
			}
		};
		var scenario = new Scenario("UMAD starved arrows followed by Graven in the same pull", golden,
				e -> field(e, 26, 2, "130E") && e.getRawFields()[7].equals("10031A09")
						&& e.getRawFields()[5].equals("400055B7"),
				null, List.of(6_776L), false);
		verifyScenario(scenario, events -> {
			List<ACTLogLineEvent> combined = new ArrayList<>();
			append(combined, ReplayFaults.mutate(events,
					e -> e.getLineNumber() == 38 && e.getRawFields()[2].equals("10031A09")
							&& java.util.Arrays.asList(e.getRawFields()).contains("130E"), 11, null), Duration.ZERO, true);
			append(combined, new DmuGraven().getEvents(), Duration.ofSeconds(-120), false);
			return new ListEventIterator<>(combined);
		});
	}

	@Test
	public void dmuWipeCancelsFollowupsAndNextCastRecovers() {
		var golden = new DmuArrows() {
			@Override
			protected List<CalloutInitialValues> getExpectedCalls() {
				List<CalloutInitialValues> calls = new ArrayList<>(super.getExpectedCalls().subList(0, 2));
				super.getExpectedCalls().forEach(call -> calls.add(
						new CalloutInitialValues(call.ms() + 60_000L, call.tts(), call.text(), null)));
				return calls;
			}
		};
		verifyScenario(new Scenario("UMAD wipe during arrows followed by a new pull", golden, null,
				null, List.of(), false), events -> {
			List<ACTLogLineEvent> combined = new ArrayList<>();
			boolean wiped = false;
			while (events.hasMore()) {
				var event = events.getNext();
				if (!wiped && !event.getTimestamp().toInstant().isBefore(Instant.parse("2026-09-16T02:06:37Z"))) {
					combined.add(new ACTLogLineEvent("33|2026-09-15T21:06:37.0000000-05:00|800375D2|40000005|00|00|00|00|0", combined.size()));
					combined.add(new ACTLogLineEvent("33|2026-09-15T21:06:37.0000000-05:00|800375D2|40000010|00|00|00|00|0", combined.size()));
					wiped = true;
				}
				combined.add(new ACTLogLineEvent(event.getLogLine(), combined.size()));
			}
			Assert.assertTrue(wiped);
			append(combined, new DmuArrows().getEvents(), Duration.ofSeconds(60), true);
			return new ListEventIterator<>(combined);
		});
	}

	private static void append(List<ACTLogLineEvent> result, EventIterator<ACTLogLineEvent> events,
	                           Duration shift, boolean includeZone) {
		while (events.hasMore()) {
			var event = events.getNext();
			if (!includeZone && event.getLineNumber() == 1) {
				continue;
			}
			var fields = event.getRawFields().clone();
			fields[1] = event.getTimestamp().plus(shift).toString();
			result.add(new ACTLogLineEvent(String.join("|", fields), result.size()));
		}
	}

	@Test
	public void dmuGravenControl() {
		new DmuGraven().doTheTest();
	}

	@Test
	public void dmuGravenMissingFirstIcePreservesIndependentCallouts() {
		verifyScenario(new Scenario("UMAD Graven missing first ice marker", new DmuGraven(),
				e -> field(e, 27, 6, "02A3") && at(e, "2026-09-15T21:09:48.7530000-05:00"),
				null, List.of(28_753L), false), UnaryOperator.identity());
	}

	@Test
	public void dmuGravenDuplicateFireCannotReplaceMissingIce() {
		verifyScenario(new Scenario("UMAD Graven repeated fire with first ice missing", new DmuGraven(),
				e -> field(e, 27, 6, "02A3") && at(e, "2026-09-15T21:09:48.7530000-05:00"),
				null, List.of(28_753L), false), events -> ReplayFaults.duplicate(events,
				e -> field(e, 27, 6, "02A1") && at(e, "2026-09-15T21:09:48.7530000-05:00"), 1));
	}

	@Test
	public void dmuGravenMissingFirstFirePreservesIndependentCallouts() {
		verifyScenario(new Scenario("UMAD Graven missing first fire marker", new DmuGraven(),
				e -> field(e, 27, 6, "02A1") && at(e, "2026-09-15T21:09:48.7530000-05:00"),
				null, List.of(28_753L), false), UnaryOperator.identity());
	}

	@Test
	public void dmuGravenMissingPlayerMarkerPreservesIndependentCallouts() {
		verifyScenario(new Scenario("UMAD Graven missing first player markers", new DmuGraven(), null,
				null, List.of(28_753L), false), events -> ReplayFaults.mutate(events,
				e -> field(e, 27, 6, "0080") && at(e, "2026-09-15T21:09:48.7530000-05:00"), 2, null));
	}

	@Test
	public void dmuGravenMissingSecondIcePreservesNextGraven() {
		verifyScenario(new Scenario("UMAD Graven missing second ice marker", new DmuGraven(),
				e -> field(e, 27, 6, "02A4") && at(e, "2026-09-15T21:10:05.0210000-05:00"),
				null, List.of(45_021L), false), UnaryOperator.identity());
	}

	@Test
	public void dmuGravenDuplicateIceCannotReplaceSecondThunder() {
		verifyScenario(new Scenario("UMAD Graven repeated ice with second thunder missing", new DmuGraven(),
				e -> field(e, 27, 6, "02A6") && at(e, "2026-09-15T21:10:05.0210000-05:00"),
				null, List.of(45_021L), false), events -> ReplayFaults.duplicate(events,
				e -> field(e, 27, 6, "02A4") && at(e, "2026-09-15T21:10:05.0210000-05:00"), 1));
	}

	@Test
	public void dmuGravenMissingSecondPairPreservesNextGraven() {
		verifyScenario(new Scenario("UMAD Graven missing second element pair", new DmuGraven(), null,
				null, List.of(45_021L), false), events -> ReplayFaults.mutate(events,
				e -> e.getLineNumber() == 27 && (e.getRawFields()[6].equals("02A4") || e.getRawFields()[6].equals("02A6"))
						&& at(e, "2026-09-15T21:10:05.0210000-05:00"), 2, null));
	}

	@Test
	public void dmuGravenLateFirstIcePreservesIndependentCallouts() {
		verifyScenario(new Scenario("UMAD Graven late first ice with original timestamp", new DmuGraven(),
				e -> field(e, 27, 6, "02A3") && at(e, "2026-09-15T21:09:48.7530000-05:00"),
				Duration.ofMillis(500), List.of(28_753L), false, null, List.of(), true), UnaryOperator.identity());
	}

	@Test
	public void dmuKefkaControl() {
		new DmuKefka().doTheTest();
	}

	@Test
	public void dmuMissingFirstIcePreservesLaterConesAndLines() {
		verifyScenario(new Scenario("UMAD Kefka Says missing first ice marker", new DmuKefka(),
				e -> field(e, 27, 6, "02A4") && at(e, "2026-09-15T21:21:48.5060000-05:00"),
				null, List.of(13_506L), false), UnaryOperator.identity());
	}

	private static class DmuGraven extends CalloutVerificationTest {
		@Override
		protected String getFileName() {
			return "/dmu-graven.log";
		}

		@Override
		protected List<CalloutInitialValues> getExpectedCalls() {
			return List.of(
					call(6652, "Buster on Player 119", "Buster on Player 119 (4.7)"),
					call(22699, "Graven Image", "Graven Image (2.7)"),
					call(26685, "No Tether", "No Tether"),
					call(28753, "Spread in Cones", "Spread in Cones"),
					call(34899, "Line Spread", "Line Spread"),
					call(38869, "Avoid Tower", "Avoid Tower"),
					call(41009, "Confetti on Player 116, Player 117", "Confetti on Player 116, Player 117 (5.0)"),
					call(45021, "Avoid Both", "Avoid Both"),
					call(54203, "Raidwide", "Raidwide (4.7)"),
					call(73765, "Graven Image", "Graven Image (2.7)"),
					call(78800, "Avoid Ice, Stone", "Avoid Ice, Stone"),
					call(84009, "Drop Stone", "Drop Stone"),
					call(88774, "Buster on Player 120", "Buster on Player 120 (4.7)"),
					call(94437, "Dark", "Dark"),
					call(102458, "Avoid Stone and Puddle", "Avoid Stone and Puddle"),
					call(115374, "Final Soaks", "Final Soaks"));
		}

		@Override
		protected Instant getCalloutTimeOrigin(MutablePicoContainer pico) {
			return Instant.parse("2026-09-16T02:09:20Z");
		}

		@Override
		protected long minimumMsBetweenCalls() {
			return 0;
		}
	}

	private static class DmuKefka extends CalloutVerificationTest {
		@Override
		protected String getFileName() {
			return "/dmu-kefka.log";
		}

		@Override
		protected List<CalloutInitialValues> getExpectedCalls() {
			return List.of(
					call(4016, "Kefka Says", "Kefka Says (4.7)"),
					call(13506, "Out of Cones, In Lines", "Out of Cones, In Lines"),
					call(13994, "Fake Cross", "Fake Cross (8.7)"),
					call(19071, "Real Tsunami", "Real Tsunami (8.7)"),
					call(23169, "Fake Short Accel", "Fake Short Accel (50.8)"),
					call(28472, "Avoid Both", "Avoid Both"),
					call(29005, "Real Cross", "Real Cross (8.7)"),
					call(30163, "Real Dynamic", "Real Dynamic (82.5)"),
					call(34084, "Fake Inferno", "Fake Inferno (8.7)"),
					call(38005, "Real Water", "Real Water (36.0)"),
					call(43617, "Stand in Both", "Stand in Both"),
					call(44017, "Real Cross", "Real Cross (8.7)"),
					call(46911, "Fake Entropy", "Fake Entropy (43.0)"),
					call(54439, "Real Black + Allag", "Real Black + Allag (15.0)"),
					call(60184, "Stand in Black (Southwest)", "Stand in Black (Southwest) (5.2)"),
					call(65667, "Motion and Stack", "Motion and Stack (8.3)"),
					call(76937, "Real Thunder, Fake Gaze on YOU", "Real Thunder, Fake Gaze on YOU (6.1)"),
					call(83347, "Stack for Donut", "Stack for Donut (6.5)"),
					call(87212, "Raidwide", "Raidwide (4.7)"),
					call(89925, "Stay", "Stay (4.7)"),
					call(95093, "Stack In Ice (with Player 117, Player 120)", "Stack In Ice (with Player 117, Player 120) (3.9)"),
					call(99020, "Real Gaze", "Real Gaze (8.0)"),
					call(106468, "Donut, Fake Ice, Real Thunder", "Donut, Fake Ice, Real Thunder (6.2)"),
					call(106513, "Stay In Ice", "Stay In Ice (4.7)"));
		}

		@Override
		protected Instant getCalloutTimeOrigin(MutablePicoContainer pico) {
			return Instant.parse("2026-09-16T02:21:35Z");
		}

		@Override
		protected long minimumMsBetweenCalls() {
			return 0;
		}
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
