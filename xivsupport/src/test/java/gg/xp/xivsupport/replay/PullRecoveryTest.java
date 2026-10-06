package gg.xp.xivsupport.replay;

import gg.xp.reevent.events.EventDistributor;
import gg.xp.reevent.events.EventMaster;
import gg.xp.reevent.events.InitEvent;
import gg.xp.xivsupport.events.ACTLogLineEvent;
import gg.xp.xivsupport.events.actlines.events.WipeEvent;
import gg.xp.xivsupport.events.actlines.events.ChatLineEvent;
import gg.xp.xivsupport.events.actlines.events.ZoneChangeEvent;
import gg.xp.xivsupport.events.actlines.events.actorcontrol.DutyRecommenceEvent;
import gg.xp.xivsupport.events.actlines.events.actorcontrol.FadeOutEvent;
import gg.xp.xivsupport.events.actlines.events.actorcontrol.VictoryEvent;
import gg.xp.xivsupport.events.misc.pulls.PullEndedEvent;
import gg.xp.xivsupport.events.misc.pulls.PullStartedEvent;
import gg.xp.xivsupport.events.state.PartyChangeEvent;
import gg.xp.xivsupport.events.state.PartyForceOrderChangeEvent;
import gg.xp.xivsupport.events.ws.ActWsRawMsg;
import gg.xp.xivsupport.models.XivZone;
import gg.xp.xivsupport.sys.PrimaryLogSource;
import gg.xp.xivsupport.sys.XivMain;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class PullRecoveryTest {
    @Test
    public void cancellationReturnsOnlyItsOwnGenerationPermit() {
        var clock = new RecoveryClock();
        var recovery = new PullRecovery(clock, new RecoveryQueue(clock), null, null);
        var old = recovery.outputPermit();
        var cancelled = recovery.cancelPendingOutput();
        Assert.assertFalse(old.getAsBoolean());
        Assert.assertTrue(cancelled.getAsBoolean());
        recovery.cancelPendingOutput();
        Assert.assertFalse(cancelled.getAsBoolean());
    }

    @Test
    public void repeatedRawPartySnapshotsPreserveOutputButChangesCancelIt() {
        for (boolean websocket : List.of(false, true)) {
            var pico = XivMain.testingMasterInit();
            var dist = pico.getComponent(EventDistributor.class);
            var master = pico.getComponent(EventMaster.class);
            master.pushEventAndWait(new InitEvent());
            var clock = new RecoveryClock();
            var recovery = new PullRecovery(clock, new RecoveryQueue(clock), master,
                    pico.getComponent(PrimaryLogSource.class));
            dist.registerHandler(recovery);
            var snapshots = new ArrayList<PartyChangeEvent>();
            dist.registerHandler(PartyChangeEvent.class, (c, e) -> snapshots.add(e));
            feedParty(master, websocket, false, false, false);
            var current = recovery.outputPermit();
            master.pushEventAndWait(new PartyForceOrderChangeEvent(List.of(0x10000001L, 0x10000002L)));
            Assert.assertFalse(current.getAsBoolean());
            current = recovery.outputPermit();
            feedParty(master, websocket, false, false, false);
            Assert.assertTrue(current.getAsBoolean(), "An identical raw snapshot cancelled output");
            Assert.assertFalse(snapshots.get(snapshots.size() - 1).isMarkerRosterChanged());
            if (websocket) {
                feedParty(master, true, false, true, false);
                Assert.assertTrue(current.getAsBoolean(), "Metadata enrichment changed authoritative marker ownership");
                Assert.assertFalse(snapshots.get(snapshots.size() - 1).isMarkerRosterChanged());
            }
            master.pushEventAndWait(new PartyForceOrderChangeEvent(List.of(0x10000001L, 0x10000002L)));
            Assert.assertTrue(current.getAsBoolean(), "Repeated forced order cancelled output");
            feedParty(master, websocket, true, false, false);
            Assert.assertFalse(current.getAsBoolean(), "Reordered actors retained stale output");
            current = recovery.outputPermit();
            feedParty(master, websocket, true, false, true);
            Assert.assertFalse(current.getAsBoolean(), "Removed party member retained stale output");
            if (websocket) {
                current = recovery.outputPermit();
                master.pushEventAndWait(new ActWsRawMsg("{\"type\":\"PartyChanged\",\"party\":["
                        + "{\"id\":\"10000002\",\"name\":\"Player\",\"job\":21,\"inParty\":true}]}"));
                Assert.assertFalse(current.getAsBoolean(), "Changed job retained stale selection output");
            }
            Assert.assertEquals(snapshots.size(), websocket ? 6 : 4, "Every raw snapshot must be delivered");
            master.pushEventAndWait(new PartyForceOrderChangeEvent(null));
            current = recovery.outputPermit();
            master.pushEventAndWait(new PartyForceOrderChangeEvent(List.of()));
            Assert.assertTrue(current.getAsBoolean(), "Null and empty order overrides are equivalent");
        }
    }

    private static void feedParty(EventMaster master, boolean websocket, boolean reversed,
                                  boolean metadata, boolean removed) {
        String first = "10000001", second = "10000002";
        if (reversed) { String swap = first; first = second; second = swap; }
        if (websocket) {
            String suffix = metadata ? ",\"name\":\"Enriched\",\"worldId\":99,\"level\":100"
                    : ",\"name\":\"Player\",\"worldId\":1,\"level\":99";
            String one = "{\"id\":\"" + first + "\",\"job\":24,\"inParty\":true" + suffix + "}";
            String two = "{\"id\":\"" + second + "\",\"job\":24,\"inParty\":true" + suffix + "}";
            master.pushEventAndWait(new ActWsRawMsg("{\"type\":\"PartyChanged\",\"party\":["
                    + one + (removed ? "" : "," + two) + "]}"));
        }
        else {
            master.pushEventAndWait(new ACTLogLineEvent("11|" + Instant.now() + "|"
                    + (removed ? "1" : "2") + "|" + first + (removed ? "" : "|" + second) + "|0"));
        }
    }

    @Test
    public void rejectedParserFieldsCountWithoutDiscardingLaterEvents() {
        var pico = XivMain.testingMasterInit();
        var dist = pico.getComponent(EventDistributor.class);
        var master = pico.getComponent(EventMaster.class);
        master.pushEventAndWait(new InitEvent());
        var clock = new RecoveryClock();
        var recovery = new PullRecovery(clock, new RecoveryQueue(clock), pico.getComponent(EventMaster.class),
                pico.getComponent(PrimaryLogSource.class));
        dist.registerHandler(recovery);
        var echoes = new ArrayList<String>();
        dist.registerHandler(ChatLineEvent.class, (c, e) -> echoes.add(e.getLine()));
        clock.begin(Instant.now());
        String malformed = "20|2026-09-16T00:00:00Z|40000001|Boss|NOT_HEX|Bad cast|10000001|Player|3|0|0|0|0|0";
        master.pushEventAndWait(new ACTLogLineEvent(malformed));
        master.pushEventAndWait(new ACTLogLineEvent("999|2026-09-16T00:00:00Z|Unsupported|0"));
        master.pushEventAndWait(new ACTLogLineEvent("00|2026-09-16T00:00:01Z|0038|Player|Later valid event|0"));
        Assert.assertEquals(recovery.skipped(), 1);
        Assert.assertEquals(echoes, List.of("Later valid event"));
        clock.resume();
        master.pushEventAndWait(new ACTLogLineEvent(malformed));
        Assert.assertEquals(recovery.skipped(), 1);
    }

    @Test
    public void pullBoundariesInvalidatePendingOutputPermits() {
        var clock = new RecoveryClock();
        var recovery = new PullRecovery(clock, new RecoveryQueue(clock), null, null);
        for (var boundary : List.of(new WipeEvent(), new PullStartedEvent(), new DutyRecommenceEvent(),
                new FadeOutEvent(), new VictoryEvent(), new PullEndedEvent(),
                new ZoneChangeEvent(new XivZone(0x553, "Raid")))) {
            var before = recovery.outputPermit();
            Assert.assertTrue(before.getAsBoolean());
            recovery.handle(null, boundary);
            Assert.assertFalse(before.getAsBoolean());
            Assert.assertTrue(recovery.outputPermit().getAsBoolean());
        }
    }
}
