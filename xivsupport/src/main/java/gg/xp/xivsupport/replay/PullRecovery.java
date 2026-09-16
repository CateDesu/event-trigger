package gg.xp.xivsupport.replay;

import gg.xp.reevent.events.BaseEvent;
import gg.xp.reevent.events.Event;
import gg.xp.reevent.events.EventContext;
import gg.xp.reevent.events.EventHandler;
import gg.xp.reevent.events.EventMaster;
import gg.xp.xivsupport.events.ACTLogLineEvent;
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

/** Restores trigger waits using event time before resuming the live feed. */
public final class PullRecovery implements EventHandler<Event> {
    private static final class Tick extends BaseEvent {}
    public final RecoveryClock clock;
    private final RecoveryQueue queue;
    private final EventMaster master;
    private final PrimaryLogSource source;
    private final ObjectMapper mapper = new ObjectMapper();

    public PullRecovery(RecoveryClock clock, RecoveryQueue queue, EventMaster master, PrimaryLogSource source) {
        this.clock = clock;
        this.queue = queue;
        this.master = master;
        this.source = source;
    }

    public void begin(String timestamp) {
        master.pushEventAndWait(new Tick());
        clock.begin(Instant.parse(timestamp));
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
        for (String snapshot : snapshots) {
            feed(snapshot, mapper.readTree(snapshot));
        }
        for (String line : result.lines()) {
            String raw = mapper.writeValueAsString(Map.of("type", "LogLine", "rawLine", line));
            feed(raw, mapper.readTree(raw));
        }
        return result;
    }

    public void end() {
        master.pushEventAndWait(new Tick());
        clock.resume();
        queue.advance(clock.now());
        source.setLogSource(KnownLogSource.WEBSOCKET_LIVE);
    }

    public void advance(Instant time) {
        if (!clock.replaying()) {
            clock.follow(time);
            return;
        }
        Instant next;
        while ((next = queue.nextTimer()) != null && !next.isAfter(time)) {
            queue.advance(next);
            master.pushEventAndWait(new Tick());
        }
        queue.advance(time);
    }

    public void feed(String raw, JsonNode frame) {
        if ("LogLine".equals(frame.path("type").asText(""))) {
            String[] parts = frame.path("rawLine").asText("").split("\\|", 3);
            if (parts.length >= 3) {
                advance(ZonedDateTime.parse(parts[1]).toInstant());
            }
        }
        master.pushEventAndWait(new ActWsRawMsg(raw));
    }

    @Override
    public int getOrder() {
        return Integer.MIN_VALUE;
    }

    @Override
    public void handle(EventContext context, Event event) {
        if (event instanceof BaseEvent base) {
            base.setTimeSource(clock);
            if (!(event instanceof ACTLogLineEvent)
                    && (event.getParent() == null || event instanceof BaseDelayedEvent)) {
                base.setHappenedAt(clock.now());
            }
        }
    }
}
