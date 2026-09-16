package gg.xp.xivsupport.replay;

import gg.xp.reevent.events.BaseEvent;
import gg.xp.reevent.events.Event;
import gg.xp.reevent.events.EventContext;
import gg.xp.reevent.events.EventHandler;
import gg.xp.reevent.events.EventMaster;
import gg.xp.xivsupport.events.ACTLogLineEvent;
import gg.xp.xivsupport.events.actlines.events.WipeEvent;
import gg.xp.xivsupport.events.actlines.events.ZoneChangeEvent;
import gg.xp.xivsupport.events.actlines.events.actorcontrol.DutyCommenceEvent;
import gg.xp.xivsupport.events.actlines.parsers.ActLineParseFailureEvent;
import gg.xp.xivsupport.events.misc.pulls.PullStartedEvent;
import gg.xp.xivsupport.events.delaytest.BaseDelayedEvent;
import gg.xp.xivsupport.events.ws.ActWsRawMsg;
import gg.xp.xivsupport.sys.KnownLogSource;
import gg.xp.xivsupport.sys.PrimaryLogSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Restores trigger waits using event time before resuming the live feed. */
public final class PullRecovery implements EventHandler<Event> {
    private static final class Tick extends BaseEvent {}
    public final RecoveryClock clock;
    private final RecoveryQueue queue;
    private final EventMaster master;
    private final PrimaryLogSource source;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile Instant inputTime;
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicLong outputGeneration = new AtomicLong();

    public PullRecovery(RecoveryClock clock, RecoveryQueue queue, EventMaster master, PrimaryLogSource source) {
        this.clock = clock;
        this.queue = queue;
        this.master = master;
        this.source = source;
    }

    public void begin(String timestamp) {
        master.pushEventAndWait(new Tick());
        clock.begin(Instant.parse(timestamp));
        cancelPendingOutput();
        skipped.set(0);
        source.setLogSource(KnownLogSource.ACT_LOG_FILE);
    }

    /** Use a fresh engine and keep host output and automarks muted until end. */
    public PullHistoryReader.Result restore(Path folder, String anchor, long zone, long player,
                                             List<String> snapshots, Instant fallback) throws InterruptedException {
        PullHistoryReader.Result result;
        try {
            var reader = new PullHistoryReader();
            result = reader.read(folder, anchor, zone, player);
            for (int retry = 0; retry < 6 && PullHistoryReader.NOT_FLUSHED.equals(result.reason()); retry++) {
                Thread.sleep(500);
                result = reader.read(folder, anchor, zone, player);
            }
        }
        catch (IOException error) {
            result = new PullHistoryReader.Result(List.of(), error.toString());
        }
        begin((result.lines().isEmpty() ? fallback : result.start()).toString());
        skipped.addAndGet(result.skipped());
        for (String snapshot : snapshots) {
            try {
                feed(snapshot, mapper.readTree(snapshot));
            }
            catch (RuntimeException error) {
                recordFailure();
            }
        }
        for (String line : result.lines()) {
            try {
                String raw = mapper.writeValueAsString(Map.of("type", "LogLine", "rawLine", line));
                feed(raw, mapper.readTree(raw));
            }
            catch (RuntimeException error) {
                recordFailure();
            }
        }
        return new PullHistoryReader.Result(result.lines(), result.reason(), skipped());
    }

    public void end() {
        master.pushEventAndWait(new Tick());
        clock.resume();
        queue.advance(clock.now());
        source.setLogSource(KnownLogSource.WEBSOCKET_LIVE);
    }

    public void advance(Instant time) {
        boolean ticking = clock.hold();
        try {
            if (ticking) {
                master.pushEventAndWait(new Tick());
            }
            Instant next;
            while ((next = queue.nextTimer()) != null && !next.isAfter(time)) {
                queue.advance(next);
                master.pushEventAndWait(new Tick());
            }
            queue.advance(time);
        }
        finally {
            if (ticking) {
                clock.release();
            }
        }
    }

    public void feed(String raw, JsonNode frame) {
        try {
            if ("LogLine".equals(frame.path("type").asText(""))) {
                String[] parts = frame.path("rawLine").asText("").split("\\|", 3);
                if (parts.length >= 3) {
                    inputTime = ZonedDateTime.parse(parts[1]).toInstant();
                    advance(inputTime);
                }
            }
            master.pushEventAndWait(new ActWsRawMsg(raw));
        }
        finally {
            inputTime = null;
        }
    }

    public boolean outputAllowed() {
        Instant cutoff = Instant.now().minusSeconds(3);
        Instant input = inputTime;
        return !clock.replaying() && !clock.now().isBefore(cutoff)
                && (input == null || !input.isBefore(cutoff));
    }

    public void recordFailure() {
        if (clock.replaying()) {
            skipped.incrementAndGet();
        }
    }

    public int skipped() {
        return skipped.get();
    }

    public void cancelPendingOutput() {
        outputGeneration.incrementAndGet();
    }

    /** A queued output may wait for its configured delay within the same pull. */
    public BooleanSupplier outputPermit() {
        long generation = outputGeneration.get();
        return () -> !clock.replaying() && outputGeneration.get() == generation;
    }

    @Override
    public int getOrder() {
        return Integer.MIN_VALUE;
    }

    @Override
    public void handle(EventContext context, Event event) {
        if (event instanceof ActLineParseFailureEvent) {
            recordFailure();
        }
        if (event instanceof ZoneChangeEvent || event instanceof WipeEvent
                || event instanceof DutyCommenceEvent || event instanceof PullStartedEvent) {
            cancelPendingOutput();
        }
        if (event instanceof BaseEvent base) {
            base.setTimeSource(clock);
            if (!(event instanceof ACTLogLineEvent)
                    && (event.getParent() == null || event instanceof BaseDelayedEvent)) {
                base.setHappenedAt(clock.now());
            }
        }
    }
}
