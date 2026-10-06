package gg.xp.telestosupport;

import gg.xp.reevent.events.BaseEvent;

import java.io.Serial;
import java.util.function.BooleanSupplier;

public class BaseTelestoResponse extends BaseEvent {
	@Serial
	private static final long serialVersionUID = -5724122000114932007L;
	private transient TelestoOutgoingMessage responseTo;
	private transient BooleanSupplier deliveryPermit;

	public void setDeliveryPermit(BooleanSupplier permit) {
		deliveryPermit = permit;
	}

	public boolean isCurrent() {
		return deliveryPermit == null || deliveryPermit.getAsBoolean();
	}

	public void setResponseTo(TelestoOutgoingMessage responseTo) {
		this.responseTo = responseTo;
	}

	public TelestoOutgoingMessage getResponseTo() {
		return responseTo;
	}

	@Override
	public boolean shouldSave() {
		return true;
	}
}
