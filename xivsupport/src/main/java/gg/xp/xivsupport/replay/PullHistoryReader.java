package gg.xp.xivsupport.replay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.DateTimeException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Selects a pull and reconstructs its actor state from an ACT network log. */
public final class PullHistoryReader {
    private static final long MAX_SCAN_BYTES = 512L << 20;
    private static final long MAX_HISTORY_BYTES = 64L << 20;
    public static final String NOT_FLUSHED = "Live log line has not reached the local log";
    private final long maxHistoryBytes;

    public PullHistoryReader() {
        this(MAX_HISTORY_BYTES);
    }

    PullHistoryReader(long maxHistoryBytes) {
        this.maxHistoryBytes = maxHistoryBytes;
    }

    public record Result(List<String> lines, String reason, int skipped) {
        public Result(List<String> lines, String reason) {
            this(lines, reason, 0);
        }

        public Instant start() {
            return ZonedDateTime.parse(lines.get(0).split("\\|", 3)[1]).toInstant();
        }
    }

    private record Input(Path path, long size, long modified) {}
    private record Slice(Path path, long start) {}

    public Result read(Path folder, String anchor, long zone, long player) throws IOException {
        List<Input> inputs = new ArrayList<>();
        try (var paths = Files.newDirectoryStream(folder, "Network_*.log")) {
            for (Path path : paths) {
                if (Files.isRegularFile(path)) {
                    inputs.add(new Input(path, Files.size(path), Files.getLastModifiedTime(path).toMillis()));
                }
            }
        }
        inputs.sort(Comparator.comparingLong(Input::modified).reversed()
                .thenComparing(input -> input.path().toString(), Comparator.reverseOrder()));
        List<Slice> slices = new ArrayList<>();
        long remaining = MAX_SCAN_BYTES;
        for (Input input : inputs.subList(0, Math.min(2, inputs.size()))) {
            long count = Math.min(input.size(), remaining);
            slices.add(new Slice(input.path(), input.size() - count));
            remaining -= count;
            if (remaining == 0) {
                break;
            }
        }
        Collections.reverse(slices);
        var scan = new Scan(maxHistoryBytes);
        for (Slice slice : slices) {
            try (var input = Files.newInputStream(slice.path())) {
                input.skipNBytes(slice.start());
                try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    if (slice.start() > 0) {
                        reader.readLine();
                    }
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("\uFEFF")) {
                            line = line.substring(1);
                        }
                        if (line.equals(anchor)) {
                            return scan.finish(zone, player);
                        }
                        scan.accept(line);
                    }
                }
            }
        }
        return new Result(List.of(), NOT_FLUSHED);
    }

    private static final class Scan {
        private long zone = -1;
        private long bytes;
        private boolean collecting;
        private int skipped;
        private Map<String, Integer> stateErrors = new LinkedHashMap<>();
        private Map<String, Integer> beforeZoneErrors = Map.of();
        private final long maxHistoryBytes;
        private final List<String> history = new ArrayList<>();
        private final Map<String, String> combatants = new LinkedHashMap<>();
        private Map<String, Map<String, String>> positions = new LinkedHashMap<>();
        private Map<String, Map<String, String>> beforeZone = Map.of();
        private final Map<String, String> seedCombatants = new LinkedHashMap<>();
        private final Map<String, Map<String, String>> seedPositions = new LinkedHashMap<>();

        Scan(long maxHistoryBytes) {
            this.maxHistoryBytes = maxHistoryBytes;
        }

        private static String actor(String id) {
            return Long.toHexString(Long.parseUnsignedLong(id, 16)).toUpperCase();
        }

        private void forgetBeforeZone(String id) {
            if (!beforeZone.isEmpty()) {
                beforeZone.remove(id);
            }
            if (!beforeZoneErrors.isEmpty()) {
                beforeZoneErrors.remove(id);
            }
        }

        private void recordStateError(String[] parts) {
            try {
                String id = switch (Integer.parseInt(parts[0])) {
                    case 3, 4, 271 -> actor(parts[2]);
                    case 261 -> actor(parts[3]);
                    default -> null;
                };
                if (id != null) {
                    stateErrors.merge(id, 1, Integer::sum);
                }
            }
            catch (NumberFormatException | IndexOutOfBoundsException ignored) {
                // Without an actor ID this line cannot contribute an actor seed.
            }
        }

        private Map<String, String> positionValues(String id) {
            Map<String, String> values = positions.get(id);
            if (values != null) {
                return values;
            }
            Map<String, String> prior = beforeZone.get(id);
            if (prior == null) {
                return new LinkedHashMap<>();
            }
            // An update confirms that this actor survived the announcement.
            seedPositions.put(id, new LinkedHashMap<>(prior));
            int errors = beforeZoneErrors.getOrDefault(id, 0);
            skipped += errors;
            if (errors > 0) {
                stateErrors.merge(id, errors, Integer::sum);
            }
            return new LinkedHashMap<>(prior);
        }

        void accept(String line) {
            String[] parts = line.split("\\|", -1);
            try {
                int kind = Integer.parseInt(parts[0]);
                ZonedDateTime.parse(parts[1]);
                switch (kind) {
                    case 1 -> {
                        zone = Long.parseLong(parts[2], 16);
                        beforeZone = positions;
                        positions = new LinkedHashMap<>();
                        beforeZoneErrors = stateErrors;
                        stateErrors = new LinkedHashMap<>();
                        combatants.clear();
                        boundary();
                        collecting = true;
                    }
                    case 3 -> {
                        String id = actor(parts[2]);
                        combatants.put(id, line);
                        // A current actor snapshot supersedes an unconfirmed position from the old zone.
                        forgetBeforeZone(id);
                    }
                    case 4 -> {
                        String id = actor(parts[2]);
                        combatants.remove(id);
                        positions.remove(id);
                        stateErrors.remove(id);
                        forgetBeforeZone(id);
                    }
                    case 261 -> {
                        String id = actor(parts[3]);
                        switch (parts[2]) {
                            case "Remove" -> {
                                positions.remove(id);
                                combatants.remove(id);
                                stateErrors.remove(id);
                                forgetBeforeZone(id);
                            }
                            case "Add", "Change" -> {
                                Map<String, String> values;
                                if ("Add".equals(parts[2])) {
                                    values = new LinkedHashMap<>();
                                    stateErrors.remove(id);
                                }
                                else {
                                    values = positionValues(id);
                                }
                                for (int i = 4; i + 1 < parts.length - 1; i += 2) {
                                    values.put(parts[i], parts[i + 1]);
                                }
                                if ("Add".equals(parts[2])) {
                                    for (String key : List.of("PosX", "PosY", "PosZ", "Heading")) {
                                        values.putIfAbsent(key, "0");
                                    }
                                }
                                positions.put(id, values);
                                forgetBeforeZone(id);
                            }
                            default -> { }
                        }
                    }
                    case 271 -> {
                        String id = actor(parts[2]);
                        Map<String, String> update = Map.of(
                                "PosX", Double.toString(Double.parseDouble(parts[6])),
                                "PosY", Double.toString(Double.parseDouble(parts[7])),
                                "PosZ", Double.toString(Double.parseDouble(parts[8])),
                                "Heading", Double.toString(Double.parseDouble(parts[3])));
                        var values = positionValues(id);
                        values.putAll(update);
                        positions.put(id, values);
                        forgetBeforeZone(id);
                    }
                    case 33 -> {
                        long command = Long.parseLong(parts[3], 16);
                        if (command == 0x40000010L || command == 0x4000000FL) {
                            boundary();
                            collecting = zone >= 0;
                            seedCombatants.putAll(combatants);
                            positions.forEach((id, values) -> seedPositions.put(id, new LinkedHashMap<>(values)));
                        }
                    }
                    default -> { }
                }
                if (collecting) {
                    history.add(line);
                    bytes += 2L * line.length() + 64;
                    if (bytes > maxHistoryBytes) {
                        boundary();
                        collecting = false;
                    }
                }
            }
            catch (NumberFormatException | IndexOutOfBoundsException | DateTimeException ignored) {
                skipped++;
                recordStateError(parts);
                // An incomplete log line cannot establish a recovery boundary.
            }
        }

        private void boundary() {
            // Earlier mechanics are discarded but damaged actor seeds can still affect this pull.
            skipped = stateErrors.entrySet().stream()
                    .filter(entry -> combatants.containsKey(entry.getKey()) || positions.containsKey(entry.getKey()))
                    .mapToInt(Map.Entry::getValue).sum();
            history.clear();
            bytes = 0;
            seedCombatants.clear();
            seedPositions.clear();
        }

        Result finish(long expectedZone, long player) {
            if (history.isEmpty() || zone != expectedZone) {
                return new Result(List.of(), "No complete pull boundary for the current zone in the local log");
            }
            if (!combatants.containsKey(Long.toHexString(player).toUpperCase())) {
                return new Result(List.of(), "Current player is missing from the local log");
            }
            String timestamp = history.get(0).split("\\|", 3)[1];
            List<String> lines = new ArrayList<>();
            // Zone changes clear imported actors so their seeds must follow the boundary.
            lines.add(history.get(0));
            for (String line : seedCombatants.values()) {
                String[] parts = line.split("\\|", 3);
                lines.add(parts[0] + '|' + timestamp + '|' + parts[2]);
            }
            seedPositions.forEach((id, values) -> {
                String update = values.keySet().containsAll(List.of("PosX", "PosY", "PosZ", "Heading")) ? "Add" : "Change";
                StringBuilder line = new StringBuilder("261|").append(timestamp).append('|').append(update).append('|').append(id);
                values.forEach((key, value) -> line.append('|').append(key).append('|').append(value));
                lines.add(line.append("|0").toString());
            });
            lines.addAll(history.subList(1, history.size()));
            return new Result(List.copyOf(lines), "", skipped);
        }
    }
}
