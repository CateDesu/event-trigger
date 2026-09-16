package gg.xp.xivsupport.replay;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.util.List;

public class PullHistoryReaderTest {
    private static String line(int kind, int second, String fields) {
        return "%02d|2026-09-15T21:00:%02d.000-05:00|%s|0".formatted(kind, second, fields);
    }

    private static final String ZONE = line(1, 2, "553|Raid");
    private static final String PLAYER = line(3, 3, "10000001|Player");
    private static final String WIPE = line(33, 10, "800375AB|40000010|0|0|0|0");
    private static final String ANCHOR = line(20, 15, "40000001|Boss|1234");
    private static final String OBJECT = line(261, 1, "Add|40000003|Type|7|PosX|95|PosY|25|PosZ|12.5|Heading|0");

    private PullHistoryReader.Result read(String... lines) throws Exception {
        return read(new PullHistoryReader(), lines);
    }

    private PullHistoryReader.Result read(PullHistoryReader reader, String... lines) throws Exception {
        var folder = Files.createTempDirectory("pull-history-test");
        var file = folder.resolve("Network_test.log");
        try {
            Files.write(file, List.of(lines));
            return reader.read(folder, ANCHOR, 0x553, 0x10000001);
        }
        finally {
            Files.deleteIfExists(file);
            Files.delete(folder);
        }
    }

    @Test
    public void skipsMalformedTimestampsAndKeepsLaterHistory() throws Exception {
        String later = line(0, 12, "0038|Player|Valid event");
        var result = read(ZONE, PLAYER, "00|invalid|0038|Player|Damaged event|0", later, ANCHOR);
        Assert.assertEquals(result.lines(), List.of(ZONE, PLAYER, later));
        Assert.assertEquals(result.skipped(), 1);
        Assert.assertEquals(result.reason(), "");
        Assert.assertEquals(result.start().toString(), "2026-09-16T02:00:02Z");
    }

    @Test
    public void restoresPreAnnouncementPositionOnlyWhenTheActorIsSeenAgain() throws Exception {
        String change = line(261, 4, "Change|40000003|Heading|1");
        var result = read(OBJECT, ZONE, PLAYER, change, ANCHOR);
        Assert.assertEquals(result.reason(), "");
        Assert.assertEquals(result.lines().get(0), ZONE);
        Assert.assertTrue(result.lines().get(1).contains("|Add|40000003|Type|7|PosX|95|PosY|25|"));
        Assert.assertEquals(result.lines().subList(2, result.lines().size()), List.of(PLAYER, change));
        Assert.assertFalse(read(OBJECT, ZONE, PLAYER, ANCHOR).lines().stream().anyMatch(s -> s.contains("40000003")));
    }

    @Test
    public void wipeSeedsMergedPositionsAndDropsEarlierMechanics() throws Exception {
        var result = read(OBJECT, ZONE, PLAYER, line(261, 4, "Change|40000003|PosX|105"),
                line(20, 5, "40000001|Boss|5678"), WIPE, ANCHOR);
        Assert.assertEquals(result.reason(), "");
        Assert.assertEquals(result.lines().get(0), WIPE);
        Assert.assertTrue(result.lines().stream().anyMatch(s -> s.contains("|PosX|105|PosY|25|")));
        Assert.assertFalse(result.lines().stream().anyMatch(s -> s.contains("5678")));
        Assert.assertTrue(result.lines().stream().allMatch(s -> s.split("\\|")[1].equals(WIPE.split("\\|")[1])));
    }

    @Test
    public void removalsPreventOldActorsFromBeingSeeded() throws Exception {
        var result = read(ZONE, PLAYER, OBJECT, line(261, 5, "Remove|40000003"), WIPE, ANCHOR);
        Assert.assertFalse(result.lines().stream().anyMatch(s -> s.contains("40000003")));
        result = read(OBJECT, line(4, 1, "40000003|Object"), ZONE, PLAYER,
                line(261, 4, "Change|40000003|Heading|1"), ANCHOR);
        Assert.assertFalse(result.lines().stream().anyMatch(s -> s.contains("|PosX|95")));
    }

    @Test
    public void newAddDoesNotInheritAnOlderActorsPosition() throws Exception {
        var result = read(OBJECT, ZONE, PLAYER,
                line(261, 4, "Add|40000003|Type|7|PosX|110|PosY|120"), WIPE, ANCHOR);
        String restored = String.join("\n", result.lines());
        Assert.assertTrue(restored.contains("|PosX|110|PosY|120|"));
        Assert.assertFalse(restored.contains("|PosZ|12.5"));
    }

    @Test
    public void rejectsMissingBoundariesPlayersAndAnchors() throws Exception {
        Assert.assertFalse(read(PLAYER, ANCHOR).reason().isEmpty());
        Assert.assertFalse(read(ZONE, ANCHOR).reason().isEmpty());
        Assert.assertFalse(read(ZONE, PLAYER, line(1, 11, "123|Other"), ANCHOR).reason().isEmpty());
        Assert.assertEquals(read(ZONE, PLAYER).reason(), PullHistoryReader.NOT_FLUSHED);
    }

    @Test
    public void excludesTheAnchorAndEverythingAfterIt() throws Exception {
        var result = read(ZONE, PLAYER, ANCHOR, line(1, 16, "123|Future"));
        Assert.assertEquals(result.lines(), List.of(ZONE, PLAYER));
    }

    @Test
    public void oversizedHistoryCanRecoverAtTheNextWipe() throws Exception {
        String large = line(0, 5, "0038|Player|" + "x".repeat(1000));
        Assert.assertFalse(read(new PullHistoryReader(1000), ZONE, PLAYER, large, ANCHOR).reason().isEmpty());
        var result = read(new PullHistoryReader(1000), ZONE, PLAYER, large, WIPE, ANCHOR);
        Assert.assertEquals(result.reason(), "");
        Assert.assertEquals(result.lines().get(0), WIPE);
        Assert.assertEquals(result.lines().size(), 2);
    }

    @Test
    public void historySpansLogRotationAndAcceptsTheByteOrderMark() throws Exception {
        var folder = Files.createTempDirectory("pull-history-rotation");
        var older = folder.resolve("Network_001.log");
        var newer = folder.resolve("Network_002.log");
        try {
            Files.write(older, List.of("\uFEFF" + ZONE, PLAYER));
            Files.write(newer, List.of(WIPE, ANCHOR));
            Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(1000));
            Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(2000));
            var result = new PullHistoryReader().read(folder, ANCHOR, 0x553, 0x10000001);
            Assert.assertEquals(result.reason(), "");
            Assert.assertEquals(result.lines().get(0), WIPE);
            Assert.assertEquals(result.lines().size(), 2);
        }
        finally {
            Files.deleteIfExists(older);
            Files.deleteIfExists(newer);
            Files.delete(folder);
        }
    }
}
