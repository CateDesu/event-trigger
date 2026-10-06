package gg.xp.telestosupport;

import gg.xp.reevent.events.EventContext;
import gg.xp.reevent.scan.FilteredEventHandler;
import gg.xp.reevent.scan.HandleEvents;
import gg.xp.xivsupport.events.state.PartyForceOrderChangeEvent;
import gg.xp.xivsupport.events.state.PartyChangeEvent;
import gg.xp.xivsupport.events.actlines.events.ZoneChangeEvent;
import gg.xp.xivsupport.persistence.settings.BooleanSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static gg.xp.telestosupport.TelestoMain.PARTY_UPDATE_ID;

public class TelestoPartyListHandler implements FilteredEventHandler {

	private static final Logger log = LoggerFactory.getLogger(TelestoPartyListHandler.class);

	private final BooleanSetting enablePartyList;
	private volatile List<Long> partyActorIds = List.of();

	public TelestoPartyListHandler(TelestoMain main) {
		enablePartyList = main.getEnablePartyList();
		enablePartyList.addListener(() -> partyActorIds = List.of());
		main.getUriSetting().addListener(() -> partyActorIds = List.of());
	}

	public boolean matchesSlot(long actor, int slot) {
		List<Long> actors = partyActorIds;
		return slot >= 1 && slot <= actors.size() && actors.get(slot - 1) == actor;
	}

	@HandleEvents(order = Integer.MIN_VALUE + 1)
	public void partyChanged(EventContext context, PartyChangeEvent event) {
		if (event.isMarkerRosterChanged()) {
			partyActorIds = List.of();
		}
	}

	@HandleEvents(order = Integer.MIN_VALUE + 1)
	public void zoneChanged(EventContext context, ZoneChangeEvent event) {
		partyActorIds = List.of();
	}

	@Override
	public boolean enabled(EventContext context) {
		return enablePartyList.get();
	}

	@HandleEvents
	public void handlePartyReponse(EventContext context, TelestoResponse event) {
		if (event.getId() == PARTY_UPDATE_ID && event.isCurrent()) {
			log.trace("Received Telesto party list");
			List<Long> previous = this.partyActorIds;
			this.partyActorIds = List.of();
			List<Long> partyActorIds = new ArrayList<>();
			try {
				if (!(event.getResponse() instanceof List<?> partyData)) {
					throw new IllegalArgumentException("Expected a Telesto party list");
				}
				List<Map<?, ?>> ordered = partyData.stream().<Map<?, ?>>map(entry -> {
					if (!(entry instanceof Map<?, ?> member)) {
						throw new IllegalArgumentException("Expected a Telesto party member");
					}
					return member;
				}).sorted(Comparator.comparing(entry -> Integer.parseInt(entry.get("order").toString(), 16))).toList();
				for (Map<?, ?> entry : ordered) {
					int slot = Integer.parseInt(entry.get("order").toString(), 16);
					long actor = Long.parseLong(entry.get("actor").toString(), 16);
					if (slot != partyActorIds.size() + 1 || slot > 8 || actor == 0
							|| actor == 0xE0000000L || partyActorIds.contains(actor)) {
						partyActorIds.clear();
						break;
					}
					partyActorIds.add(actor);
				}
			}
			catch (RuntimeException error) {
				partyActorIds.clear();
				log.warn("Invalid Telesto party list", error);
			}
			this.partyActorIds = List.copyOf(partyActorIds);
			if (!Objects.equals(previous, partyActorIds)) {
				log.info("New Telesto Party List: {}", partyActorIds);
				context.accept(new PartyForceOrderChangeEvent(partyActorIds.isEmpty() ? null : partyActorIds));
			}
			else {
				log.trace("Ignored Telesto party list update because it is identical to the previous.");
			}
		}
	}
}
