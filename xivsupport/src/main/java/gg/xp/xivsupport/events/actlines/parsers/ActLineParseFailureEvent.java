package gg.xp.xivsupport.events.actlines.parsers;

import gg.xp.reevent.events.BaseEvent;

/** Reports a rejected ACT line without interrupting later events. */
public final class ActLineParseFailureEvent extends BaseEvent {
    private final int lineNumber;

    public ActLineParseFailureEvent(int lineNumber) {
        this.lineNumber = lineNumber;
    }

    public int getLineNumber() {
        return lineNumber;
    }
}
