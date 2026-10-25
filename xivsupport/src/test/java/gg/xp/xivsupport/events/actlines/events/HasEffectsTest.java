package gg.xp.xivsupport.events.actlines.events;

import gg.xp.xivsupport.events.actlines.events.abilityeffect.DamageTakenEffect;
import gg.xp.xivsupport.events.actlines.events.abilityeffect.HitSeverity;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

public class HasEffectsTest {
	@Test
	public void keepsDamageAboveTheIntegerRange() {
		HasEffects effects = () -> List.of(
				new DamageTakenEffect(HitSeverity.NORMAL, Integer.MAX_VALUE),
				new DamageTakenEffect(HitSeverity.NORMAL, 7));
		Assert.assertEquals(effects.getDamage(), (long) Integer.MAX_VALUE + 7);
	}
}
