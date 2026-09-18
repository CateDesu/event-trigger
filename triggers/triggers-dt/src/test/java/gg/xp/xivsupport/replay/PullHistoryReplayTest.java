package gg.xp.xivsupport.replay;

import gg.xp.reevent.events.EventMaster;
import gg.xp.reevent.events.InitEvent;
import gg.xp.xivsupport.events.ACTLogLineEvent;
import gg.xp.xivsupport.events.state.XivStateImpl;
import gg.xp.xivsupport.models.Position;
import gg.xp.xivsupport.models.XivCombatant;
import gg.xp.xivsupport.sys.KnownLogSource;
import gg.xp.xivsupport.sys.PrimaryLogSource;
import gg.xp.xivsupport.sys.XivMain;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.ref.Reference;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PullHistoryReplayTest {
    private static final long ACTOR = 0x40000003L;

    private static String line(int kind, int second, String data) {
        return kind + "|2026-09-17T12:00:%02dZ|".formatted(second) + data + "|0";
    }

    private static String actor(String id, int second) {
        return line(3, second, id + "|Actor|00|64|0000|00||123|456|100|100|100|100|||120|80|2|0.4");
    }

    private static final String OLD = line(261, 1, "Add|40000003|Type|7|PosX|95|PosY|25|PosZ|12.5|Heading|0");
    private static final String ZONE = line(1, 3, "553|Raid");
    private static final String PLAYER = actor("10000001", 4);
    private static final String CURRENT = actor("40000003", 5);
    private static final String CONFIRM = line(261, 6, "Change|40000003|Radius|2");
    private static final String MOVE = line(271, 2, "40000003|0.4|0|0|120|80|2");
    private static final String WIPE = line(33, 8, "800375AB|40000010|0|0|0|0");
    private static final String ANCHOR = line(0, 9, "0038|Player|Anchor");

    @DataProvider
    public Object[][] histories() {
        return new Object[][] {
                {List.of(OLD, ZONE, PLAYER, CURRENT, CONFIRM), new Position(120, 80, 2, 0.4), 2L},
                {List.of(OLD, ZONE, PLAYER, CURRENT, CONFIRM, WIPE), new Position(120, 80, 2, 0.4), 2L},
                {List.of(OLD, MOVE, ZONE, PLAYER, CONFIRM), new Position(120, 80, 2, 0.4), 7L},
                {List.of(OLD, MOVE, ZONE, PLAYER, CONFIRM, WIPE), new Position(120, 80, 2, 0.4), 7L},
                {List.of(OLD, ZONE, PLAYER, line(271, 5, "40000003|0.4|0|0|120|80|2"), CONFIRM, WIPE),
                        new Position(120, 80, 2, 0.4), 7L},
                {List.of(ZONE, PLAYER, CURRENT, line(261, 6, "Change|40000003|PosX|130|PosY|85"), WIPE),
                        new Position(130, 85, 2, 0.4), 2L},
                {List.of(ZONE, PLAYER, CURRENT, line(261, 6, "Add|40000003|Type|2|PosY|25|PosZ|12.5|Heading|1"), WIPE),
                        new Position(0, 25, 12.5, 1), 2L},
        };
    }

    private XivCombatant replay(List<String> lines) throws Exception {
        var pico = XivMain.testingMasterInit();
        pico.getComponent(PrimaryLogSource.class).setLogSource(KnownLogSource.ACT_LOG_FILE);
        var master = pico.getComponent(EventMaster.class);
        master.pushEventAndWait(new InitEvent());
        var state = pico.getComponent(XivStateImpl.class);
        var graveyard = XivStateImpl.class.getDeclaredField("graveyard");
        graveyard.setAccessible(true);
        for (String raw : lines) {
            var event = new ACTLogLineEvent(raw);
            master.pushEventAndWait(event);
            if (event.getLineNumber() == 1) {
                Assert.assertTrue(state.getCombatants().isEmpty(), "The import handler must clear actors at a zone change");
                for (Object ref : ((Map<?, ?>) graveyard.get(state)).values()) {
                    ((Reference<?>) ref).clear();
                }
            }
        }
        Assert.assertNotNull(state.getCombatant(ACTOR));
        return state.getCombatant(ACTOR);
    }

    @Test(dataProvider = "histories")
    public void selectedHistoryMatchesContinuousReplay(List<String> history, Position expected, long type) throws Exception {
        var folder = Files.createTempDirectory("history-replay-test");
        var file = folder.resolve("Network_test.log");
        try {
            var input = new ArrayList<>(history);
            input.add(ANCHOR);
            Files.write(file, input);
            var selected = new PullHistoryReader().read(folder, ANCHOR, 0x553, 0x10000001);
            Assert.assertEquals(selected.reason(), "");
            Assert.assertEquals(selected.skipped(), 0);
            for (var lines : List.of(history, selected.lines())) {
                var actor = replay(lines);
                Assert.assertEquals(actor.getPos(), expected, lines.toString());
                Assert.assertEquals(actor.getRawType(), type, lines.toString());
            }
        }
        finally {
            Files.deleteIfExists(file);
            Files.delete(folder);
        }
    }
}
