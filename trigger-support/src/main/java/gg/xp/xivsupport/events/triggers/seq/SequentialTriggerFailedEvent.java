package gg.xp.xivsupport.events.triggers.seq;

import gg.xp.reevent.events.BaseEvent;
import org.jetbrains.annotations.Nullable;

import java.io.Serial;

/**
 * Emitted when a sequential trigger chain dies to an exception or a real timeout. Carries which
 * chain failed and what it was waiting on so dead chains can be surfaced to users instead of only
 * showing up in logs. Not emitted for deliberate stops, force expiry, or chains that outlive a
 * wipe or pull start.
 */
public class SequentialTriggerFailedEvent extends BaseEvent {

	@Serial
	private static final long serialVersionUID = 5382917465029183746L;

	private final @Nullable String triggerName;
	private final @Nullable String pendingWait;
	private final BaseEvent initialEvent;
	private final Throwable error;

	public SequentialTriggerFailedEvent(@Nullable String triggerName, @Nullable String pendingWait, BaseEvent initialEvent, Throwable error) {
		this.triggerName = triggerName;
		this.pendingWait = pendingWait;
		this.initialEvent = initialEvent;
		this.error = error;
	}

	public @Nullable String getTriggerName() {
		return triggerName;
	}

	public @Nullable String getPendingWait() {
		return pendingWait;
	}

	public BaseEvent getInitialEvent() {
		return initialEvent;
	}

	public Throwable getError() {
		return error;
	}

	@Override
	public String toString() {
		return "SequentialTriggerFailedEvent(%s waiting for %s: %s)".formatted(triggerName, pendingWait, error);
	}
}
