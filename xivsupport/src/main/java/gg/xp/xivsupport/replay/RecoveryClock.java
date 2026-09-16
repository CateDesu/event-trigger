package gg.xp.xivsupport.replay;

import gg.xp.xivsupport.events.actlines.parsers.FakeACTTimeSource;
import java.time.Instant;

public final class RecoveryClock extends FakeACTTimeSource {
    private Instant time = Instant.now();
    private long tick = System.nanoTime();
    private boolean replay;
    private boolean ticking = true;

    @Override
    public synchronized Instant now() {
        return ticking ? time.plusNanos(System.nanoTime() - tick) : time;
    }

    public synchronized boolean replaying() {
        return replay;
    }

    public synchronized void begin(Instant start) {
        time = start;
        replay = true;
        ticking = false;
    }

    public synchronized void advance(Instant next) {
        if (ticking) {
            time = now();
            tick = System.nanoTime();
        }
        if (next.isAfter(time)) {
            time = next;
        }
    }

    public synchronized void resume() {
        tick = System.nanoTime();
        replay = false;
        ticking = true;
    }

    public synchronized boolean hold() {
        boolean wasTicking = ticking;
        time = now();
        ticking = false;
        return wasTicking;
    }

    public synchronized void release() {
        tick = System.nanoTime();
        ticking = true;
    }

    @Override
    public void setNewTime(Instant ignored) {
        // The feed owns the clock so individual parsers cannot rewind it.
    }
}
