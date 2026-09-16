package gg.xp.xivsupport.events.ws;

import gg.xp.reevent.events.EventMaster;
import gg.xp.reevent.events.InitEvent;
import gg.xp.xivsupport.events.actlines.events.BuffApplied;
import gg.xp.xivsupport.events.actlines.events.WipeEvent;
import gg.xp.xivsupport.events.actlines.events.ZoneChangeEvent;
import gg.xp.xivsupport.events.state.XivState;
import gg.xp.xivsupport.events.state.combatstate.StatusEffectRepository;
import gg.xp.xivsupport.models.XivStatusEffect;
import gg.xp.xivsupport.models.XivZone;
import gg.xp.xivsupport.sys.XivMain;
import org.testng.Assert;
import org.testng.annotations.Test;

public class WsStateRecoveryTest {
    private static final String PLAYER = "{\"ID\":268435457,\"Name\":\"Player\",\"Type\":1,\"Job\":21}";
    private static final String BOSS = "{\"ID\":1073741825,\"Name\":\"Boss\",\"Type\":2,\"PosX\":100,\"PosY\":90}";

    private static final class Engine {
        final EventMaster master;
        final XivState state;
        final StatusEffectRepository buffs;

        Engine() {
            var pico = XivMain.testingMasterInit();
            master = pico.getComponent(EventMaster.class);
            master.pushEventAndWait(new InitEvent());
            state = pico.getComponent(XivState.class);
            buffs = pico.getComponent(StatusEffectRepository.class);
            zone(129);
            feed("{\"type\":\"ChangePrimaryPlayer\",\"charID\":268435457,\"charName\":\"Player\"}");
            snapshot(true, PLAYER, BOSS);
        }

        void feed(String json) {
            master.pushEventAndWait(new ActWsRawMsg(json));
        }

        void zone(int id) {
            feed("{\"type\":\"ChangeZone\",\"zoneID\":" + id + ",\"zoneName\":\"Test\"}");
        }

        void snapshot(boolean full, String... actors) {
            feed("{\"rseq\":\"" + (full ? "allCombatants" : "specificCombatants")
                    + "\",\"combatants\":[" + String.join(",", actors) + "]}");
        }

        void buff() {
            master.pushEventAndWait(new BuffApplied(new XivStatusEffect(123), 30,
                    state.getPlayer(), state.getPlayer(), 0));
            Assert.assertEquals(buffs.getBuffs().size(), 1);
        }
    }

    @Test
    public void repeatedAnnouncementsPreserveBuffsButTransitionsAndWipesClearThem() {
        var engine = new Engine();
        engine.buff();
        engine.zone(129);
        Assert.assertEquals(engine.buffs.getBuffs().size(), 1);
        engine.zone(130);
        Assert.assertTrue(engine.buffs.getBuffs().isEmpty());
        engine.zone(129);
        engine.buff();
        engine.master.pushEventAndWait(new ZoneChangeEvent(new XivZone(129, "New instance")));
        Assert.assertTrue(engine.buffs.getBuffs().isEmpty());
        engine.buff();
        engine.master.pushEventAndWait(new WipeEvent());
        Assert.assertTrue(engine.buffs.getBuffs().isEmpty());
    }

    @Test
    public void fullSnapshotsRemoveMissingActorsAndAcceptEmptyLists() {
        var engine = new Engine();
        engine.snapshot(true, PLAYER);
        Assert.assertFalse(engine.state.getCombatants().containsKey(1073741825L));
        Assert.assertTrue(engine.state.getCombatants().containsKey(268435457L));
        engine.snapshot(true);
        Assert.assertTrue(engine.state.getCombatants().isEmpty());
        engine.snapshot(true, PLAYER, BOSS);
        Assert.assertEquals(engine.state.getCombatants().size(), 2);
    }

    @Test
    public void partialSnapshotsPreserveOtherActorsAndRestoreAnUnchangedActor() {
        var engine = new Engine();
        engine.snapshot(false, PLAYER);
        Assert.assertEquals(engine.state.getCombatants().size(), 2);
        engine.state.removeSpecificCombatant(1073741825L);
        engine.snapshot(false, BOSS);
        Assert.assertEquals(engine.state.getCombatants().size(), 2);
        Assert.assertEquals(engine.state.getCombatant(1073741825L).getPos().x(), 100.0);
    }
}
