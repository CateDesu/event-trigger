package gg.xp.xivsupport.events.triggers.endwalker.ultimate;

import gg.xp.reevent.context.StateStore;
import gg.xp.reevent.events.BaseEvent;
import gg.xp.reevent.events.Event;
import gg.xp.reevent.events.EventContext;
import gg.xp.services.ServiceDescriptor;
import gg.xp.xivdata.data.Job;
import gg.xp.xivsupport.callouts.RawModifiedCallout;
import gg.xp.xivsupport.events.actlines.events.AbilityCastStart;
import gg.xp.xivsupport.events.actlines.events.AbilityUsedEvent;
import gg.xp.xivsupport.events.actlines.events.BuffApplied;
import gg.xp.xivsupport.events.actlines.events.WipeEvent;
import gg.xp.xivsupport.events.misc.EchoEvent;
import gg.xp.xivsupport.events.state.XivState;
import gg.xp.xivsupport.events.state.combatstate.StatusEffectRepository;
import gg.xp.xivsupport.events.triggers.duties.ewult.OmegaUltimate;
import gg.xp.xivsupport.events.triggers.duties.ewult.omega.ProgramLoopAssignments;
import gg.xp.xivsupport.events.triggers.duties.ewult.omega.OmegaFirstSetAssignments;
import gg.xp.xivsupport.events.triggers.duties.ewult.omega.OmegaSecondSetAssignments;
import gg.xp.xivsupport.events.triggers.marks.ClearAutoMarkRequest;
import gg.xp.xivsupport.events.triggers.marks.adv.MarkerSign;
import gg.xp.xivsupport.events.triggers.marks.adv.SpecificAutoMarkRequest;
import gg.xp.xivsupport.events.triggers.marks.adv.AutoMarkServiceSelector;
import gg.xp.xivsupport.events.triggers.seq.SequentialTrigger;
import gg.xp.xivsupport.events.triggers.seq.SequentialTriggerFailedEvent;
import gg.xp.xivsupport.models.*;
import gg.xp.xivsupport.models.groupmodels.TwoGroupsOfFour;
import gg.xp.xivsupport.persistence.InMemoryMapPersistenceProvider;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

public class OmegaAutomarkRegressionTest {

	private static XivPlayerCharacter player(int index) {
		return new XivPlayerCharacter(0x10000001L + index, "Player" + index, Job.WHM,
				XivWorld.unknown(), index == 0, 1, null, null, null, 0, 0, 1, 100, 0, 0);
	}

	private static class Harness implements EventContext, AutoCloseable {
		final List<Event> events = new ArrayList<>();
		final AtomicReference<Instant> clock = new AtomicReference<>(Instant.now());
		final StatusEffectRepository buffs = new StatusEffectRepository(null, null);
		final SequentialTrigger<BaseEvent> gather;
		final SequentialTrigger<BaseEvent> marks;
		final SequentialTrigger<BaseEvent> secondMarks;
		final OmegaUltimate pack;
		final AutoMarkServiceSelector selector = new AutoMarkServiceSelector(new InMemoryMapPersistenceProvider(), null);
		final XivCombatant boss = new XivCombatant(0x40000001L, "Omega");

		Harness() throws Exception {
			this(false);
		}

		Harness(boolean omega) throws Exception {
			XivState state = (XivState) Proxy.newProxyInstance(XivState.class.getClassLoader(),
					new Class<?>[]{XivState.class}, (proxy, method, args) -> {
						if (method.getName().equals("getPartyList")) { return IntStream.range(0, 8).mapToObj(OmegaAutomarkRegressionTest::player).toList(); }
						if (method.getName().equals("getPlayer")) { return player(0); }
						throw new UnsupportedOperationException(method.getName());
					});
			pack = new OmegaUltimate(state, buffs, null, new InMemoryMapPersistenceProvider(), selector);
			if (omega) { pack.getOmegaAmEnable().set(true); }
			else { pack.getLooperAM().set(true); }
			gather = sequence(pack, omega ? "runDynamisOmegaSq" : "programLoopGather");
			marks = sequence(pack, omega ? "omegaFirstSetAm" : "programLoopAM");
			secondMarks = omega ? sequence(pack, "omegaSecondSetAm") : null;
		}

		@SuppressWarnings("unchecked")
		private static SequentialTrigger<BaseEvent> sequence(OmegaUltimate pack, String name) throws Exception {
			Field field = OmegaUltimate.class.getDeclaredField(name);
			field.setAccessible(true);
			return (SequentialTrigger<BaseEvent>) field.get(pack);
		}

		@Override public void accept(Event event) {
			events.add(event);
			if (event instanceof BaseEvent assignment
					&& (event instanceof ProgramLoopAssignments || event instanceof OmegaFirstSetAssignments)) {
				assignment.setHappenedAt(clock.get());
				assignment.setTimeSource(clock::get);
				marks.feed(this, assignment);
			}
			if (event instanceof OmegaSecondSetAssignments assignment && secondMarks != null) {
				assignment.setHappenedAt(clock.get());
				assignment.setTimeSource(clock::get);
				secondMarks.feed(this, assignment);
			}
		}
		@Override public void enqueue(Event event) { events.add(event); }
		@Override public StateStore getStateInfo() { return null; }

		void feed(BaseEvent event) {
			event.setHappenedAt(clock.get());
			event.setTimeSource(clock::get);
			if (event instanceof BuffApplied applied) { buffs.buffApplication(this, applied); }
			gather.feed(this, event);
			marks.feed(this, event);
			if (secondMarks != null) { secondMarks.feed(this, event); }
		}

		void tick(long millis) {
			clock.updateAndGet(now -> now.plusMillis(millis));
			feed(new EchoEvent("tick"));
		}
		void start() { feed(new AbilityCastStart(new XivAbility(0x7B03), boss, player(0), 5)); }
		void buff(int target) {
			long[] ids = {0xBBC, 0xBBD, 0xBBE, 0xD7B};
			feed(new BuffApplied(new XivStatusEffect(ids[target % 4]), 50, boss, player(target), 0));
		}
		List<SpecificAutoMarkRequest> requests() {
			return events.stream().filter(SpecificAutoMarkRequest.class::isInstance).map(SpecificAutoMarkRequest.class::cast).toList();
		}
		void omegaBuff(long id, double seconds, int target, int stacks) {
			feed(new BuffApplied(new XivStatusEffect(id), seconds, boss, player(target), stacks));
		}
		void omegaStart() {
			omegaBuff(0xD72, 32, 0, 0);
			omegaBuff(0xD73, 32, 1, 0);
			omegaBuff(0xD72, 50, 2, 0);
			omegaBuff(0xD73, 50, 3, 0);
			omegaBuff(0xBBC, 50, 0, 0);
			omegaBuff(0xBBC, 50, 1, 0);
			for (int i = 4; i < 8; i++) { omegaBuff(0xD74, 50, i, 1); }
			feed(new AbilityCastStart(new XivAbility(0x8015), boss, player(0), 5));
		}
		void omegaFirstSet() {
			omegaBuff(0xBBD, 50, 2, 0);
			omegaBuff(0xBBD, 50, 3, 0);
			tick(100);
			tick(1100);
		}
		void hit(long id) {
			feed(new AbilityUsedEvent(new XivAbility(id), boss, player(0), List.of(), 1, 0, 1));
		}
		@Override public void close() {
			gather.stopSilently();
			marks.stopSilently();
			if (secondMarks != null) { secondMarks.stopSilently(); }
		}
	}

	@DataProvider
	public Object[][] delayedActors() { return new Object[][]{{0L}, {2000L}}; }

	@Test(dataProvider = "delayedActors")
	public void duplicatesCannotReplaceADelayedActor(long delay) throws Exception {
		try (Harness h = new Harness()) {
			h.start();
			for (int i = 6; i >= 0; i--) { h.buff(i); }
			h.buff(0);
			h.tick(100);
			Assert.assertTrue(h.requests().isEmpty(), "Seven distinct actors cannot produce marks");
			Assert.assertTrue(h.events.stream().noneMatch(SequentialTriggerFailedEvent.class::isInstance), "Duplicate input must not crash assignment");
			h.tick(delay);
			h.buff(7);
			h.tick(100);
			Assert.assertEquals(h.requests().stream().map(SpecificAutoMarkRequest::getPlayerToMark).toList(),
					List.of(player(0), player(1), player(2), player(3), player(4), player(5), player(6), player(7)));
			Assert.assertEquals(h.requests().stream().map(SpecificAutoMarkRequest::getMarker).toList(),
					List.of(MarkerSign.ATTACK1, MarkerSign.ATTACK2, MarkerSign.ATTACK3, MarkerSign.ATTACK4,
							MarkerSign.BIND1, MarkerSign.BIND2, MarkerSign.BIND3, MarkerSign.CROSS));
			h.buff(7);
			Assert.assertEquals(h.requests().size(), 8, "Duplicate input cannot repeat marks");
			h.feed(new WipeEvent());
			h.tick(36_000);
			Assert.assertTrue(h.events.stream().noneMatch(ClearAutoMarkRequest.class::isInstance), "Wipe cancels old timed clear");
		}
	}

	@Test
	public void incompleteSequenceDoesNotBlockTheNextStart() throws Exception {
		try (Harness h = new Harness()) {
			h.start();
			for (int i = 0; i < 7; i++) { h.buff(i); }
			h.tick(2_000);
			h.start();
			for (int i = 7; i >= 0; i--) { h.buff(i); }
			h.tick(100);
			Assert.assertEquals(h.requests().size(), 8);
			Assert.assertEquals(h.events.stream().filter(ProgramLoopAssignments.class::isInstance).count(), 1L);
			Assert.assertTrue(h.events.stream().noneMatch(SequentialTriggerFailedEvent.class::isInstance));
		}
	}

	@Test
	public void replacementAssignmentsCancelTheOlderTimedClear() throws Exception {
		try (Harness h = new Harness()) {
			EnumMap<TwoGroupsOfFour, XivPlayerCharacter> old = new EnumMap<>(TwoGroupsOfFour.class);
			EnumMap<TwoGroupsOfFour, XivPlayerCharacter> replacement = new EnumMap<>(TwoGroupsOfFour.class);
			int index = 0;
			for (TwoGroupsOfFour slot : TwoGroupsOfFour.values()) {
				old.put(slot, player(index));
				replacement.put(slot, player(7 - index++));
			}
			h.feed(new ProgramLoopAssignments(old));
			h.tick(10_000);
			h.feed(new ProgramLoopAssignments(replacement));
			Assert.assertEquals(h.requests().size(), 16);
			h.tick(26_000);
			Assert.assertTrue(h.events.stream().noneMatch(ClearAutoMarkRequest.class::isInstance));
			h.tick(10_000);
			Assert.assertEquals(h.events.stream().filter(ClearAutoMarkRequest.class::isInstance).count(), 1L);
		}
	}

	@Test
	public void serviceDisableAndReenableRetiresOldTimedClear() throws Exception {
		try (Harness h = new Harness()) {
			h.start();
			for (int i = 0; i < 8; i++) { h.buff(i); }
			h.tick(100);
			Assert.assertEquals(h.requests().size(), 8);
			h.selector.setCurrent(null);
			h.selector.setCurrent(h.selector.getOptions().get(0));
			h.tick(36_000);
			Assert.assertTrue(h.events.stream().noneMatch(ClearAutoMarkRequest.class::isInstance));
			Assert.assertFalse(h.marks.isActive());
		}
	}

	@DataProvider
	public Object[][] omegaControlChanges() {
		return new Object[][]{{false, "none"}, {false, "service"}, {false, "setting"},
				{true, "none"}, {true, "service"}, {true, "setting"}};
	}

	@Test(dataProvider = "omegaControlChanges")
	public void sharedOmegaAssignmentsCannotReviveOldMarkerOutput(boolean secondSet, String control) throws Exception {
		try (Harness h = new Harness(true)) {
			var capture = h.selector.register(ServiceDescriptor.of("capture", "Capture", 20));
			var none = h.selector.getOptions().stream()
					.filter(option -> option.descriptor().id().equals("none")).findFirst().orElseThrow();
			h.omegaStart();
			if (secondSet) {
				h.omegaFirstSet();
				Assert.assertEquals(h.requests().size(), 8);
				h.feed(new AbilityCastStart(new XivAbility(31643), h.boss, player(0), 5));
				h.feed(new AbilityCastStart(new XivAbility(31638), h.boss, player(0), 5));
				h.hit(0x7B89);
				h.events.clear();
			}
			else { h.events.clear(); }
			if (control.equals("service")) {
				h.selector.setCurrent(none);
				h.selector.setCurrent(capture);
			}
			else if (control.equals("setting")) {
				h.pack.getOmegaAmEnable().set(false);
				h.pack.getOmegaAmEnable().set(true);
			}
			if (secondSet) {
				for (int i = 4; i < 8; i++) { h.omegaBuff(0xD74, 50, i, 2); }
				h.hit(0x7B8A);
				h.hit(0x7B8A);
				h.tick(500);
				h.tick(1);
			}
			else { h.omegaFirstSet(); }
			Class<? extends Event> assignment = secondSet ? OmegaSecondSetAssignments.class : OmegaFirstSetAssignments.class;
			Assert.assertEquals(h.events.stream().filter(assignment::isInstance).count(), 1L,
					"Shared assignment remains available to speech consumers");
			Assert.assertTrue(h.gather.isActive(), "Marker changes keep the speech sequence active");
			Assert.assertTrue(h.events.stream().anyMatch(RawModifiedCallout.class::isInstance));
			if (control.equals("none")) {
				Assert.assertEquals(h.requests().stream().map(SpecificAutoMarkRequest::getPlayerToMark).toList(),
						secondSet ? List.of(player(2), player(3), player(4), player(5), player(6), player(7))
								: List.of(player(0), player(1), player(4), player(5), player(6), player(7), player(2), player(3)));
				Assert.assertEquals(h.requests().stream().map(SpecificAutoMarkRequest::getMarker).toList(),
						secondSet ? List.of(MarkerSign.IGNORE1, MarkerSign.IGNORE2, MarkerSign.ATTACK1,
								MarkerSign.ATTACK2, MarkerSign.ATTACK3, MarkerSign.ATTACK4)
								: List.of(MarkerSign.IGNORE1, MarkerSign.IGNORE2, MarkerSign.ATTACK1,
								MarkerSign.ATTACK2, MarkerSign.ATTACK3, MarkerSign.ATTACK4, MarkerSign.BIND1, MarkerSign.BIND2));
			}
			else {
				Assert.assertTrue(h.requests().isEmpty(), "Older shared assignments cannot restart native marks");
				Assert.assertTrue(h.events.stream().noneMatch(ClearAutoMarkRequest.class::isInstance));
			}
			h.feed(new WipeEvent());
			h.events.clear();
			h.omegaStart();
			h.omegaFirstSet();
			Assert.assertEquals(h.requests().size(), 8, "Fresh mechanics still mark after the control change");
			Assert.assertTrue(h.events.stream().noneMatch(SequentialTriggerFailedEvent.class::isInstance));
		}
	}

	@Test
	public void pendingLineCollectionPreservesSharedAssignmentsAfterServiceChange() throws Exception {
		try (Harness h = new Harness()) {
			h.start();
			for (int i = 0; i < 7; i++) { h.buff(i); }
			h.selector.setCurrent(null);
			h.selector.setCurrent(h.selector.getOptions().get(0));
			h.buff(7);
			h.tick(100);
			Assert.assertEquals(h.events.stream().filter(ProgramLoopAssignments.class::isInstance).count(), 1L);
			Assert.assertTrue(h.requests().isEmpty());
			Assert.assertFalse(h.marks.isActive());
		}
	}
}
