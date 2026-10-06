package gg.xp.telestosupport;

import gg.xp.reevent.events.EventContext;
import gg.xp.reevent.scan.FilteredEventHandler;
import gg.xp.reevent.scan.HandleEvents;
import gg.xp.services.ServiceDescriptor;
import gg.xp.services.ServiceHandle;
import gg.xp.xivsupport.events.triggers.marks.AutoMarkHandler;
import gg.xp.xivsupport.events.triggers.marks.AutoMarkSlotRequest;
import gg.xp.xivsupport.events.triggers.marks.AutoMarkRequest;
import gg.xp.xivsupport.events.triggers.marks.ClearAutoMarkRequest;
import gg.xp.xivsupport.events.triggers.marks.adv.AutoMarkServiceSelector;
import gg.xp.xivsupport.events.triggers.marks.adv.MarkerSign;
import gg.xp.xivsupport.events.triggers.marks.adv.SpecificAutoMarkSlotRequest;
import gg.xp.xivsupport.events.triggers.marks.adv.SpecificAutoMarkRequest;
import gg.xp.reevent.events.Event;
import gg.xp.xivsupport.persistence.PersistenceProvider;
import gg.xp.xivsupport.persistence.settings.BooleanSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelestoAutoMarkHandler implements FilteredEventHandler {

	private static final Logger log = LoggerFactory.getLogger(TelestoAutoMarkHandler.class);
	private final AutoMarkHandler am;
	private final ServiceHandle handle;
	private final TelestoPartyListHandler party;

	public TelestoAutoMarkHandler(PersistenceProvider pers, AutoMarkHandler am, AutoMarkServiceSelector serv, TelestoPartyListHandler party) {
		this.am = am;
		this.party = party;
		BooleanSetting legacySetting = new BooleanSetting(pers, "auto-marks.use-telesto", false);
		handle = serv.register(ServiceDescriptor.of("telesto-am", "Telesto (Requires Dalamud Plugin)", legacySetting.get() ? 20 : 12));
	}

	@HandleEvents
	public void doAutoMark(EventContext context, AutoMarkSlotRequest event) {
		if (event.isHandled() || !validSlot(event, event.getSlotToMark())) {
			return;
		}
		context.accept(new TelestoGameCommand(String.format("/mk attack <%s>", event.getSlotToMark())));
		event.setHandled();
	}

	@HandleEvents
	public void doSpecificAutoMark(EventContext context, SpecificAutoMarkSlotRequest event) {
		if (event.isHandled() || !validSlot(event, event.getSlotToMark())) {
			return;
		}
		MarkerSign marker = event.getMarker();
		context.accept(new TelestoGameCommand(String.format(
				"/mk %s <%s>",
				marker.getCommand(am.getEffectiveLanguage()),
				event.getSlotToMark())));
		event.setHandled();
	}

	private boolean validSlot(Event event, int slot) {
		if (slot < 1 || slot > 8) {
			return false;
		}
		if (!party.enabled(null)) {
			return true;
		}
		Event parent = event.getParent();
		if (parent instanceof AutoMarkRequest request) {
			return party.matchesSlot(request.getPlayerToMark().getId(), slot);
		}
		if (parent instanceof SpecificAutoMarkRequest request) {
			return party.matchesSlot(request.getPlayerToMark().getId(), slot);
		}
		return true;
	}

	private void clearAutoMark(EventContext context) {
		context.accept(new TelestoGameCommand("/mk clear <1>"));
		context.accept(new TelestoGameCommand("/mk clear <2>"));
		context.accept(new TelestoGameCommand("/mk clear <3>"));
		context.accept(new TelestoGameCommand("/mk clear <4>"));
		context.accept(new TelestoGameCommand("/mk clear <5>"));
		context.accept(new TelestoGameCommand("/mk clear <6>"));
		context.accept(new TelestoGameCommand("/mk clear <7>"));
		context.accept(new TelestoGameCommand("/mk clear <8>"));
	}

	@HandleEvents
//	@LiveOnly
	public void clearMarks(EventContext context, ClearAutoMarkRequest event) {
		if (event.isHandled()) {
			return;
		}
		log.info("Clearing marks");
		clearAutoMark(context);
		event.setHandled();
	}


	@Override
	public boolean enabled(EventContext context) {
		return handle.enabled();
	}
}
