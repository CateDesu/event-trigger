package gg.xp.xivsupport.events.actlines.parsers;

import gg.xp.reevent.events.Event;
import gg.xp.reevent.events.EventContext;
import gg.xp.reevent.scan.HandleEvents;
import gg.xp.xivsupport.events.actlines.events.RawRemoveCombatantEvent;
import gg.xp.xivsupport.events.actlines.events.ZoneChangeEvent;
import gg.xp.xivsupport.events.state.XivState;
import gg.xp.xivsupport.models.Position;
import gg.xp.xivsupport.models.XivCombatant;
import gg.xp.xivsupport.sys.PrimaryLogSource;
import org.picocontainer.PicoContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unused")
public class Line261Parser extends AbstractACTLineParser<Line261Parser.Fields> {

	private static final Logger log = LoggerFactory.getLogger(Line261Parser.class);
	private final XivState state;
	private final PrimaryLogSource source;
	private record ActorPosition(Position position, boolean noncombatant) {}
	private Map<Long, ActorPosition> positions = new HashMap<>();
	private Map<Long, ActorPosition> beforeZone = new HashMap<>();

	public Line261Parser(PicoContainer container, XivState state) {
		super(container, 261, Line261Parser.Fields.class);
		this.state = state;
		this.source = container.getComponent(PrimaryLogSource.class);
	}

	@HandleEvents
	public void zoneChange(EventContext context, ZoneChangeEvent event) {
		beforeZone = source.isActImport() ? positions : new HashMap<>();
		positions = new HashMap<>();
	}

	@HandleEvents
	public void combatantRemoved(EventContext context, RawRemoveCombatantEvent event) {
		forget(event.getEntity().getId());
	}

	private void forget(long id) {
		positions.remove(id);
		beforeZone.remove(id);
	}

	enum Fields {
		updateType, entityId
	}

	enum PosKeys {
		PosX, PosY, PosZ, Heading
	}

	@Override
	protected Event convert(FieldMapper<Fields> fields, int lineNumber, ZonedDateTime time) {
		String updateType = fields.getString(Fields.updateType);
		switch (updateType) {
			case "Remove" -> {
				long entityId = fields.getHex(Fields.entityId);
				forget(entityId);
				state.removeSpecificCombatant(entityId);
			}
			case "Add", "Change" -> {
				// TODO: non-combatants with type==7 do not have 03-lines at all. Thus, we should try to use 261-lines
				// to fill this data.
				Map<PosKeys, Double> pos = new EnumMap<>(PosKeys.class);
				XivCombatant existing = fields.getEntity(Fields.entityId);
				boolean added = "Add".equals(updateType);
				if (added) {
					forget(existing.getId());
				}
				Position existingPos = added ? null : existing.getPos();
				ActorPosition prior = beforeZone.get(existing.getId());
				if (existingPos == null && prior != null && source.isActImport()) {
					// A partial update confirms that the actor survived the zone announcement.
					existingPos = prior.position();
				}
				List<String> raw = fields.getRawLineSplit();
				// Start at first data field, end before hash
				List<String> kvFields = raw.subList(4, raw.size() - 1);
				int type = 0;
				for (int i = 0; i + 1 < kvFields.size(); i += 2) {
					String key = kvFields.get(i);
					String valueRaw = kvFields.get(i + 1);
					// TEMP WORKAROUND - https://github.com/OverlayPlugin/OverlayPlugin/issues/221
					valueRaw = valueRaw.replaceAll(",", ".");
					switch (key) {
						case "PosX" -> pos.put(PosKeys.PosX, Double.parseDouble(valueRaw));
						case "PosY" -> pos.put(PosKeys.PosY, Double.parseDouble(valueRaw));
						case "PosZ" -> pos.put(PosKeys.PosZ, Double.parseDouble(valueRaw));
						case "Heading" -> pos.put(PosKeys.Heading, Double.parseDouble(valueRaw));
						case "Type" -> type = Integer.parseInt(valueRaw);
						case "Radius" -> state.provideCombatantRadius(existing, Float.parseFloat(valueRaw));
						// TODO: this might also be readable from ActorControlExtraEvent category 0x3F
						case "WeaponId" -> state.provideWeaponId(existing, Short.parseShort(valueRaw));
						// As seen in m11s, sometimes these don't work
						case "BNpcID" -> state.provideNpcId(existing, Long.parseLong(valueRaw, 16));
						case "BNpcNameID" -> state.provideNpcNameId(existing, Long.parseLong(valueRaw, 16));
					}
				}
				if (pos.isEmpty() && prior == null) {
					break;
				}
				// Workaround for type-7 (non-combatants) not appearing in ACT 03-lines, thus no raw data existing
				boolean noncombatant = type == 7 || !added && (existing.getRawType() == 7
						|| prior != null && source.isActImport() && prior.noncombatant());
				if (noncombatant) {
					state.provideTypeOverride(existing, 7);
				}
				if (existingPos == null && !(pos.containsKey(PosKeys.PosX) && pos.containsKey(PosKeys.PosY))) {
					log.trace("Incomplete position info for 0x{}", Long.toString(existing.getId(), 16));
					return null;
				}
				Position updated = new Position(
						pos.getOrDefault(PosKeys.PosX, existingPos == null ? 0.0 : existingPos.x()),
						pos.getOrDefault(PosKeys.PosY, existingPos == null ? 0.0 : existingPos.y()),
						pos.getOrDefault(PosKeys.PosZ, existingPos == null ? 0.0 : existingPos.z()),
						pos.getOrDefault(PosKeys.Heading, existingPos == null ? 0.0 : existingPos.heading()));
				state.provideCombatantPos(existing, updated, true);
				beforeZone.remove(existing.getId());
				if (source.isActImport()) {
					positions.put(existing.getId(), new ActorPosition(updated, noncombatant));
				}
			}
		}
		return null;
	}

}
