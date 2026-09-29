package gg.xp.xivsupport.triggers.Arcadion;

import gg.xp.xivsupport.events.ACTLogLineEvent;
import gg.xp.xivsupport.events.triggers.util.ReplayFaults;
import gg.xp.xivsupport.eventstorage.EventReader;
import gg.xp.xivsupport.gui.imprt.EventIterator;

public class M1sDuplicateCastTest extends M1sTest {
	@Override
	protected EventIterator<ACTLogLineEvent> getEvents() {
		return ReplayFaults.duplicate(EventReader.readActLogResource(getFileName()),
				event -> event.getLineNumber() == 20 && event.getRawFields()[4].equals("9446")
						&& event.getRawFields()[1].equals("2024-07-30T18:15:12.6810000-05:00"), 1);
	}
}
