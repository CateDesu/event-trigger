package gg.xp.xivsupport.replay;

import gg.xp.reevent.events.EventDistributor;
import gg.xp.reevent.events.EventMaster;
import gg.xp.reevent.events.InitEvent;
import gg.xp.xivsupport.events.ACTLogLineEvent;
import gg.xp.xivsupport.events.actlines.events.WipeEvent;
import gg.xp.xivsupport.events.actlines.events.ChatLineEvent;
import gg.xp.xivsupport.events.actlines.events.ZoneChangeEvent;
import gg.xp.xivsupport.events.actlines.events.actorcontrol.DutyRecommenceEvent;
import gg.xp.xivsupport.events.misc.pulls.PullStartedEvent;
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
    public void rejectedParserFieldsCountWithoutDiscardingLaterEvents() {
        var pico = XivMain.testingMasterInit();
        var dist = pico.getComponent(EventDistributor.class);
        dist.acceptEvent(new InitEvent());
        var clock = new RecoveryClock();
        var recovery = new PullRecovery(clock, new RecoveryQueue(clock), pico.getComponent(EventMaster.class),
                pico.getComponent(PrimaryLogSource.class));
        dist.registerHandler(recovery);
        var echoes = new ArrayList<String>();
        dist.registerHandler(ChatLineEvent.class, (c, e) -> echoes.add(e.getLine()));
        clock.begin(Instant.now());
        String malformed = "20|2026-09-16T00:00:00Z|40000001|Boss|NOT_HEX|Bad cast|10000001|Player|3|0|0|0|0|0";
        dist.acceptEvent(new ACTLogLineEvent(malformed));
        dist.acceptEvent(new ACTLogLineEvent("999|2026-09-16T00:00:00Z|Unsupported|0"));
        dist.acceptEvent(new ACTLogLineEvent("00|2026-09-16T00:00:01Z|0038|Player|Later valid event|0"));
        Assert.assertEquals(recovery.skipped(), 1);
        Assert.assertEquals(echoes, List.of("Later valid event"));
        clock.resume();
        dist.acceptEvent(new ACTLogLineEvent(malformed));
        Assert.assertEquals(recovery.skipped(), 1);
    }

    @Test
    public void aNewPullInvalidatesPendingOutputPermits() {
        var clock = new RecoveryClock();
        var recovery = new PullRecovery(clock, new RecoveryQueue(clock), null, null);
        for (var boundary : List.of(new WipeEvent(), new PullStartedEvent(), new DutyRecommenceEvent(),
                new ZoneChangeEvent(new XivZone(0x553, "Raid")))) {
            var before = recovery.outputPermit();
            Assert.assertTrue(before.getAsBoolean());
            recovery.handle(null, boundary);
            Assert.assertFalse(before.getAsBoolean());
            Assert.assertTrue(recovery.outputPermit().getAsBoolean());
        }
    }
}
