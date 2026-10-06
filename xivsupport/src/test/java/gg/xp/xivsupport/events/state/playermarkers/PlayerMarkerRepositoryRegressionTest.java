package gg.xp.xivsupport.events.state.playermarkers;

import gg.xp.xivsupport.events.actlines.events.PlayerMarkerPlacedEvent;
import gg.xp.xivsupport.events.actlines.events.PlayerMarkerRemovedEvent;
import gg.xp.xivsupport.events.actlines.events.ZoneChangeEvent;
import gg.xp.xivsupport.events.triggers.marks.adv.MarkerSign;
import gg.xp.xivsupport.models.XivCombatant;
import gg.xp.xivsupport.models.XivZone;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Map;

public class PlayerMarkerRepositoryRegressionTest {

	private static final XivCombatant SOURCE = new XivCombatant(0x10000001, "Source");
	private static final XivCombatant FIRST = new XivCombatant(0x10000002, "First");
	private static final XivCombatant SECOND = new XivCombatant(0x10000003, "Second");

	@Test
	public void placedMarkersRemainObservableAcrossReplacementsAndLateRemovals() {
		PlayerMarkerRepository repo = new PlayerMarkerRepository();
		repo.markerPlaced(null, new PlayerMarkerPlacedEvent(MarkerSign.ATTACK1, SOURCE, FIRST));
		Assert.assertEquals(repo.getMarkers(), Map.of(MarkerSign.ATTACK1, FIRST));
		repo.markerPlaced(null, new PlayerMarkerPlacedEvent(MarkerSign.BIND1, SOURCE, SECOND));
		repo.markerPlaced(null, new PlayerMarkerPlacedEvent(MarkerSign.ATTACK2, SOURCE, FIRST));
		Assert.assertEquals(repo.getMarkers(), Map.of(MarkerSign.ATTACK2, FIRST, MarkerSign.BIND1, SECOND));
		repo.markerRemoved(null, new PlayerMarkerRemovedEvent(MarkerSign.ATTACK1, SOURCE, FIRST));
		Assert.assertEquals(repo.signOnCombatant(FIRST), MarkerSign.ATTACK2);
		repo.markerPlaced(null, new PlayerMarkerPlacedEvent(MarkerSign.ATTACK2, SOURCE, SECOND));
		repo.markerRemoved(null, new PlayerMarkerRemovedEvent(MarkerSign.ATTACK2, SOURCE, FIRST));
		Assert.assertEquals(repo.getMarkers(), Map.of(MarkerSign.ATTACK2, SECOND));
		repo.markerPlaced(null, new PlayerMarkerPlacedEvent(MarkerSign.ATTACK2, SOURCE, SECOND));
		Assert.assertEquals(repo.getMarkers(), Map.of(MarkerSign.ATTACK2, SECOND));
		repo.markerRemoved(null, new PlayerMarkerRemovedEvent(MarkerSign.ATTACK2, SOURCE, SECOND));
		Assert.assertTrue(repo.getMarkers().isEmpty());
		repo.markerPlaced(null, new PlayerMarkerPlacedEvent(MarkerSign.ATTACK1, SOURCE, FIRST));
		repo.zoneChanged(null, new ZoneChangeEvent(new XivZone(999, "Other zone")));
		Assert.assertTrue(repo.getMarkers().isEmpty());
	}
}
