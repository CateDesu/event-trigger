package gg.xp.xivsupport.triggers.ultimate;

import gg.xp.reevent.events.Event;
import gg.xp.reevent.events.EventContext;
import gg.xp.reevent.events.EventDistributor;
import gg.xp.reevent.events.EventHandler;
import gg.xp.reevent.events.EventMaster;
import gg.xp.reevent.events.InitEvent;
import gg.xp.reevent.events.TestEventCollector;
import gg.xp.xivsupport.callouts.RawModifiedCallout;
import gg.xp.xivsupport.events.ACTLogLineEvent;
import gg.xp.xivsupport.events.misc.EchoEvent;
import gg.xp.xivsupport.events.actlines.events.WipeEvent;
import gg.xp.xivsupport.events.actlines.events.XivStateRecalculatedEvent;
import gg.xp.xivsupport.events.actlines.events.HeadMarkerEvent;
import gg.xp.xivsupport.events.actlines.events.BuffRemoved;
import gg.xp.xivsupport.events.state.XivStateImpl;
import gg.xp.xivsupport.events.state.combatstate.StatusEffectRepository;
import gg.xp.xivsupport.models.XivEntity;
import gg.xp.xivsupport.events.actlines.parsers.FakeACTTimeSource;
import gg.xp.xivsupport.events.delaytest.BaseDelayedEvent;
import gg.xp.xivsupport.events.triggers.seq.SequentialTriggerFailedEvent;
import gg.xp.xivsupport.eventstorage.EventReader;
import gg.xp.xivsupport.replay.ReplayController;
import gg.xp.xivsupport.sys.KnownLogSource;
import gg.xp.xivsupport.sys.PrimaryLogSource;
import gg.xp.xivsupport.sys.XivMain;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class DmuRecoveryTest {

	private List<String> replay(String resource) {
		return replay(resource, 0, false);
	}

	private List<String> replay(String resource, long player, boolean missingStackTargets) {
		var pico = XivMain.testingMasterInit();
		pico.addComponent(FakeACTTimeSource.class);
		var clock = pico.getComponent(FakeACTTimeSource.class);
		pico.getComponent(PrimaryLogSource.class).setLogSource(KnownLogSource.ACT_LOG_FILE);
		var removedStackTargets = new AtomicBoolean();
		var dist = pico.getComponent(EventDistributor.class);
		var collected = new TestEventCollector();
		dist.registerHandler(collected);
		dist.registerHandler(Event.class, new EventHandler<>() {
			@Override
			public void handle(EventContext context, Event event) {
				if (missingStackTargets && event instanceof HeadMarkerEvent marker
						&& marker.markerIdMatches(675, 676) && marker.getTarget().npcIdMatches(18475)) {
					// Reproduce the empty stack state recorded in the live failure.
					var buffs = pico.getComponent(StatusEffectRepository.class);
					for (var buff : buffs.findBuffs(ba -> ba.buffIdMatches(0x15A8, 0x15A9))) {
						buffs.buffRemove(context, new BuffRemoved(buff.getBuff(), 0,
								buff.getSource(), buff.getTarget(), 0));
					}
					removedStackTargets.set(true);
				}
				if (event instanceof BaseDelayedEvent delayed) {
					if (delayed.getParent() != null) {
						delayed.setHappenedAt(delayed.getParent().getHappenedAt());
					}
					delayed.setTimeSource(clock);
				}
				else if (event instanceof XivStateRecalculatedEvent recalculated) {
					recalculated.setTimeSource(clock);
					recalculated.setHappenedAt(clock.now());
				}
			}

			@Override
			public int getOrder() {
				return -5000;
			}
		});
		dist.acceptEvent(new InitEvent());
		if (player != 0) {
			pico.getComponent(XivStateImpl.class).setPlayerTmpOverride(new XivEntity(player, "Player"));
		}
		var master = pico.getComponent(EventMaster.class);
		var replay = new ReplayController(master, EventReader.readActLogResource(resource), false) {
			@Override
			protected void preProcessEvent(Event event) {
				if (resource.equals("/dmu-arrow-applications.log") && event instanceof ACTLogLineEvent line
						&& line.getLineNumber() == 1) {
					// Each arrow sample starts fresh before its clock advances.
					var wipe = new WipeEvent();
					wipe.setTimeSource(clock);
					master.pushEventAndWait(wipe);
				}
			}
		};
		replay.advanceBy(Integer.MAX_VALUE);
		master.getQueue().waitDrain();
		if (resource.equals("/dmu-midtt.log")) {
			clock.setNewTime(clock.now().plusSeconds(181));
			var tick = new EchoEvent("After the mechanic");
			tick.setTimeSource(clock);
			tick.setHappenedAt(clock.now());
			master.pushEventAndWait(tick);
		}
		if (missingStackTargets) {
			Assert.assertTrue(removedStackTargets.get(), "The replay must reach the missing stack state");
		}
		var failures = collected.getEventsOf(SequentialTriggerFailedEvent.class);
		Assert.assertTrue(failures.isEmpty(), failures.toString());
		return collected.getEventsOf(RawModifiedCallout.class).stream()
				.map(RawModifiedCallout::getDescription).toList();
	}

	@Test
	public void secondGravenWithoutFirstCast() {
		var calls = replay("/dmu-midpull.log");
		Assert.assertTrue(calls.contains("Graven Image 2: Real Ice, Dark"), calls.toString());
		Assert.assertTrue(calls.contains("Graven Image 2: Final Soaks"), calls.toString());
		Assert.assertFalse(calls.contains("Graven Image 1: Spread For Laser"), calls.toString());
		Assert.assertEquals(calls.stream().filter("Graven Image"::equals).count(), 1L, calls.toString());
	}

	@Test
	public void bothGravenSequencesFromPullStart() {
		var calls = replay("/dmu-graven.log");
		Assert.assertTrue(calls.contains("Graven Image 1: Spread For Laser"), calls.toString());
		Assert.assertTrue(calls.contains("Graven Image 1: Got Hit by Laser"), calls.toString());
		Assert.assertTrue(calls.contains("Graven Image 2: Real Ice, Stone"), calls.toString());
		Assert.assertTrue(calls.contains("Graven Image 2: Final Soaks"), calls.toString());
	}

	@Test
	public void kefkaSaysReachesFinalGaze() {
		var calls = replay("/dmu-kefka.log");
		Assert.assertTrue(calls.contains("Kefka Says: Second Shrieks Resolving"), calls.toString());
	}

	@Test
	public void arrowsContinueThroughElements() {
		var calls = replay("/dmu-arrows.log");
		Assert.assertTrue(calls.contains("TT: Double E"), calls.toString());
		Assert.assertTrue(calls.contains("TT: Fake Gaze (Early Call)"), calls.toString());
		Assert.assertFalse(calls.contains("TT: Real Gaze (Early Call)"), calls.toString());
		Assert.assertTrue(calls.contains("TT: Element Mechanics"), calls.toString());
	}

	@Test
	public void kefkaSaysWithoutStackTargets() {
		var calls = replay("/dmu-kefka.log", 0, true);
		Assert.assertTrue(calls.contains("Kefka Says: Second Debuff Set Resolving: Nothing"), calls.toString());
		Assert.assertTrue(calls.contains("Kefka Says: Second Shrieks Resolving"), calls.toString());
	}

	@Test
	public void kefkaSaysWithForkedLightning() {
		var calls = replay("/dmu-kefka.log", 0x10072917L, false);
		Assert.assertTrue(calls.contains("Kefka Says: Second Debuff Set Resolving: Stack"), calls.toString());
		Assert.assertTrue(calls.contains("Kefka Says: Second Shrieks Resolving"), calls.toString());
	}

	@Test
	public void recordedArrowApplications() {
		var calls = replay("/dmu-arrow-applications.log");
		var directions = calls.stream()
				.filter(call -> call.startsWith("TT: Double ") || call.startsWith("TT:") && call.contains(" -> "))
				.toList();
		Assert.assertEquals(directions.size(), 125, directions.toString());
		Assert.assertFalse(calls.contains("TT: Error"), calls.toString());
	}

	@Test
	public void startingDuringArrowsDoesNotMistakeGraven() {
		var calls = replay("/dmu-midtt.log");
		Assert.assertFalse(calls.stream().anyMatch(call -> call.startsWith("Graven Image 1:")
				|| call.startsWith("Graven Image 2:")), calls.toString());
	}

}
