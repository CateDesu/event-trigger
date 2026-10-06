package gg.xp.xivsupport.events;

import gg.xp.reevent.context.StateStore;
import gg.xp.reevent.events.Event;
import gg.xp.reevent.events.EventContext;
import gg.xp.reevent.events.EventMaster;
import gg.xp.reevent.events.InitEvent;
import gg.xp.xivdata.data.Job;
import gg.xp.xivsupport.events.actlines.events.AbilityUsedEvent;
import gg.xp.xivsupport.events.actlines.events.AbilityCastStart;
import gg.xp.xivsupport.events.actlines.events.WipeEvent;
import gg.xp.xivsupport.events.actlines.events.ZoneChangeEvent;
import gg.xp.xivsupport.events.actlines.events.actorcontrol.DutyCommenceEvent;
import gg.xp.xivsupport.events.triggers.jails.FinalTitanJailsSolvedEvent;
import gg.xp.xivsupport.events.triggers.marks.AutoMarkRequest;
import gg.xp.xivsupport.events.triggers.marks.AutoMarkSlotRequest;
import gg.xp.xivsupport.events.triggers.marks.ClearAutoMarkRequest;
import gg.xp.xivsupport.events.triggers.marks.adv.MarkerSign;
import gg.xp.xivsupport.events.triggers.marks.adv.SpecificAutoMarkRequest;
import gg.xp.xivsupport.events.triggers.marks.adv.SpecificAutoMarkSlotRequest;
import gg.xp.xivsupport.events.triggers.marks.adv.AutoMarkServiceSelector;
import gg.xp.xivsupport.events.triggers.jails.JailSolver;
import gg.xp.xivsupport.events.triggers.jails.UnsortedTitanJailsSolvedEvent;
import gg.xp.xivsupport.events.state.XivState;
import gg.xp.xivsupport.events.state.PartyChangeEvent;
import gg.xp.xivsupport.events.state.PartyForceOrderChangeEvent;
import gg.xp.xivsupport.events.state.RawXivPartyInfo;
import gg.xp.xivsupport.models.XivAbility;
import gg.xp.xivsupport.models.XivCombatant;
import gg.xp.xivsupport.models.XivPlayerCharacter;
import gg.xp.xivsupport.models.XivWorld;
import gg.xp.xivsupport.models.XivZone;
import gg.xp.xivsupport.persistence.InMemoryMapPersistenceProvider;
import gg.xp.xivsupport.sys.XivMain;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

public class JailAutomarkRegressionTest {

	private static XivPlayerCharacter player(int index) {
		return new XivPlayerCharacter(0x10000001L + index, "Player" + index, Job.WHM,
				XivWorld.unknown(), index == 0, 1, null, null, null, 0, 0, 1, 100, 0, 0);
	}

	private static class Context implements EventContext {
		final List<Event> events = new ArrayList<>();
		@Override public void accept(Event event) { events.add(event); }
		@Override public void enqueue(Event event) { events.add(event); }
		@Override public StateStore getStateInfo() { return null; }
	}

	private static JailSolver solver() { return solver(null); }

	private static JailSolver solver(AutoMarkServiceSelector selector) {
		XivState state = (XivState) Proxy.newProxyInstance(XivState.class.getClassLoader(),
				new Class<?>[]{XivState.class}, (proxy, method, args) -> {
					if (method.getName().equals("getPartyList")) { return List.of(player(0), player(1), player(2)); }
					if (method.getName().equals("dutyIs")) { return true; }
					throw new UnsupportedOperationException(method.getName());
				});
		return selector == null ? new JailSolver(new InMemoryMapPersistenceProvider(), state)
				: new JailSolver(new InMemoryMapPersistenceProvider(), state, selector);
	}

	private static void jail(JailSolver solver, Context context, int target) {
		solver.handleJailCast(context, new AbilityUsedEvent(new XivAbility(0x2B6C),
				new XivCombatant(0x40000001L, "Titan"), player(target), List.of(), 1, 0, 1));
	}

	@Test
	public void duplicatesDoNotSolveMissingActors() {
		JailSolver solver = solver();
		Context context = new Context();
		jail(solver, context, 2);
		jail(solver, context, 2);
		jail(solver, context, 0);
		Assert.assertTrue(context.events.isEmpty(), "Two distinct actors cannot solve three jails");
		jail(solver, context, 1);
		Assert.assertEquals(context.events.size(), 1);
		Assert.assertEquals(((UnsortedTitanJailsSolvedEvent) context.events.get(0)).getJailedPlayers(),
				List.of(player(2), player(0), player(1)));
		jail(solver, context, 0);
		Assert.assertEquals(context.events.size(), 1, "A duplicate cannot emit another assignment");
	}

	private static JailSolver.JailAutoMarkClear mark(JailSolver solver, Context context) {
		FinalTitanJailsSolvedEvent owner = new FinalTitanJailsSolvedEvent(List.of(player(0), player(1), player(2)));
		solver.automarks(context, owner);
		List<AutoMarkRequest> marks = context.events.stream().filter(AutoMarkRequest.class::isInstance)
				.map(AutoMarkRequest.class::cast).toList();
		Assert.assertEquals(marks.stream().map(AutoMarkRequest::getPlayerToMark).toList(),
				List.of(player(0), player(1), player(2)));
		for (int i = 0; i < marks.size(); i++) {
			AutoMarkRequest request = marks.get(i);
			request.setParent(owner);
			solver.otherMarks(context, request);
			AutoMarkSlotRequest slot = new AutoMarkSlotRequest(i + 1);
			slot.setParent(request);
			solver.otherMarks(context, slot);
		}
		JailSolver.JailAutoMarkClear clear = context.events.stream().filter(JailSolver.JailAutoMarkClear.class::isInstance)
				.map(JailSolver.JailAutoMarkClear.class::cast).findFirst().orElseThrow();
		context.events.clear();
		return clear;
	}

	@Test
	public void currentClearRunsOnceAndFetterDoesNotCancelIt() {
		JailSolver solver = solver();
		Context context = new Context();
		JailSolver.JailAutoMarkClear clear = mark(solver, context);
		solver.resetOnFetterBuff(context, new AbilityCastStart(new XivAbility(0x2B6D),
				new XivCombatant(0x40000001L, "Titan"), player(0), 3));
		solver.clearMarks(context, clear);
		solver.clearMarks(context, clear);
		Assert.assertEquals(context.events.size(), 1);
		Assert.assertTrue(context.events.get(0) instanceof ClearAutoMarkRequest);
	}

	@Test
	public void resetAndReplacementCancelOldClear() {
		for (int reset = 0; reset < 7; reset++) {
			JailSolver solver = solver();
			Context context = new Context();
			JailSolver.JailAutoMarkClear old = mark(solver, context);
			switch (reset) {
				case 0 -> solver.handleWipe(context, new WipeEvent());
				case 1 -> solver.handleWipe(context, new ZoneChangeEvent(new XivZone(999, "Other zone")));
				case 2 -> solver.handleWipe(context, new DutyCommenceEvent());
				case 3 -> solver.otherMarks(context, new AutoMarkRequest(player(0)));
				case 4 -> solver.otherMarks(context, new SpecificAutoMarkRequest(player(0), MarkerSign.BIND1));
				case 5 -> solver.otherMarks(context, new AutoMarkSlotRequest(1));
				case 6 -> solver.otherMarks(context, new SpecificAutoMarkSlotRequest(1, MarkerSign.BIND1));
			}
			if (reset == 0 || reset == 2) {
				Assert.assertEquals(context.events.size(), 1, "Reset immediately clears current jail marks");
				Assert.assertTrue(context.events.get(0) instanceof ClearAutoMarkRequest);
				context.events.clear();
			}
			solver.clearMarks(context, old);
			Assert.assertTrue(context.events.isEmpty(), "Old clear survived reset " + reset);
			JailSolver.JailAutoMarkClear replacement = mark(solver, context);
			solver.clearMarks(context, old);
			Assert.assertTrue(context.events.isEmpty(), "Old clear cannot clear a replacement");
			solver.clearMarks(context, replacement);
			Assert.assertEquals(context.events.size(), 1);
		}
	}

	@Test
	public void wipeDoesNotClearNewerExternalMarks() {
		JailSolver solver = solver();
		Context context = new Context();
		JailSolver.JailAutoMarkClear old = mark(solver, context);
		solver.otherMarks(context, new SpecificAutoMarkRequest(player(0), MarkerSign.BIND1));
		solver.handleWipe(context, new WipeEvent());
		solver.clearMarks(context, old);
		Assert.assertTrue(context.events.isEmpty(), "Reset cannot clear a newer mechanic's marks");
	}

	@Test
	public void partyAndSettingChangesRetirePendingClear() {
		for (int boundary = 0; boundary < 3; boundary++) {
			JailSolver solver = solver();
			Context context = new Context();
			JailSolver.JailAutoMarkClear old = mark(solver, context);
			switch (boundary) {
				case 0 -> solver.partyChanged(context, new PartyChangeEvent(List.of()));
				case 1 -> solver.partyChanged(context, new PartyForceOrderChangeEvent(List.of(3L, 2L, 1L)));
				case 2 -> {
					solver.getEnableAutomark().set(false);
					solver.getEnableAutomark().set(true);
				}
			}
			solver.clearMarks(context, old);
			Assert.assertTrue(context.events.isEmpty(), "Old clear survived party/settings boundary " + boundary);
		}
	}

	@Test
	public void identicalPartyAndOrderSnapshotsPreserveCurrentClear() {
		var pico = XivMain.testingMasterInit();
		var master = pico.getComponent(EventMaster.class);
		master.pushEventAndWait(new InitEvent());
		List<RawXivPartyInfo> members = List.of(
				new RawXivPartyInfo(player(0).getId(), "First", 1, 24, 99, true),
				new RawXivPartyInfo(player(1).getId(), "Second", 1, 24, 99, true),
				new RawXivPartyInfo(player(2).getId(), "Third", 1, 24, 99, true));
		List<Long> order = members.stream().map(RawXivPartyInfo::getId).toList();
		master.pushEventAndWait(new PartyChangeEvent(members));
		master.pushEventAndWait(new PartyForceOrderChangeEvent(order));
		JailSolver solver = solver();
		Context context = new Context();
		JailSolver.JailAutoMarkClear clear = mark(solver, context);
		PartyChangeEvent duplicate = new PartyChangeEvent(members);
		master.pushEventAndWait(duplicate);
		Assert.assertFalse(duplicate.isMarkerRosterChanged());
		solver.partyChanged(context, duplicate);
		PartyChangeEvent enriched = new PartyChangeEvent(members.stream()
				.map(member -> new RawXivPartyInfo(member.getId(), "Enriched", 99, member.getJobId(), 100, true)).toList());
		master.pushEventAndWait(enriched);
		Assert.assertFalse(enriched.isMarkerRosterChanged());
		solver.partyChanged(context, enriched);
		PartyForceOrderChangeEvent repeatedOrder = new PartyForceOrderChangeEvent(order);
		master.pushEventAndWait(repeatedOrder);
		Assert.assertFalse(repeatedOrder.isMarkerRosterChanged());
		solver.partyChanged(context, repeatedOrder);
		solver.clearMarks(context, clear);
		Assert.assertEquals(context.events.size(), 1, "Duplicate snapshots must preserve the legitimate timer");
		Assert.assertTrue(context.events.get(0) instanceof ClearAutoMarkRequest);
	}

	@Test
	public void serviceDisableAndReenableRetiresPendingClear() {
		AutoMarkServiceSelector selector = new AutoMarkServiceSelector(new InMemoryMapPersistenceProvider(), null);
		JailSolver solver = solver(selector);
		Context context = new Context();
		JailSolver.JailAutoMarkClear old = mark(solver, context);
		selector.setCurrent(null);
		selector.setCurrent(selector.getOptions().get(0));
		solver.clearMarks(context, old);
		Assert.assertTrue(context.events.isEmpty(), "A new service epoch cannot run an old clear");
	}

	@Test
	public void disabledAutomarkersDoNotQueueActions() {
		JailSolver solver = solver();
		solver.getEnableAutomark().set(false);
		Context context = new Context();
		solver.automarks(context, new FinalTitanJailsSolvedEvent(List.of(player(0), player(1), player(2))));
		Assert.assertTrue(context.events.isEmpty());
	}
}
