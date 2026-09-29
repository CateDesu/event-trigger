package gg.xp.xivsupport.events.triggers.seq;

import gg.xp.reevent.context.NoOpStateStore;
import gg.xp.reevent.events.BaseEvent;
import gg.xp.reevent.events.BasicEventDistributor;
import gg.xp.reevent.events.BasicEventQueue;
import gg.xp.xivsupport.events.actlines.parsers.FakeTimeSource;
import gg.xp.xivsupport.events.debug.DebugCommand;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

public class SequentialBurstTest {
    private static final class Signal extends BaseEvent {
        final String name;

        Signal(String name) {
            this.name = name;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final FakeTimeSource clock = new FakeTimeSource();
        final BasicEventDistributor distributor = new BasicEventDistributor(NoOpStateStore.INSTANCE);
        final List<String> output = new ArrayList<>();
        final SequentialTrigger<BaseEvent> trigger;

        Fixture(BiConsumer<Signal, SequentialTriggerController<BaseEvent>> body) {
            distributor.setQueue(new BasicEventQueue());
            trigger = SqtTemplates.sq(10_000, Signal.class, e -> e.name.equals("start"), body);
            distributor.registerHandler(BaseEvent.class, trigger::feed);
            distributor.registerHandler(DebugCommand.class, (ctx, e) -> output.add(e.getCommand()));
        }

        void send(String name, long millis) {
            send(name, millis, millis);
        }

        void send(String name, long eventMillis, long clockMillis) {
            clock.setNewTime(Instant.EPOCH.plusMillis(clockMillis));
            Signal event = new Signal(name);
            event.setHappenedAt(Instant.EPOCH.plusMillis(eventMillis));
            event.setTimeSource(clock);
            distributor.acceptEvent(event);
        }

        @Override
        public void close() {
            trigger.stopSilently();
        }
    }

    private static List<Signal> burst(SequentialTriggerController<BaseEvent> controller, int limit) {
        return controller.waitEventsQuickSuccession(limit, Signal.class, e -> e.name.equals("hit"), Duration.ofMillis(200));
    }

    @Test
    public void boundaryIsAvailableToNextWaitExactlyOnce() {
        try (var f = new Fixture((start, s) -> {
            s.accept(new DebugCommand("hits=" + burst(s, 2).size()));
            s.waitEvent(Signal.class, e -> e.name.equals("marker"));
            s.accept(new DebugCommand("first"));
            s.waitEvent(Signal.class, e -> e.name.equals("marker"));
            s.accept(new DebugCommand("second"));
        })) {
            f.send("start", 0);
            f.send("hit", 10);
            f.send("marker", 250);
            Assert.assertEquals(f.output, List.of("hits=1", "first"));
            f.send("marker", 300);
            Assert.assertEquals(f.output, List.of("hits=1", "first", "second"));
        }
    }

    @Test
    public void matchingEventBeyondGapStartsTheNextBurst() {
        try (var f = new Fixture((start, s) -> {
            s.accept(new DebugCommand("first=" + burst(s, 2).size()));
            var second = burst(s, 2);
            s.accept(new DebugCommand("second=" + second.get(0).getHappenedAt().toEpochMilli() + "," + second.size()));
        })) {
            f.send("start", 0);
            f.send("hit", 10);
            f.send("hit", 300);
            f.send("hit", 301);
            Assert.assertEquals(f.output, List.of("first=1", "second=300,2"));
        }
    }

    @Test
    public void slowProcessingDoesNotSplitMatchingEventTimes() {
        try (var f = new Fixture((start, s) -> s.accept(new DebugCommand("hits=" + burst(s, 2).size())))) {
            f.send("start", 0);
            f.send("hit", 10);
            f.send("hit", 10, 500);
            Assert.assertEquals(f.output, List.of("hits=2"));
        }
    }

    @Test
    public void unrelatedWallClockEventDoesNotEndReplayBurst() {
        try (var f = new Fixture((start, s) -> s.accept(new DebugCommand("hits=" + burst(s, 2).size())))) {
            f.send("start", 0);
            f.send("hit", 10);
            f.distributor.acceptEvent(new BaseEvent() {});
            Assert.assertTrue(f.output.isEmpty(), f.output.toString());
            f.send("hit", 10);
            Assert.assertEquals(f.output, List.of("hits=2"));
        }
    }

    @Test
    public void oneEventLimitReturnsWithoutConsumingTheNextEvent() {
        try (var f = new Fixture((start, s) -> {
            s.accept(new DebugCommand("hits=" + burst(s, 1).size()));
            s.waitEvent(Signal.class, e -> e.name.equals("marker"));
            s.accept(new DebugCommand("marker"));
        })) {
            f.send("start", 0);
            f.send("hit", 10);
            Assert.assertEquals(f.output, List.of("hits=1"));
            f.send("marker", 20);
            Assert.assertEquals(f.output, List.of("hits=1", "marker"));
        }
    }

    @Test
    public void zeroLimitDoesNotConsumeAnEvent() {
        try (var f = new Fixture((start, s) -> s.accept(new DebugCommand("hits=" + burst(s, 0).size())))) {
            f.send("start", 0);
            Assert.assertEquals(f.output, List.of("hits=0"));
        }
    }

    @Test
    public void unmatchedBoundaryDoesNotSurviveAnotherWait() {
        try (var f = new Fixture((start, s) -> {
            burst(s, 2);
            s.waitEvent(Signal.class, e -> e.name.equals("later"));
            s.accept(new DebugCommand("later"));
            s.waitEvent(Signal.class, e -> e.name.equals("marker"));
            s.accept(new DebugCommand("marker"));
        })) {
            f.send("start", 0);
            f.send("hit", 10);
            f.send("marker", 250);
            f.send("later", 300);
            Assert.assertEquals(f.output, List.of("later"));
            f.send("marker", 400);
            Assert.assertEquals(f.output, List.of("later", "marker"));
        }
    }

    @Test
    public void delayAfterBurstUsesItsNewDeadline() {
        try (var f = new Fixture((start, s) -> {
            burst(s, 2);
            s.accept(new DebugCommand("burst"));
            s.waitMs(100);
            s.accept(new DebugCommand("delay"));
            s.waitEvent(Signal.class, e -> e.name.equals("marker"));
            s.accept(new DebugCommand("marker"));
        })) {
            f.send("start", 0);
            f.send("hit", 10);
            f.send("marker", 250);
            f.send("tick", 349);
            Assert.assertEquals(f.output, List.of("burst"));
            f.send("tick", 350);
            Assert.assertEquals(f.output, List.of("burst", "delay"));
            f.send("marker", 400);
            Assert.assertEquals(f.output, List.of("burst", "delay", "marker"));
        }
    }

    @Test
    public void fullBurstDoesNotReuseItsLastMatchingEvent() {
        try (var f = new Fixture((start, s) -> {
            burst(s, 2);
            s.accept(new DebugCommand("burst"));
            s.waitEvent(Signal.class, e -> e.name.equals("hit"));
            s.accept(new DebugCommand("next"));
        })) {
            f.send("start", 0);
            f.send("hit", 10);
            f.send("hit", 20);
            Assert.assertEquals(f.output, List.of("burst"));
            f.send("hit", 30);
            Assert.assertEquals(f.output, List.of("burst", "next"));
        }
    }
}
