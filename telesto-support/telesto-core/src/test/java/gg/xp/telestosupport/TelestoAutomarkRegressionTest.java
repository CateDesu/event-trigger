package gg.xp.telestosupport;

import com.sun.net.httpserver.HttpServer;
import gg.xp.reevent.context.BasicStateStore;
import gg.xp.reevent.context.StateStore;
import gg.xp.reevent.events.*;
import gg.xp.xivsupport.persistence.InMemoryMapPersistenceProvider;
import gg.xp.xivsupport.sys.KnownLogSource;
import gg.xp.xivsupport.sys.PrimaryLogSource;
import gg.xp.xivsupport.sys.XivMain;
import gg.xp.xivsupport.replay.*;
import gg.xp.xivsupport.events.state.PartyChangeEvent;
import gg.xp.xivsupport.events.ws.ActWsRawMsg;
import gg.xp.xivsupport.events.triggers.marks.AutoMarkHandler;
import gg.xp.xivsupport.events.triggers.marks.ClearAutoMarkRequest;
import gg.xp.xivsupport.events.triggers.marks.adv.*;
import gg.xp.xivsupport.lang.LanguageController;
import gg.xp.xivsupport.models.XivPlayerCharacter;
import gg.xp.xivsupport.models.XivWorld;
import gg.xp.xivdata.data.Job;
import org.testng.Assert;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public class TelestoAutomarkRegressionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final class CaptureMaster extends EventMaster {
        final List<Event> events = new CopyOnWriteArrayList<>();
        CaptureMaster() { super(new BasicEventDistributor(new BasicStateStore()), new BasicEventQueue()); }
        @Override public void pushEvent(Event event) { events.add(event); }
    }

    private static final class Endpoint implements AutoCloseable {
        final HttpServer server;
        final List<String> commands = new CopyOnWriteArrayList<>();
        final CountDownLatch first = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final ExecutorService executor = Executors.newCachedThreadPool();
        volatile boolean block;
        volatile boolean slow;
        volatile int status = 200;
        volatile String body = "null";

        Endpoint() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
                String command = request.path("payload").path("command").asText();
                commands.add(command);
                if (block && command.equals("first")) {
                    first.countDown();
                    try { release.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                }
                if (slow && !command.equals("current")) {
                    try { Thread.sleep(2000); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try { exchange.getResponseBody().write(bytes); }
                finally { exchange.close(); }
            });
            server.start();
        }

        URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"); }
        @Override public void close() { release.countDown(); server.stop(0); executor.shutdownNow(); }
    }

    private static TelestoMain sender(CaptureMaster master, URI uri) {
        PrimaryLogSource source = new PrimaryLogSource();
        source.setLogSource(KnownLogSource.WEBSOCKET_LIVE);
        TelestoMain main = new TelestoMain(master, new InMemoryMapPersistenceProvider(), source);
        main.getUriSetting().set(uri);
        main.getEnablePartyList().set(false);
        main.getCommandDelayBase().set(0);
        main.getCommandDelayPlus().set(0);
        return main;
    }

    private static void send(TelestoMain main, String command) {
        main.handleMessage(null, main.makeMessage(TelestoMain.GAME_CMD_ID, "ExecuteCommand", Map.of("command", command), true));
    }

    @Test
    public void commandResponsesRemainOrdered() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.block = true;
            CaptureMaster master = new CaptureMaster();
            TelestoMain main = sender(master, endpoint.uri());
            send(main, "first");
            Assert.assertTrue(endpoint.first.await(5, TimeUnit.SECONDS));
            send(main, "second");
            Thread.sleep(200);
            Assert.assertEquals(endpoint.commands, List.of("first"));
            endpoint.release.countDown();
            await(() -> master.events.stream().filter(TelestoResponse.class::isInstance).count() == 2);
            Assert.assertEquals(endpoint.commands, List.of("first", "second"));
            Assert.assertEquals(main.getStatus(), TelestoStatus.GOOD);
        }
    }

    @Test
    public void rejectedRequestsAreBadAndNotRetried() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.status = 503;
            CaptureMaster master = new CaptureMaster();
            TelestoMain main = sender(master, endpoint.uri());
            send(main, "reject");
            await(() -> main.getStatus() == TelestoStatus.BAD);
            Assert.assertEquals(master.events.stream().filter(TelestoHttpError.class::isInstance).count(), 1L);
            Assert.assertEquals(endpoint.commands, List.of("reject"));
        }
    }

    @Test
    public void failuresCarryTheirPostCancellationGenerationAndOriginalUri() throws Exception {
        for (int superseded = 0; superseded < 3; superseded++) {
            try (Endpoint endpoint = new Endpoint()) {
                endpoint.status = 503;
                CaptureMaster master = new CaptureMaster();
                TelestoMain main = sender(master, endpoint.uri());
                AtomicLong generation = new AtomicLong();
                CountDownLatch cancelled = new CountDownLatch(1);
                CountDownLatch releaseFailure = new CountDownLatch(1);
                main.setDeliveryPermit(() -> {
                    long admitted = generation.get();
                    return () -> admitted == generation.get();
                });
                main.setDeliveryFailure(() -> {
                    long failed = generation.incrementAndGet();
                    BooleanSupplier permit = () -> failed == generation.get();
                    cancelled.countDown();
                    try { Assert.assertTrue(releaseFailure.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { throw new IllegalStateException(error); }
                    return permit;
                });
                send(main, "rejected");
                Assert.assertTrue(cancelled.await(5, TimeUnit.SECONDS));
                if (superseded == 1) generation.incrementAndGet();
                if (superseded == 2) main.getUriSetting().set(endpoint.uri().resolve("changed"));
                releaseFailure.countDown();
                await(() -> master.events.stream().anyMatch(TelestoHttpError.class::isInstance));
                TelestoHttpError failure = (TelestoHttpError) master.events.stream()
                        .filter(TelestoHttpError.class::isInstance).findFirst().orElseThrow();
                Assert.assertEquals(failure.isCurrent(), superseded == 0);
                if (superseded == 0) await(() -> main.getStatus() == TelestoStatus.BAD);
                else Assert.assertEquals(main.getStatus(), TelestoStatus.UNKNOWN);
                generation.incrementAndGet();
                Assert.assertFalse(failure.isCurrent(), "A queued failure cannot affect a newer control epoch");
                endpoint.status = 200;
                send(main, "current");
                await(() -> main.getStatus() == TelestoStatus.GOOD);
                Assert.assertEquals(endpoint.commands, List.of("rejected", "current"));
            }
        }
    }

    @Test
    public void timedOutRequestsCancelQueuedBurstAndAllowNewOutput() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.block = true;
            CaptureMaster master = new CaptureMaster();
            TelestoMain main = sender(master, endpoint.uri());
            AtomicLong generation = new AtomicLong();
            main.setDeliveryPermit(() -> {
                long admitted = generation.get();
                return () -> admitted == generation.get();
            });
            main.setDeliveryFailure(() -> {
                long cancelled = generation.incrementAndGet();
                return () -> cancelled == generation.get();
            });
            long started = System.nanoTime();
            send(main, "first");
            Assert.assertTrue(endpoint.first.await(5, TimeUnit.SECONDS));
            send(main, "stale second");
            send(main, "stale third");
            await(() -> main.getStatus() == TelestoStatus.BAD);
            Assert.assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5500);
            endpoint.release.countDown();
            send(main, "current");
            await(() -> main.getStatus() == TelestoStatus.GOOD);
            Assert.assertEquals(endpoint.commands, List.of("first", "current"));
            Assert.assertEquals(master.events.stream().filter(TelestoConnectionError.class::isInstance).count(), 1L);
        }
    }

    @Test
    public void slowSuccessResponsesCannotDrainAnOldBurst() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.slow = true;
            CaptureMaster master = new CaptureMaster();
            TelestoMain main = sender(master, endpoint.uri());
            AtomicLong generation = new AtomicLong();
            main.setDeliveryPermit(() -> {
                long admitted = generation.get();
                return () -> admitted == generation.get();
            });
            main.setDeliveryFailure(() -> {
                long cancelled = generation.incrementAndGet();
                return () -> cancelled == generation.get();
            });
            send(main, "first");
            send(main, "second");
            send(main, "stale third");
            await(() -> main.getStatus() == TelestoStatus.BAD);
            Assert.assertEquals(endpoint.commands, List.of("first", "second"));
            TelestoConnectionError failure = (TelestoConnectionError) master.events.stream()
                    .filter(TelestoConnectionError.class::isInstance).findFirst().orElseThrow();
            Assert.assertTrue(failure.getError() instanceof java.util.concurrent.TimeoutException);
            send(main, "current");
            await(() -> main.getStatus() == TelestoStatus.GOOD);
            Assert.assertEquals(endpoint.commands, List.of("first", "second", "current"));
        }
    }

    @Test
    public void oldUriResponseCannotRestoreTransportStatus() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.block = true;
            CaptureMaster master = new CaptureMaster();
            TelestoMain main = sender(master, endpoint.uri());
            send(main, "first");
            Assert.assertTrue(endpoint.first.await(5, TimeUnit.SECONDS));
            main.getUriSetting().set(endpoint.uri().resolve("changed"));
            endpoint.release.countDown();
            Thread.sleep(300);
            Assert.assertEquals(main.getStatus(), TelestoStatus.UNKNOWN);
            Assert.assertTrue(master.events.isEmpty());
        }
    }

    @Test
    public void malformedResponseFailsAndWrongResponseIdFails() throws Exception {
        for (String body : List.of("not json", "{\"id\":123,\"response\":[]}")) {
            try (Endpoint endpoint = new Endpoint()) {
                endpoint.body = body;
                CaptureMaster master = new CaptureMaster();
                TelestoMain main = sender(master, endpoint.uri());
                send(main, "invalid");
                await(() -> main.getStatus() == TelestoStatus.BAD);
                Assert.assertEquals(master.events.stream().filter(TelestoConnectionError.class::isInstance).count(), 1L);
            }
        }
    }

    private static final class CaptureContext implements EventContext {
        final List<Event> events = new CopyOnWriteArrayList<>();
        @Override public void accept(Event event) { events.add(event); }
        @Override public void enqueue(Event event) { events.add(event); }
        @Override public StateStore getStateInfo() { return null; }
    }

    private static TelestoResponse party(List<Map<String, Object>> entries) {
        return new TelestoResponse(Map.of("id", TelestoMain.PARTY_UPDATE_ID, "response", entries));
    }

    @Test
    public void partyOrderUsesAuthoritativeSlotsAndInvalidatesOnSettings() {
        TelestoMain main = sender(new CaptureMaster(), URI.create("http://127.0.0.1:1/"));
        TelestoPartyListHandler handler = new TelestoPartyListHandler(main);
        CaptureContext context = new CaptureContext();
        handler.handlePartyReponse(context, party(List.of(
                Map.of("order", "2", "actor", "10000002"), Map.of("order", "1", "actor", "10000001"))));
        Assert.assertTrue(handler.matchesSlot(0x10000001L, 1));
        Assert.assertTrue(handler.matchesSlot(0x10000002L, 2));
        Assert.assertFalse(handler.matchesSlot(0x10000002L, 1));
        main.getEnablePartyList().set(true);
        Assert.assertFalse(handler.matchesSlot(0x10000001L, 1));
        handler.handlePartyReponse(context, party(List.of(Map.of("order", "1", "actor", "10000001"))));
        main.getUriSetting().set(URI.create("http://127.0.0.1:2/"));
        Assert.assertFalse(handler.matchesSlot(0x10000001L, 1));
    }

    @Test
    public void duplicateRawPartySnapshotsPreserveQueuedMarksAndAuthoritativeSlots() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            var pico = XivMain.testingMasterInit();
            var master = pico.getComponent(EventMaster.class);
            var dist = pico.getComponent(EventDistributor.class);
            master.pushEventAndWait(new InitEvent());
            RecoveryClock clock = new RecoveryClock();
            PullRecovery recovery = new PullRecovery(clock, new RecoveryQueue(clock), master,
                    pico.getComponent(PrimaryLogSource.class));
            dist.registerHandler(recovery);
            TelestoMain main = sender(new CaptureMaster(), endpoint.uri());
            main.getEnablePartyList().set(true);
            main.getCommandDelayBase().set(250);
            main.setDeliveryPermit(recovery::outputPermit);
            TelestoPartyListHandler handler = new TelestoPartyListHandler(main);
            dist.registerHandler(PartyChangeEvent.class, handler::partyChanged);
            String snapshot = "{\"type\":\"PartyChanged\",\"party\":["
                    + "{\"id\":\"10000001\",\"name\":\"Player\",\"job\":24,\"inParty\":true}]}";
            master.pushEventAndWait(new ActWsRawMsg(snapshot));
            CaptureContext context = new CaptureContext();
            TelestoResponse reply = party(List.of(Map.of("order", "1", "actor", "10000001")));
            handler.handlePartyReponse(context, reply);
            context.events.forEach(master::pushEventAndWait);
            context.events.clear();
            send(main, "current");
            master.pushEventAndWait(new ActWsRawMsg(snapshot));
            master.pushEventAndWait(new ActWsRawMsg(snapshot.replace("Player", "Enriched")
                    .replace("\"job\":24", "\"job\":24,\"level\":100,\"worldId\":99")));
            Assert.assertTrue(handler.matchesSlot(0x10000001L, 1));
            handler.handlePartyReponse(context, reply);
            Assert.assertTrue(context.events.isEmpty(), "Identical authoritative order must remain a no-op");
            await(() -> endpoint.commands.size() == 1);
            Assert.assertEquals(endpoint.commands, List.of("current"));
            send(main, "obsolete");
            master.pushEventAndWait(new ActWsRawMsg(snapshot.replace("\"job\":24", "\"job\":21")));
            Assert.assertFalse(handler.matchesSlot(0x10000001L, 1));
            Thread.sleep(350);
            Assert.assertEquals(endpoint.commands, List.of("current"), "Changed selection must cancel queued work");
        }
    }

    @Test
    public void incompleteAndDuplicatePartyRepliesCannotCompressSlots() {
        TelestoMain main = sender(new CaptureMaster(), URI.create("http://127.0.0.1:1/"));
        TelestoPartyListHandler handler = new TelestoPartyListHandler(main);
        CaptureContext context = new CaptureContext();
        for (List<Map<String, Object>> entries : List.<List<Map<String, Object>>>of(
                List.of(Map.of("order", "2", "actor", "10000002")),
                List.of(Map.of("order", "1", "actor", "00000000")),
                List.of(Map.of("order", "1", "actor", "10000001"), Map.of("order", "2", "actor", "10000001")))) {
            handler.handlePartyReponse(context, party(entries));
            Assert.assertFalse(handler.matchesSlot(0x10000001L, 1));
            Assert.assertFalse(handler.matchesSlot(0x10000002L, 1));
            Assert.assertFalse(handler.matchesSlot(0x10000002L, 2));
        }
    }

    @Test
    public void malformedPartyRepliesRetireSlotsAndQueuedOutputThenRecover() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            var pico = XivMain.testingMasterInit();
            var master = pico.getComponent(EventMaster.class);
            var dist = pico.getComponent(EventDistributor.class);
            master.pushEventAndWait(new InitEvent());
            RecoveryClock clock = new RecoveryClock();
            PullRecovery recovery = new PullRecovery(clock, new RecoveryQueue(clock), master,
                    pico.getComponent(PrimaryLogSource.class));
            dist.registerHandler(recovery);
            InMemoryMapPersistenceProvider persistence = new InMemoryMapPersistenceProvider();
            AutoMarkHandler am = new AutoMarkHandler(persistence, null, new LanguageController());
            AutoMarkServiceSelector selector = new AutoMarkServiceSelector(persistence, am);
            TelestoMain main = sender(new CaptureMaster(), endpoint.uri());
            main.getEnablePartyList().set(true);
            main.setDeliveryPermit(recovery::outputPermit);
            TelestoPartyListHandler partyHandler = new TelestoPartyListHandler(main);
            TelestoAutoMarkHandler markerHandler = new TelestoAutoMarkHandler(persistence, am, selector, partyHandler);
            XivPlayerCharacter player = new XivPlayerCharacter(0x10000001L, "Player", Job.WHM,
                    XivWorld.unknown(), false, 1, null, null, null, 0, 0, 1, 100, 0, 0);
            CaptureContext context = new CaptureContext();
            TelestoResponse valid = party(List.of(Map.of("order", "1", "actor", "10000001")));
            List<String> expected = new ArrayList<>();
            for (Object response : Arrays.asList(
                    Map.of("order", "1", "actor", "10000001"), "invalid", null,
                    List.of("invalid member"), List.of(Map.of("order", "1")),
                    List.of(Map.of("actor", "10000001")),
                    List.of(Map.of("order", "invalid", "actor", "10000001")),
                    List.of(Map.of("order", "1", "actor", "10000001"), Map.of("order", "2", "actor", "invalid")),
                    List.of())) {
                partyHandler.handlePartyReponse(context, valid);
                context.events.forEach(master::pushEventAndWait);
                context.events.clear();
                Assert.assertTrue(partyHandler.matchesSlot(player.getId(), 1));
                BooleanSupplier queuedPermit = recovery.outputPermit();
                main.getCommandDelayBase().set(250);
                SpecificAutoMarkSlotRequest queuedMark = new SpecificAutoMarkSlotRequest(1, MarkerSign.ATTACK1);
                queuedMark.setParent(new SpecificAutoMarkRequest(player, MarkerSign.ATTACK1));
                markerHandler.doSpecificAutoMark(context, queuedMark);
                markerHandler.clearMarks(context, new ClearAutoMarkRequest());
                Assert.assertEquals(context.events.size(), 9);
                context.events.forEach(event -> send(main, ((TelestoGameCommand) event).getCommand()));
                context.events.clear();
                Map<String, Object> data = new HashMap<>();
                data.put("id", TelestoMain.PARTY_UPDATE_ID);
                data.put("response", response);
                partyHandler.handlePartyReponse(context, new TelestoResponse(data));
                Assert.assertFalse(partyHandler.matchesSlot(player.getId(), 1), "Invalid response cannot retain a slot");
                Assert.assertEquals(context.events.size(), 1, "Retiring a valid party emits one order reset");
                context.events.forEach(master::pushEventAndWait);
                context.events.clear();
                Assert.assertFalse(queuedPermit.getAsBoolean(), "The order reset must cancel admitted output");
                SpecificAutoMarkSlotRequest rejected = new SpecificAutoMarkSlotRequest(1, MarkerSign.BIND2);
                rejected.setParent(new SpecificAutoMarkRequest(player, MarkerSign.BIND2));
                markerHandler.doSpecificAutoMark(context, rejected);
                Assert.assertFalse(rejected.isHandled());
                Assert.assertTrue(context.events.isEmpty(), "Actor markers wait for a new authoritative party");
                main.getCommandDelayBase().set(0);
                markerHandler.clearMarks(context, new ClearAutoMarkRequest());
                Assert.assertEquals(context.events.size(), 8, "An explicit current clear remains available");
                context.events.forEach(event -> send(main, ((TelestoGameCommand) event).getCommand()));
                for (int slot = 1; slot <= 8; slot++) expected.add("/mk clear <" + slot + ">");
                await(() -> endpoint.commands.size() == expected.size());
                Assert.assertEquals(endpoint.commands, expected, "Old queued marks and clears cannot reach the endpoint");
                context.events.clear();
                partyHandler.handlePartyReponse(context, valid);
                context.events.forEach(master::pushEventAndWait);
                context.events.clear();
                SpecificAutoMarkSlotRequest recovered = new SpecificAutoMarkSlotRequest(1, MarkerSign.BIND2);
                recovered.setParent(new SpecificAutoMarkRequest(player, MarkerSign.BIND2));
                markerHandler.doSpecificAutoMark(context, recovered);
                Assert.assertTrue(recovered.isHandled());
                Assert.assertEquals(context.events.size(), 1);
                send(main, ((TelestoGameCommand) context.events.get(0)).getCommand());
                expected.add("/mk bind2 <1>");
                await(() -> endpoint.commands.size() == expected.size());
                Assert.assertEquals(endpoint.commands, expected);
                context.events.clear();
            }
        }
    }

    @Test
    public void actorRequestsWaitForAuthoritativePartyAndRejectWrongSlots() {
        InMemoryMapPersistenceProvider persistence = new InMemoryMapPersistenceProvider();
        AutoMarkHandler am = new AutoMarkHandler(persistence, null, new LanguageController());
        AutoMarkServiceSelector selector = new AutoMarkServiceSelector(persistence, am);
        TelestoMain main = sender(new CaptureMaster(), URI.create("http://127.0.0.1:1/"));
        main.getEnablePartyList().set(true);
        TelestoPartyListHandler party = new TelestoPartyListHandler(main);
        TelestoAutoMarkHandler handler = new TelestoAutoMarkHandler(persistence, am, selector, party);
        XivPlayerCharacter player = new XivPlayerCharacter(0x10000002L, "Player", Job.WHM,
                XivWorld.unknown(), false, 1, null, null, null, 0, 0, 1, 100, 0, 0);
        SpecificAutoMarkRequest request = new SpecificAutoMarkRequest(player, MarkerSign.BIND2);
        CaptureContext context = new CaptureContext();
        SpecificAutoMarkSlotRequest correct = new SpecificAutoMarkSlotRequest(2, MarkerSign.BIND2);
        correct.setParent(request);
        handler.doSpecificAutoMark(context, correct);
        Assert.assertTrue(context.events.isEmpty());
        party.handlePartyReponse(context, party(List.of(
                Map.of("order", "1", "actor", "10000001"), Map.of("order", "2", "actor", "10000002"))));
        context.events.clear();
        SpecificAutoMarkSlotRequest wrong = new SpecificAutoMarkSlotRequest(1, MarkerSign.BIND2);
        wrong.setParent(request);
        handler.doSpecificAutoMark(context, wrong);
        Assert.assertTrue(context.events.isEmpty());
        handler.doSpecificAutoMark(context, correct);
        Assert.assertEquals(context.events.size(), 1);
        Assert.assertEquals(((TelestoGameCommand) context.events.get(0)).getCommand(), "/mk bind2 <2>");
        Assert.assertTrue(correct.isHandled());
        Assert.assertFalse(wrong.isHandled());
    }

    @Test
    public void queuedPartyResponseDoesNotOutliveItsPermit() {
        TelestoMain main = sender(new CaptureMaster(), URI.create("http://127.0.0.1:1/"));
        TelestoPartyListHandler handler = new TelestoPartyListHandler(main);
        CaptureContext context = new CaptureContext();
        AtomicLong generation = new AtomicLong();
        TelestoResponse response = party(List.of(Map.of("order", "1", "actor", "10000001")));
        response.setDeliveryPermit(() -> generation.get() == 0);
        generation.incrementAndGet();
        handler.handlePartyReponse(context, response);
        Assert.assertTrue(context.events.isEmpty());
        Assert.assertFalse(handler.matchesSlot(0x10000001L, 1));
    }

    @Test
    public void explicitlyDisabledPartyPollingRetainsStandaloneSlotFallback() {
        InMemoryMapPersistenceProvider persistence = new InMemoryMapPersistenceProvider();
        AutoMarkHandler am = new AutoMarkHandler(persistence, null, new LanguageController());
        AutoMarkServiceSelector selector = new AutoMarkServiceSelector(persistence, am);
        TelestoMain main = sender(new CaptureMaster(), URI.create("http://127.0.0.1:1/"));
        TelestoPartyListHandler party = new TelestoPartyListHandler(main);
        TelestoAutoMarkHandler handler = new TelestoAutoMarkHandler(persistence, am, selector, party);
        XivPlayerCharacter player = new XivPlayerCharacter(0x10000002L, "Player", Job.WHM,
                XivWorld.unknown(), false, 1, null, null, null, 0, 0, 1, 100, 0, 0);
        SpecificAutoMarkRequest request = new SpecificAutoMarkRequest(player, MarkerSign.BIND2);
        SpecificAutoMarkSlotRequest slot = new SpecificAutoMarkSlotRequest(1, MarkerSign.BIND2);
        slot.setParent(request);
        CaptureContext context = new CaptureContext();
        handler.doSpecificAutoMark(context, slot);
        Assert.assertEquals(context.events.size(), 1);
        Assert.assertEquals(((TelestoGameCommand) context.events.get(0)).getCommand(), "/mk bind2 <1>");
        Assert.assertTrue(slot.isHandled());
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        Assert.assertTrue(condition.getAsBoolean(), "Transport did not finish within the bound");
    }
}
