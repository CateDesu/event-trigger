package gg.xp.xivsupport.replay;

import gg.xp.xivsupport.events.delaytest.BaseDelayedEvent;
import gg.xp.reevent.events.BaseEvent;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class RecoveryQueueTest {
    private static final class Timer extends BaseDelayedEvent {
        Timer(long delay) { super(delay); }
    }

    @Test
    public void timersFollowRecordedTimeAndKeepEqualDeadlinesInOrder() {
        var clock = new RecoveryClock();
        var queue = new RecoveryQueue(clock);
        var start = Instant.parse("2026-09-15T00:00:00Z");
        clock.begin(start);
        var first = new Timer(1000);
        var second = new Timer(1000);
        queue.push(first);
        queue.push(second);
        queue.advance(start.plusMillis(999));
        Assert.assertEquals(queue.pendingSize(), 0);
        queue.advance(start.plusSeconds(1));
        Assert.assertSame(queue.pull(), first);
        Assert.assertSame(queue.pull(), second);
        var chained = new Timer(500);
        queue.push(chained);
        Assert.assertEquals(queue.nextTimer(), start.plusMillis(1500));
        queue.advance(start.minusSeconds(1));
        Assert.assertEquals(clock.now(), start.plusSeconds(1));
        queue.advance(start.plusMillis(1500));
        Assert.assertSame(queue.pull(), chained);
    }

    @Test
    public void dueTimerPrecedesNewInputAfterLiveClockAdvance() {
        var clock = new RecoveryClock();
        var queue = new RecoveryQueue(clock);
        var start = Instant.now();
        clock.begin(start);
        var timer = new Timer(1000);
        queue.push(timer);
        clock.resume();
        Assert.assertTrue(clock.hold());
        queue.advance(start.plusSeconds(2));
        var input = new BaseEvent() {};
        queue.push(input);
        Assert.assertSame(queue.pull(), timer);
        Assert.assertSame(queue.pull(), input);
        Assert.assertFalse(clock.replaying());
        clock.release();
        Assert.assertFalse(clock.now().isBefore(start.plusSeconds(2)));
    }

    @Test
    public void pendingTimerContinuesAfterTheLiveHandoffWithoutAnotherLogLine() throws Exception {
        var clock = new RecoveryClock();
        var queue = new RecoveryQueue(clock);
        clock.begin(Instant.parse("2026-09-15T00:00:00Z"));
        var timer = new Timer(100);
        queue.push(timer);
        clock.resume();
        Assert.assertSame(CompletableFuture.supplyAsync(queue::pull).get(2, TimeUnit.SECONDS), timer);
        Assert.assertFalse(clock.replaying());
    }
}
