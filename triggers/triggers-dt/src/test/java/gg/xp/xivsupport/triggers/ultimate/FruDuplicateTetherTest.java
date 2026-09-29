package gg.xp.xivsupport.triggers.ultimate;

import gg.xp.xivsupport.events.ACTLogLineEvent;
import gg.xp.xivsupport.events.triggers.util.ReplayFaults;
import gg.xp.xivsupport.eventstorage.EventReader;
import gg.xp.xivsupport.gui.imprt.EventIterator;

public class FruDuplicateTetherTest extends FRUTest {
	@Override
	protected EventIterator<ACTLogLineEvent> getEvents() {
		return ReplayFaults.duplicate(EventReader.readActLogResource(getFileName()),
				event -> event.getLineNumber() == 35 && event.getRawFields()[8].equals("0086")
						&& event.getRawFields()[2].equals("40010650"), 1);
	}
}
