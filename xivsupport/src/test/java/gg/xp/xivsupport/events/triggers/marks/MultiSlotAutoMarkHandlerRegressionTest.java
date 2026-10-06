package gg.xp.xivsupport.events.triggers.marks;

import gg.xp.reevent.events.Event;
import gg.xp.xivdata.data.Job;
import gg.xp.xivsupport.events.triggers.marks.adv.MarkerSign;
import gg.xp.xivsupport.events.triggers.marks.adv.MultiSlotAutoMarkHandler;
import gg.xp.xivsupport.events.triggers.marks.adv.SpecificAutoMarkRequest;
import gg.xp.xivsupport.models.XivPlayerCharacter;
import gg.xp.xivsupport.models.XivWorld;
import gg.xp.xivsupport.persistence.InMemoryMapPersistenceProvider;
import gg.xp.xivsupport.persistence.settings.MultiSlotAutomarkSetting;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MultiSlotAutoMarkHandlerRegressionTest {

	private enum Assignment { FIRST }

	private static XivPlayerCharacter player(int index) {
		return new XivPlayerCharacter(0x10000001L + index, "Player" + index, Job.WHM,
				XivWorld.unknown(), false, 1, null, null, null, 0, 0, 1, 100, 0, 0);
	}

	@Test
	public void repeatedAssignmentsClearOnlyTheDistinctOwnedActors() {
		List<Event> events = new ArrayList<>();
		MultiSlotAutomarkSetting<Assignment> setting = new MultiSlotAutomarkSetting<>(
				new InMemoryMapPersistenceProvider(), "test.marks", Assignment.class,
				Map.of(Assignment.FIRST, MarkerSign.ATTACK1));
		MultiSlotAutoMarkHandler<Assignment> handler = new MultiSlotAutoMarkHandler<>(events::add, setting);
		for (int i = 0; i < 8; i++) { handler.process(Assignment.FIRST, player(0)); }
		handler.process(Assignment.FIRST, player(1));
		events.clear();
		handler.clearAll();
		Assert.assertEquals(events.size(), 2, "Repeated assignments cannot become a global clear");
		Assert.assertTrue(events.stream().allMatch(SpecificAutoMarkRequest.class::isInstance));
		List<SpecificAutoMarkRequest> clears = events.stream().map(SpecificAutoMarkRequest.class::cast).toList();
		Assert.assertEquals(clears.stream().map(SpecificAutoMarkRequest::getPlayerToMark).toList(),
				List.of(player(0), player(1)));
		Assert.assertTrue(clears.stream().allMatch(mark -> mark.getMarker() == MarkerSign.CLEAR));
		events.clear();
		handler.clearAll();
		Assert.assertTrue(events.isEmpty(), "Repeated clear cannot repeat actions");
	}
}
