package gg.xp.xivsupport.events.actlines.events;

import gg.xp.reevent.events.BaseEvent;
import gg.xp.xivsupport.models.XivCombatant;

public class SpawnNpcExtraEvent extends BaseEvent implements HasTargetEntity {
	private final XivCombatant target;
	private final int tetherId;
	private final int animationState;

	public SpawnNpcExtraEvent(XivCombatant target, int tetherId, int animationState) {
		this.target = target;
		this.tetherId = tetherId;
		this.animationState = animationState;
	}

	@Override
	public XivCombatant getTarget() {
		return target;
	}

	public int getTetherId() {
		return tetherId;
	}

	public int getAnimationState() {
		return animationState;
	}
}
