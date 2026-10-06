package gg.xp.xivsupport.events.triggers.endwalker.ultimate;

import gg.xp.reevent.context.StateStore;
import gg.xp.reevent.events.BaseEvent;
import gg.xp.reevent.events.Event;
import gg.xp.reevent.events.EventContext;
import gg.xp.xivdata.data.Job;
import gg.xp.xivsupport.callouts.RawModifiedCallout;
import gg.xp.xivsupport.events.actlines.events.actorcontrol.DutyRecommenceEvent;
import gg.xp.xivsupport.events.actlines.events.*;
import gg.xp.xivsupport.events.misc.EchoEvent;
import gg.xp.xivsupport.events.misc.pulls.PullStartedEvent;
import gg.xp.xivsupport.events.state.XivState;
import gg.xp.xivsupport.events.state.combatstate.HeadmarkerOffsetTracker;
import gg.xp.xivsupport.events.state.combatstate.StatusEffectRepository;
import gg.xp.xivsupport.events.triggers.duties.Dragonsong;
import gg.xp.xivsupport.events.triggers.marks.AutoMarkRequest;
import gg.xp.xivsupport.events.triggers.marks.ClearAutoMarkRequest;
import gg.xp.xivsupport.events.triggers.marks.adv.MarkerSign;
import gg.xp.xivsupport.events.triggers.marks.adv.SpecificAutoMarkRequest;
import gg.xp.xivsupport.events.triggers.marks.adv.AutoMarkServiceSelector;
import gg.xp.xivsupport.events.triggers.seq.SequentialTrigger;
import gg.xp.xivsupport.events.triggers.seq.SequentialTriggerFailedEvent;
import gg.xp.xivsupport.models.*;
import gg.xp.xivsupport.persistence.InMemoryMapPersistenceProvider;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

public class DragonsongAutomarkRegressionTest {

	private static XivPlayerCharacter player(int index) {
		return new XivPlayerCharacter(0x10000001L + index, "Player" + index, Job.WHM,
				XivWorld.unknown(), index == 0, 1, null, null, null, 0, 0, 1, 100, 0, 0);
	}

	private static class Harness implements EventContext, AutoCloseable {
		final List<Event> events = new ArrayList<>();
		final AtomicReference<Instant> clock = new AtomicReference<>(Instant.now());
		final Dragonsong pack;
		final SequentialTrigger<BaseEvent> wroth;
		final AutoMarkServiceSelector selector = new AutoMarkServiceSelector(new InMemoryMapPersistenceProvider(), null);
		final XivCombatant boss = new XivCombatant(0x40000001L, "Hraesvelgr");

		@SuppressWarnings("unchecked")
		Harness() throws Exception {
			XivState state = (XivState) Proxy.newProxyInstance(XivState.class.getClassLoader(),
					new Class<?>[]{XivState.class}, (proxy, method, args) -> {
						if (method.getName().equals("getPartyList")) { return IntStream.range(0, 8).mapToObj(DragonsongAutomarkRegressionTest::player).toList(); }
						throw new UnsupportedOperationException(method.getName());
					});
			pack = new Dragonsong(state, new StatusEffectRepository(null, null),
					new InMemoryMapPersistenceProvider(), new HeadmarkerOffsetTracker(), selector);
			pack.getP5_thunderstruckAutoMarks().set(true);
			pack.getP6_useAutoMarks().set(true);
			pack.getP6_reverseSort().set(false);
			Field field = Dragonsong.class.getDeclaredField("p6_wrothFlames");
			field.setAccessible(true);
			wroth = (SequentialTrigger<BaseEvent>) field.get(pack);
		}

		@Override public void accept(Event event) { events.add(event); }
		@Override public void enqueue(Event event) { events.add(event); }
		@Override public StateStore getStateInfo() { return null; }
		void feed(BaseEvent event) {
			event.setHappenedAt(clock.get());
			event.setTimeSource(clock::get);
			wroth.feed(this, event);
		}
		void tick(long millis) {
			clock.updateAndGet(now -> now.plusMillis(millis));
			feed(new EchoEvent("tick"));
		}
		BuffApplied buff(long id, int target) { return new BuffApplied(new XivStatusEffect(id), 25, boss, player(target), 0); }
		void start() { feed(new AbilityUsedEvent(new XivAbility(0x6D45), boss, player(0), List.of(), 1, 0, 1)); }
		List<SpecificAutoMarkRequest> requests() {
			return events.stream().filter(SpecificAutoMarkRequest.class::isInstance).map(SpecificAutoMarkRequest.class::cast).toList();
		}
		@Override public void close() { wroth.stopSilently(); }
	}

	@Test
	public void duplicateLightningInputsDoNotConsumeTheNextAttackMarker() throws Exception {
		try (Harness h = new Harness()) {
			h.pack.lightning(h, h.buff(0xB11, 1));
			h.pack.lightning(h, h.buff(0xB11, 1));
			h.pack.lightning(h, h.buff(0xB11, 2));
			Assert.assertEquals(h.events.stream().filter(AutoMarkRequest.class::isInstance)
					.map(AutoMarkRequest.class::cast).map(AutoMarkRequest::getPlayerToMark).toList(),
					List.of(player(1), player(2)));
			h.pack.lightningClearAm(h, new PullStartedEvent());
			h.pack.lightning(h, h.buff(0xB11, 1));
			Assert.assertEquals(h.events.stream().filter(AutoMarkRequest.class::isInstance).count(), 3L);
		}
	}

	@Test
	public void duplicatesCannotCompleteWrothAssignmentsAndWipeCancelsClear() throws Exception {
		try (Harness h = new Harness()) {
			h.start();
			for (int i = 0; i < 6; i++) { h.feed(h.buff(2758, 0)); }
			Assert.assertTrue(h.requests().isEmpty(), "One actor cannot complete Wroth assignments");
			for (int i = 1; i < 6; i++) { h.feed(h.buff(i < 4 ? 2758 : 2759, i)); }
			Assert.assertEquals(h.requests().stream().map(SpecificAutoMarkRequest::getPlayerToMark).toList(),
					List.of(player(0), player(1), player(2), player(3), player(4), player(6), player(5), player(7)));
			Assert.assertEquals(h.requests().stream().map(SpecificAutoMarkRequest::getMarker).toList(),
					List.of(MarkerSign.ATTACK1, MarkerSign.ATTACK2, MarkerSign.ATTACK3, MarkerSign.ATTACK4,
							MarkerSign.BIND1, MarkerSign.BIND2, MarkerSign.IGNORE1, MarkerSign.IGNORE2));
			h.feed(new WipeEvent());
			h.tick(26_000);
			Assert.assertTrue(h.events.stream().noneMatch(ClearAutoMarkRequest.class::isInstance), "Wipe cancels old clear");
		}
	}

	@Test
	public void missingWrothRoleDoesNotCrashThePartialAssignments() throws Exception {
		try (Harness h = new Harness()) {
			h.start();
			h.feed(h.buff(2758, 0));
			h.feed(new AbilityCastStart(new XivAbility(0x6D46), h.boss, player(0), 5));
			Assert.assertEquals(h.requests().size(), 1);
			Assert.assertEquals(h.requests().get(0).getPlayerToMark(), player(0));
			Assert.assertEquals(h.requests().get(0).getMarker(), MarkerSign.ATTACK1);
			Assert.assertTrue(h.events.stream().noneMatch(SequentialTriggerFailedEvent.class::isInstance));
		}
	}

	@Test
	public void serviceChangeRetiresWrothMarksAndKeepsTheCalloutSequence() throws Exception {
		try (Harness h = new Harness()) {
			h.start();
			h.feed(h.buff(2758, 0));
			h.selector.setCurrent(null);
			h.selector.setCurrent(h.selector.getOptions().get(0));
			Assert.assertTrue(h.wroth.isActive(), "Service changes preserve the speech sequence");
			for (int i = 1; i < 6; i++) { h.feed(h.buff(i < 4 ? 2758 : 2759, i)); }
			h.tick(26_000);
			Assert.assertTrue(h.requests().isEmpty(), "Older mechanic cannot assign marks after service change");
			Assert.assertTrue(h.events.stream().noneMatch(ClearAutoMarkRequest.class::isInstance));
			Assert.assertTrue(h.events.stream().anyMatch(gg.xp.xivsupport.callouts.RawModifiedCallout.class::isInstance));
		}
	}

	@Test
	public void duplicateWrothStartsKeepCollectedActorsAndPersonalSpeech() throws Exception {
		try (Harness h = new Harness()) {
			h.start();
			h.feed(h.buff(2758, 0));
			h.start();
			for (int i = 1; i < 6; i++) { h.feed(h.buff(i < 4 ? 2758 : 2759, i)); }
			Assert.assertEquals(h.requests().stream().map(SpecificAutoMarkRequest::getPlayerToMark).toList(),
					List.of(player(0), player(1), player(2), player(3), player(4), player(6), player(5), player(7)));
			Assert.assertEquals(h.requests().stream().map(SpecificAutoMarkRequest::getMarker).toList(),
					List.of(MarkerSign.ATTACK1, MarkerSign.ATTACK2, MarkerSign.ATTACK3, MarkerSign.ATTACK4,
							MarkerSign.BIND1, MarkerSign.BIND2, MarkerSign.IGNORE1, MarkerSign.IGNORE2));
			Assert.assertEquals(h.events.stream().filter(RawModifiedCallout.class::isInstance)
					.map(RawModifiedCallout.class::cast).map(RawModifiedCallout::getDescription).toList(),
					List.of("Dragons: Spread"));
			h.tick(26_000);
			Assert.assertEquals(h.events.stream().filter(ClearAutoMarkRequest.class::isInstance).count(), 1L);
		}
	}

	@DataProvider
	public Object[][] wrothBoundaries() { return new Object[][]{{"wipe"}, {"zone"}, {"duty"}}; }

	@Test(dataProvider = "wrothBoundaries")
	public void wrothResetAllowsFreshAssignmentsWithoutAnOldClear(String boundary) throws Exception {
		try (Harness h = new Harness()) {
			h.start();
			for (int i = 0; i < 6; i++) { h.feed(h.buff(i < 4 ? 2758 : 2759, i)); }
			Assert.assertEquals(h.requests().size(), 8);
			h.tick(10_000);
			switch (boundary) {
				case "wipe" -> h.feed(new WipeEvent());
				case "zone" -> h.feed(new ZoneChangeEvent(new XivZone(999, "Other zone")));
				case "duty" -> h.feed(new DutyRecommenceEvent());
			}
			h.events.clear();
			h.start();
			for (int i = 0; i < 6; i++) { h.feed(h.buff(i < 4 ? 2758 : 2759, i)); }
			Assert.assertEquals(h.requests().size(), 8);
			h.tick(16_000);
			Assert.assertTrue(h.events.stream().noneMatch(ClearAutoMarkRequest.class::isInstance),
					"Reset retired the earlier timer");
			h.tick(10_000);
			Assert.assertEquals(h.events.stream().filter(ClearAutoMarkRequest.class::isInstance).count(), 1L);
			Assert.assertTrue(h.events.stream().noneMatch(SequentialTriggerFailedEvent.class::isInstance));
		}
	}
}
