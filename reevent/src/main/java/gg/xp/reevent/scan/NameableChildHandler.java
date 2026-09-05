package gg.xp.reevent.scan;

import org.jetbrains.annotations.Nullable;

/**
 * A child event handler that can carry a human readable name. AutoChildEventHandler fills this in
 * from the enclosing class and field name when it discovers the handler, so a dead chain can say
 * which one it was instead of only showing a thread name and a stack trace.
 */
public interface NameableChildHandler {

	@Nullable String getHandlerName();

	void setHandlerName(String name);
}
