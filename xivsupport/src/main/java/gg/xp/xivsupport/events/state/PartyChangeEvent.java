package gg.xp.xivsupport.events.state;

import gg.xp.reevent.events.BaseEvent;

import java.io.Serial;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PartyChangeEvent extends BaseEvent {
	@Serial
	private static final long serialVersionUID = -9103783238842156824L;
	private final List<RawXivPartyInfo> members;
	private boolean markerRosterChanged = true;

	public PartyChangeEvent(List<RawXivPartyInfo> members) {
		this.members = new ArrayList<>(members);
	}

	public List<RawXivPartyInfo> getMembers() {
		return Collections.unmodifiableList(members);
	}

	public boolean isMarkerRosterChanged() {
		return markerRosterChanged;
	}

	void setMarkerRosterChanged(boolean changed) {
		markerRosterChanged = changed;
	}
}
