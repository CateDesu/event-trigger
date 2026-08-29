package gg.xp.xivsupport.events.actlines.parsers;

import gg.xp.reevent.events.Event;
import gg.xp.xivsupport.events.actlines.events.SpawnNpcExtraEvent;
import gg.xp.xivsupport.events.state.XivState;
import gg.xp.xivsupport.models.Position;
import org.jetbrains.annotations.Nullable;
import org.picocontainer.PicoContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;

@SuppressWarnings("unused")
public class Line272Parser extends AbstractACTLineParser<Line272Parser.Fields> {

	private static final Logger log = LoggerFactory.getLogger(Line272Parser.class);

	public Line272Parser(PicoContainer container) {
		super(container, 272, Line272Parser.Fields.class);
	}

	enum Fields {
		id, parentId, tetherId, animationState
	}

	@Override
	protected @Nullable Event convert(FieldMapper<Fields> fields, int lineNumber, ZonedDateTime time) {
		var cbt = fields.getEntityWithParent(Fields.id, Fields.parentId);
		return new SpawnNpcExtraEvent(cbt, (int) fields.getHex(Fields.tetherId), (int) fields.getHex(Fields.animationState));
	}
}
