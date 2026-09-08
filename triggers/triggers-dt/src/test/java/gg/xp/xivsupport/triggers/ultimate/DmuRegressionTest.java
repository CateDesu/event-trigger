package gg.xp.xivsupport.triggers.ultimate;

import gg.xp.reevent.context.StateStore;
import gg.xp.reevent.events.BaseEvent;
import gg.xp.reevent.events.Event;
import gg.xp.reevent.events.EventContext;
import gg.xp.xivdata.data.Job;
import gg.xp.xivsupport.callouts.RawModifiedCallout;
import gg.xp.xivsupport.events.actlines.events.*;
import gg.xp.xivsupport.events.actlines.events.vfx.StatusLoopVfxApplied;
import gg.xp.xivsupport.events.misc.EchoEvent;
import gg.xp.xivsupport.events.state.XivState;
import gg.xp.xivsupport.events.state.combatstate.ActiveCastRepository;
import gg.xp.xivsupport.events.state.combatstate.CastTracker;
import gg.xp.xivsupport.events.state.combatstate.StatusEffectRepository;
import gg.xp.xivsupport.events.triggers.marks.adv.MarkerSign;
import gg.xp.xivsupport.events.triggers.marks.adv.SpecificAutoMarkRequest;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class DmuRegressionTest {

	private static final XivCombatant BOSS = new XivCombatant(0x40000001L, "Boss");

	private static XivPlayerCharacter player(int index) {
		return new XivPlayerCharacter(0x10FF0001L + index, "Player" + index, Job.WHM,
				XivWorld.unknown(), index == 0, 1, null, null, null, 0, 0, 1, 100, 0, 0);
	}

	private static XivCombatant npc(long id, Position position) {
		return new XivCombatant(id, "NPC", false, false, 2, null, null, position, id, 0, 0, 100, 0, 0);
	}

	private static class Context implements EventContext {
		final List<Event> events = Collections.synchronizedList(new ArrayList<>());
		@Override public void accept(Event event) { events.add(event); }
		@Override public void enqueue(Event event) { events.add(event); }
		@Override public StateStore getStateInfo() { return null; }

		List<String> calls() {
			return events.stream().filter(RawModifiedCallout.class::isInstance)
					.map(RawModifiedCallout.class::cast).map(RawModifiedCallout::getDescription).toList();
		}

		List<SpecificAutoMarkRequest> marks() {
			return events.stream().filter(SpecificAutoMarkRequest.class::isInstance)
					.map(SpecificAutoMarkRequest.class::cast).toList();
		}
	}

	private static class Harness implements AutoCloseable {
		final Context context = new Context();
		final AtomicReference<Instant> clock = new AtomicReference<>(Instant.now());
		final StatusEffectRepository buffs = new StatusEffectRepository(null, null);
		final DMU pack;
		SequentialTrigger<BaseEvent> sequence;

		Harness() {
			XivState state = (XivState) Proxy.newProxyInstance(XivState.class.getClassLoader(),
					new Class<?>[]{XivState.class}, (proxy, method, args) -> {
						if (method.getName().equals("getLatestCombatantData")) { return args[0]; }
						throw new UnsupportedOperationException(method.getName());
					});
			ActiveCastRepository casts = new ActiveCastRepository() {
				@Override public CastTracker getCastFor(XivCombatant combatant) { return null; }
				@Override public List<CastTracker> getAll() { return List.of(); }
			};
			pack = new DMU(state, casts, buffs, new InMemoryMapPersistenceProvider());
		}

		@SuppressWarnings("unchecked")
		void select(String name) throws Exception {
			Field field = DMU.class.getDeclaredField(name);
			field.setAccessible(true);
			sequence = (SequentialTrigger<BaseEvent>) field.get(pack);
		}

		void feed(BaseEvent event) {
			event.setHappenedAt(clock.get());
			event.setTimeSource(clock::get);
			if (event instanceof BuffApplied applied) { buffs.buffApplication(context, applied); }
			if (event instanceof BuffRemoved removed) { buffs.buffRemove(context, removed); }
			if (event instanceof StatusLoopVfxApplied vfx) { pack.handleVfx(vfx); }
			if (sequence != null) { sequence.feed(context, event); }
			pack.maintainKefkaMarks(context, event);
		}

		BuffApplied buff(long id, double seconds, int target) {
			BuffApplied applied = new BuffApplied(new XivStatusEffect(id), seconds, BOSS, player(target), 0);
			feed(applied);
			return applied;
		}

		void remove(BuffApplied buff) {
			feed(new BuffRemoved(buff.getBuff(), 0, buff.getSource(), buff.getTarget(), 0));
		}

		void tick(long millis) {
			clock.updateAndGet(t -> t.plusMillis(millis));
			feed(new EchoEvent("tick"));
		}

		void tethers(Position position) {
			for (int i = 0; i < 8; i++) { feed(new TetherEvent(npc(99, position), player(i), 45)); }
			tick(200);
			tick(200);
		}

		void cast(long id) { feed(new AbilityCastStart(new XivAbility(id), BOSS, player(0), 5)); }
		void hit(long id) { feed(new AbilityUsedEvent(new XivAbility(id), BOSS, player(0), List.of(), 1, 0, 1)); }
		void hand(Position position) {
			feed(new ActorControlExtraEvent(npc(100, position), 0x19D, 0x40, 0x80, 0, 0));
			tick(300);
			tick(300);
		}

		@Override public void close() {
			if (sequence != null) { sequence.stopSilently(); }
			Assert.assertTrue(context.events.stream().noneMatch(SequentialTriggerFailedEvent.class::isInstance),
					"No chain should fail while handling incomplete data");
		}
	}

	@DataProvider
	public Object[][] arrows() {
		return new Object[][]{{false, false, false}, {true, false, false}, {true, true, false},
				{true, false, true}, {true, true, true}};
	}

	@Test(dataProvider = "arrows")
	public void duplicateArrowsDoNotCompleteThePair(boolean duplicate, boolean reversed, boolean sameDirection) throws Exception {
		try (Harness h = new Harness()) {
			h.select("ttSq");
			h.cast(0xBAB9);
			long secondId = sameDirection ? 0x130C : 0x13D9;
			long firstId = reversed ? secondId : 0x130C;
			h.buff(firstId, reversed ? 10 : 7, 0);
			if (duplicate) {
				h.buff(firstId, reversed ? 10 : 7, 0);
				Assert.assertEquals(h.context.calls(), List.of("Tele-trouncing"));
			}
			h.buff(reversed ? 0x130C : secondId, reversed ? 7 : 10, 0);
			Assert.assertTrue(h.context.calls().contains(sameDirection ? "TT: Double N" : "TT: N -> E"));
		}
	}

	@DataProvider
	public Object[][] positions() { return new Object[][]{{true}, {false}}; }

	@Test(dataProvider = "positions")
	public void missingPositionsSkipOnlyDependentGravenCalls(boolean known) throws Exception {
		try (Harness h = new Harness()) {
			h.select("gravenImageSq");
			h.cast(0xBCF2);
			h.sequence.forceExpire();
			h.cast(0xBCF2);
			h.tethers(known ? Position.of2d(125, 100) : null);
			h.feed(new HeadMarkerEvent(BOSS, 675));
			h.hit(0xBAAC);
			h.hit(0xBAB0);
			h.hand(known ? Position.of2d(116, 43) : null);
			Assert.assertEquals(h.context.calls().stream().anyMatch(c -> c.contains("West Safe")), known);
			Assert.assertFalse(h.context.calls().stream().anyMatch(c -> c.contains("East Safe") || c.contains("Dark Tether")));
			h.tethers(Position.of2d(125, 100));
			h.hit(0xBAAC);
			h.hand(null);
			h.tick(10_000);
			Assert.assertTrue(h.context.calls().stream().anyMatch(c -> c.contains("Final Soaks")), h.context.calls().toString());
		}
	}

	@Test
	public void missingGazeKeepsTheElementCall() throws Exception {
		try (Harness h = new Harness()) {
			h.select("ttSq");
			h.cast(0xBAB9);
			h.buff(0x130C, 7, 0);
			BuffApplied second = h.buff(0x13D9, 10, 0);
			h.remove(second);
			h.tethers(null);
			h.hand(null);
			h.feed(new HeadMarkerEvent(BOSS, 673));
			h.feed(new HeadMarkerEvent(BOSS, 677));
			h.feed(new HeadMarkerEvent(player(0), 127));
			Assert.assertTrue(h.context.calls().contains("TT: Element Mechanics"));
			Assert.assertFalse(h.context.calls().stream().anyMatch(c -> c.contains("Real Gaze") || c.contains("Confusion Tether")));
		}
	}

	@Test
	public void arrowRefreshWithLessTimeStillWaitsForTheSecondArrow() throws Exception {
		try (Harness h = new Harness()) {
			h.select("ttSq");
			h.cast(0xBAB9);
			h.buff(0x130C, 7, 0);
			h.tick(1500);
			h.buff(0x130C, 5.5, 0);
			Assert.assertEquals(h.context.calls(), List.of("Tele-trouncing"));
			h.buff(0x13D9, 8.5, 0);
			Assert.assertTrue(h.context.calls().contains("TT: N -> E"));
		}
	}

	@Test
	public void independentMarkGroupsKeepTheirSignsUntilEachHolderResolves() {
		try (Harness h = new Harness()) {
			BuffApplied water = h.buff(0x15A9, 45, 0);
			BuffApplied shriek = h.buff(0x15A7, 50, 1);
			DmuDebuffMarks marks = new DmuDebuffMarks(h.buffs);
			marks.start(List.of(water, shriek), h.context::accept);
			Assert.assertEquals(h.context.marks().stream().map(SpecificAutoMarkRequest::getMarker).toList(),
					List.of(MarkerSign.BIND1, MarkerSign.IGNORE1));
			h.context.events.clear();
			h.remove(water);
			marks.update(h.context::accept);
			Assert.assertEquals(h.context.marks().size(), 1);
			Assert.assertEquals(h.context.marks().get(0).getPlayerToMark(), player(0));
			h.context.events.clear();
			h.tick(51_000);
			marks.update(h.context::accept);
			Assert.assertEquals(h.context.marks().size(), 1);
			Assert.assertEquals(h.context.marks().get(0).getPlayerToMark(), player(1));
			Assert.assertEquals(h.context.marks().get(0).getMarker(), MarkerSign.CLEAR);
		}
	}

	@Test
	public void kefkaHeadmarkersArriveWhileMarksAreStillActive() throws Exception {
		try (Harness h = new Harness()) {
			h.select("kefkaSaysSqExdeath");
			h.cast(0xC2DC);
			XivCombatant kefka = npc(18475, null);
			XivCombatant exdeath = npc(19510, null);
			List<BuffApplied> firstWave = new ArrayList<>();
			for (int wave = 0; wave < 2; wave++) {
				h.feed(new HeadMarkerEvent(kefka, 673));
				h.feed(new HeadMarkerEvent(kefka, 675));
				h.feed(new StatusLoopVfxApplied(exdeath,
						new BuffApplied(new XivStatusEffect(0), 90, BOSS, exdeath, 1122)));
				long[] ids = {0x15A8, 0x15A8, 0x15A9, 0x15A9, 0x15A7, 0x15A7, 0x15AA, 0x15AA, 0x15AA, 0x15AA};
				int[] targets = wave == 0 ? new int[]{1, 2, 3, 4, 5, 6, 0, 5, 6, 7}
						: new int[]{3, 4, 1, 2, 0, 7, 3, 4, 5, 6};
				for (int i = 0; i < ids.length; i++) {
					BuffApplied buff = h.buff(ids[i], wave == 0 ? 45 : 75, targets[i]);
					if (wave == 0) { firstWave.add(buff); }
				}
				h.tick(200);
				h.tick(200);
			}
			Assert.assertEquals(h.context.marks().size(), 6);
			h.feed(new HeadMarkerEvent(kefka, 673));
			h.feed(new HeadMarkerEvent(kefka, 675));
			for (int i = 0; i < 8; i++) {
				h.buff(0x15A5, 60, i);
				h.buff(0x566, 60, i);
			}
			h.feed(new StatusLoopVfxApplied(exdeath,
					new BuffApplied(new XivStatusEffect(0), 90, BOSS, exdeath, 1122)));
			AbilityCastStart cast = new AbilityCastStart(new XivAbility(0xC395), BOSS, player(0), 5);
			CastLocationDataEvent location = new CastLocationDataEvent(cast, Position.of2d(100, 100));
			cast.setLocationInfo(location);
			h.feed(location);
			h.tick(30_000);
			AbilityUsedEvent hit = new AbilityUsedEvent(cast.getAbility(), BOSS, player(0), List.of(), 1, 0, 1);
			hit.setPrecursor(cast);
			h.feed(hit);
			h.feed(new HeadMarkerEvent(kefka, 677));
			h.feed(new HeadMarkerEvent(kefka, 675));
			Assert.assertTrue(h.context.calls().stream().anyMatch(c -> c.contains("Second Debuff Set Resolving")),
					h.context.calls().toString());
			Assert.assertTrue(firstWave.stream().allMatch(h.buffs::statusOrRefreshActive));
			h.context.events.clear();
			h.feed(new WipeEvent());
			Assert.assertEquals(h.context.marks().size(), 6);
			Assert.assertTrue(h.context.marks().stream().allMatch(mark -> mark.getMarker() == MarkerSign.CLEAR));
			h.context.events.clear();
			h.remove(firstWave.get(0));
			Assert.assertTrue(h.context.marks().isEmpty());
		}
	}

	@Test
	public void marksClearPerHolderAndAdvanceByResolution() {
		try (Harness h = new Harness()) {
			BuffApplied longOne = h.buff(0x15A8, 75, 2);
			BuffApplied longTwo = h.buff(0x15A8, 75, 3);
			BuffApplied shortOne = h.buff(0x15A8, 45, 0);
			BuffApplied shortTwo = h.buff(0x15A8, 45, 1);
			DmuDebuffMarks marks = new DmuDebuffMarks(h.buffs);
			marks.start(List.of(longOne, longTwo, shortOne, shortOne, shortTwo), h.context::accept);
			Assert.assertEquals(h.context.marks().stream().map(SpecificAutoMarkRequest::getPlayerToMark).toList(),
					List.of(player(0), player(1)));
			h.context.events.clear();
			h.remove(shortOne);
			marks.update(h.context::accept);
			Assert.assertEquals(h.context.marks().size(), 1);
			Assert.assertEquals(h.context.marks().get(0).getPlayerToMark(), player(0));
			Assert.assertEquals(h.context.marks().get(0).getMarker(), MarkerSign.CLEAR);
			h.context.events.clear();
			h.remove(shortTwo);
			marks.update(h.context::accept);
			Assert.assertEquals(h.context.marks().stream().map(SpecificAutoMarkRequest::getMarker).toList(),
					List.of(MarkerSign.CLEAR, MarkerSign.ATTACK1, MarkerSign.ATTACK2));
			h.context.events.clear();
			h.remove(shortOne);
			marks.update(h.context::accept);
			Assert.assertTrue(h.context.marks().isEmpty());
			marks.clear(h.context::accept);
			Assert.assertEquals(h.context.marks().size(), 2);
			h.context.events.clear();
			marks.update(h.context::accept);
			Assert.assertTrue(h.context.marks().isEmpty());
		}
	}
}
