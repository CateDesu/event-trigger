package gg.xp.xivsupport.events.actlines;

import gg.xp.reevent.events.EventDistributor;
import gg.xp.reevent.events.InitEvent;
import gg.xp.xivsupport.events.ACTLogLineEvent;
import gg.xp.xivsupport.events.actlines.events.ZoneChangeEvent;
import gg.xp.xivsupport.events.state.XivStateImpl;
import gg.xp.xivsupport.models.Position;
import gg.xp.xivsupport.models.XivZone;
import gg.xp.xivsupport.sys.KnownLogSource;
import gg.xp.xivsupport.sys.PrimaryLogSource;
import gg.xp.xivsupport.sys.XivMain;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.lang.ref.Reference;
import java.util.List;
import java.util.Map;

public class Line261RecoveryTest {
    private static final long ACTOR = 0x40000003L;

    private static final class Engine {
        final EventDistributor dist;
        final XivStateImpl state;
        final PrimaryLogSource source;

        Engine() {
            var pico = XivMain.testingMasterInit();
            dist = pico.getComponent(EventDistributor.class);
            dist.acceptEvent(new InitEvent());
            state = pico.getComponent(XivStateImpl.class);
            source = pico.getComponent(PrimaryLogSource.class);
            source.setLogSource(KnownLogSource.ACT_LOG_FILE);
            line("Add|40000003|Type|7|PosX|95|PosY|25|PosZ|12.5|Heading|0");
        }

        void line(String data) {
            dist.acceptEvent(new ACTLogLineEvent("261|2026-09-16T00:00:00Z|" + data + "|0"));
        }

        void zone() throws Exception {
            dist.acceptEvent(new ZoneChangeEvent(new XivZone(0x553, "Test")));
            state.setCombatants(List.of());
            // Reproduce collection of the weak references left by the import reset.
            var field = XivStateImpl.class.getDeclaredField("graveyard");
            field.setAccessible(true);
            for (Object value : ((Map<?, ?>) field.get(state)).values()) {
                ((Reference<?>) value).clear();
            }
            Assert.assertNull(state.getCombatant(ACTOR));
        }

        void assertNoPosition() {
            var actor = state.getCombatant(ACTOR);
            Assert.assertTrue(actor == null || actor.getPos() == null);
        }
    }

    @Test
    public void confirmedActorRetainsPositionAcrossTheImportZoneReset() throws Exception {
        var engine = new Engine();
        engine.zone();
        engine.line("Change|40000003|Heading|1");
        Assert.assertEquals(engine.state.getCombatant(ACTOR).getPos(), new Position(95, 25, 12.5, 1));
        Assert.assertEquals(engine.state.getCombatant(ACTOR).getRawType(), 7);
    }

    @Test
    public void newerNetworkPositionSurvivesTheImportZoneReset() throws Exception {
        var engine = new Engine();
        engine.dist.acceptEvent(new ACTLogLineEvent("271|2026-09-16T00:00:01Z|40000003|0.4|0|0|120|80|2|0"));
        engine.zone();
        engine.line("Change|40000003|Radius|2");
        Assert.assertEquals(engine.state.getCombatant(ACTOR).getPos(), new Position(120, 80, 2, 0.4));
    }

    @Test
    public void actorWithoutAFullSnapshotRetainsTheLatestPosition() throws Exception {
        var engine = new Engine();
        engine.line("Remove|40000003");
        engine.line("Add|40000003|Type|2|PosX|95|PosY|25");
        Assert.assertFalse(engine.state.getCombatants().containsKey(ACTOR));
        engine.dist.acceptEvent(new ACTLogLineEvent("271|2026-09-16T00:00:01Z|40000003|0.4|0|0|120|80|2|0"));
        engine.zone();
        engine.line("Change|40000003|Radius|2");
        Assert.assertEquals(engine.state.getCombatant(ACTOR).getPos(), new Position(120, 80, 2, 0.4));
    }

    @Test
    public void addDefaultsOmittedCoordinatesWithoutInheritingOldValues() {
        var engine = new Engine();
        engine.line("Add|40000003|Type|7|PosY|25|PosZ|12.5|Heading|1");
        Assert.assertEquals(engine.state.getCombatant(ACTOR).getPos(), new Position(0, 25, 12.5, 1));
        engine.line("Add|40000003|Type|7|PosX|120");
        Assert.assertEquals(engine.state.getCombatant(ACTOR).getPos(), new Position(120, 0, 0, 0));
        engine.line("Add|40000003|Type|7");
        Assert.assertEquals(engine.state.getCombatant(ACTOR).getPos(), new Position(0, 0, 0, 0));
    }

    @Test
    public void newAddStartsFreshPositionData() throws Exception {
        var engine = new Engine();
        engine.zone();
        engine.line("Add|40000003|Type|7|PosX|110|PosY|120");
        Assert.assertEquals(engine.state.getCombatant(ACTOR).getPos(), new Position(110, 120, 0, 0));
    }

    @Test
    public void removedAndUnconfirmedActorsCannotUseThePreviousPosition() throws Exception {
        var removed = new Engine();
        removed.zone();
        removed.line("Remove|40000003");
        removed.line("Change|40000003|Heading|1");
        removed.assertNoPosition();
        var unconfirmed = new Engine();
        unconfirmed.zone();
        unconfirmed.zone();
        unconfirmed.line("Change|40000003|Heading|1");
        unconfirmed.assertNoPosition();
    }

    @Test
    public void liveZoneSnapshotsRemainAuthoritative() throws Exception {
        var engine = new Engine();
        engine.source.setLogSource(KnownLogSource.WEBSOCKET_LIVE);
        engine.zone();
        engine.line("Change|40000003|Heading|1");
        engine.assertNoPosition();
    }
}
