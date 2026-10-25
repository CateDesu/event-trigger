package gg.xp.xivsupport.eventstorage;

import gg.xp.reevent.events.Event;
import gg.xp.xivdata.data.Job;
import gg.xp.xivdata.data.ZoneInfo;
import gg.xp.xivsupport.events.ACTLogLineEvent;
import gg.xp.xivsupport.events.debug.DebugEvent;
import gg.xp.xivsupport.events.ws.ActWsConnectionStatusChangedEvent;
import gg.xp.xivsupport.events.ws.ActWsRawMsg;
import gg.xp.xivsupport.models.HitPoints;
import gg.xp.xivsupport.models.Position;
import gg.xp.xivsupport.models.XivZone;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

public class EventReaderSecurityTest {
	private static final AtomicInteger callbacks = new AtomicInteger();

	private static class ForeignCallback implements Serializable {
		private static final long serialVersionUID = 1L;

		private void readObject(ObjectInputStream stream) throws IOException, ClassNotFoundException {
			stream.defaultReadObject();
			callbacks.incrementAndGet();
		}
	}

	private static final class CallbackHandler extends ForeignCallback implements InvocationHandler {
		private static final long serialVersionUID = 1L;

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			return null;
		}
	}

	private static byte[] session(Object... events) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream stream = new ObjectOutputStream(new GZIPOutputStream(bytes))) {
			for (Object event : events) {
				stream.writeObject(event);
			}
		}
		return bytes.toByteArray();
	}

	private static List<Event> read(byte[] bytes) {
		return EventReader.readEventsFromInputStream(new ByteArrayInputStream(bytes));
	}

	private static void assertRejected(Object value) throws IOException {
		RuntimeException error = Assert.expectThrows(RuntimeException.class, () -> read(session(value)));
		Assert.assertTrue(error.getCause() instanceof InvalidClassException, error.toString());
	}

	@Test
	public void importsRawSessionEventsAndTimeValues() throws IOException {
		String raw = "{\"type\":\"LogLine\",\"line\":\"" + "fixture".repeat(100) + "\"}";
		ActWsRawMsg ws = new ActWsRawMsg(raw);
		ws.compress();
		ACTLogLineEvent line = new ACTLogLineEvent("00|2026-09-16T00:00:01Z|0038|Player|Fixture|0");
		ws.setParent(line);
		List<Event> events = read(session(ws, line, new ActWsConnectionStatusChangedEvent()));
		Assert.assertEquals(events.size(), 3);
		Assert.assertEquals(((ActWsRawMsg) events.get(0)).getRawMsgData(), raw);
		Assert.assertEquals(((ACTLogLineEvent) events.get(0).getParent()).getLogLine(), line.getLogLine());
		Assert.assertEquals(((ACTLogLineEvent) events.get(1)).getTimestamp(), line.getTimestamp());
		Assert.assertTrue(events.stream().allMatch(Event::isImported));
	}

	@Test
	public void importsModelCollectionsAndImmutableValues() throws IOException {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("position", new Position(1, 2, 3, 4));
		values.put("hp", new HitPoints(100, 200));
		values.put("zone", new XivZone(123, "Fixture"));
		values.put("zoneInfo", new ZoneInfo(123, "Duty", "Place"));
		values.put("time", List.of(Instant.EPOCH, Duration.ofSeconds(5), ZonedDateTime.parse("2026-09-16T00:00:01Z")));
		values.put("arrayList", new ArrayList<>(List.of(1, 2L, true)));
		values.put("linkedList", new LinkedList<>(List.of("a", "b")));
		values.put("arrayView", Arrays.asList("a", "b"));
		values.put("map", new HashMap<>(Map.of("number", 123)));
		values.put("set", new LinkedHashSet<>(Set.of("a", "b")));
		values.put("immutable", Map.of("list", List.of(1), "set", Set.of(2)));
		values.put("enumSet", EnumSet.of(Job.PLD));
		EnumMap<Job, String> jobs = new EnumMap<>(Job.class);
		jobs.put(Job.PLD, "Fixture");
		values.put("enumMap", jobs);
		values.put("readonly", Collections.unmodifiableList(new ArrayList<>(List.of("fixture"))));
		values.put("empty", List.of(Collections.emptyMap(), Collections.emptySet(), Collections.emptyList()));
		values.put("singleton", Collections.singletonMap("a", Collections.singletonList("b")));
		values.put("bytes", new byte[]{1, 2, 3});
		DebugEvent event = (DebugEvent) read(session(new DebugEvent(Collections.unmodifiableMap(values)))).get(0);
		Map<?, ?> restored = (Map<?, ?>) event.getValue();
		Assert.assertEquals(restored.size(), values.size());
		Assert.assertEquals(restored.get("position"), values.get("position"));
		Assert.assertEquals(restored.get("time"), values.get("time"));
		Assert.assertEquals(restored.get("enumMap"), jobs);
		Assert.assertEquals(((XivZone) restored.get("zone")).getId(), 123L);
	}

	@Test
	public void rejectsForeignCallbacksAtRootAndNestedDepths() throws IOException {
		callbacks.set(0);
		assertRejected(new ForeignCallback());
		assertRejected(new DebugEvent(Map.of("nested", List.of(new ForeignCallback()))));
		Assert.assertEquals(callbacks.get(), 0);
	}

	@Test
	public void rejectsNestedUrlKeysBeforeDeserializationCanResolveHosts() throws IOException {
		Map<URL, String> urls = new HashMap<>();
		urls.put(new URL("http://127.0.0.1/"), "fixture");
		assertRejected(new DebugEvent(urls));
	}

	@Test
	public void rejectsEventProxiesAndExecutableCollections() throws IOException {
		callbacks.set(0);
		Object proxy = Proxy.newProxyInstance(Event.class.getClassLoader(), new Class<?>[]{Event.class}, new CallbackHandler());
		assertRejected(proxy);
		assertRejected(new DebugEvent(proxy));
		assertRejected(new DebugEvent(new PriorityQueue<>(List.of(1, 2))));
		Assert.assertEquals(callbacks.get(), 0);
	}

	@Test
	public void rejectsOversizedArraysAndDeeplyNestedGraphs() throws IOException {
		assertRejected(new DebugEvent(new byte[1_000_001]));
		Object nested = "fixture";
		for (int depth = 0; depth < 70; depth++) {
			nested = new ArrayList<>(List.of(nested));
		}
		assertRejected(new DebugEvent(nested));
	}
}
