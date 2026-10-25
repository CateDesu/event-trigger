package gg.xp.xivsupport.eventstorage;

import gg.xp.xivsupport.events.fflogs.FflogsImportProcessor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import gg.xp.reevent.events.Event;
import gg.xp.xivdata.data.*;
import gg.xp.xivsupport.events.ACTLogLineEvent;
import gg.xp.xivsupport.events.actlines.events.MapChangeEvent;
import gg.xp.xivsupport.events.actlines.events.ZoneChangeEvent;
import gg.xp.xivsupport.events.actlines.parsers.ActLineParseException;
import gg.xp.xivsupport.events.fflogs.FflogsMasterDataEvent;
import gg.xp.xivsupport.events.fflogs.FflogsRawEvent;
import gg.xp.xivsupport.gui.imprt.EventIterator;
import gg.xp.xivsupport.models.XivZone;
import gg.xp.xivsupport.persistence.Compressible;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.serialization.ValidatingObjectInputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputFilter;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

public final class EventReader {

	private static final Logger log = LoggerFactory.getLogger(EventReader.class);

	private EventReader() {
	}

	private static final Set<String> SESSION_VALUE_CLASSES = Set.of(
			"java.lang.String", "java.lang.Boolean", "java.lang.Byte", "java.lang.Short",
			"java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double",
			"java.lang.Character", "java.lang.Number", "java.lang.Enum",
			"java.util.ArrayList", "java.util.LinkedList", "java.util.Arrays$ArrayList",
			"java.util.HashMap", "java.util.LinkedHashMap", "java.util.HashSet", "java.util.LinkedHashSet",
			"java.util.EnumMap", "java.util.RegularEnumSet", "java.util.JumboEnumSet",
			"java.util.EnumSet$SerializationProxy", "java.util.CollSer",
			"java.util.ImmutableCollections$List12", "java.util.ImmutableCollections$ListN",
			"java.util.ImmutableCollections$Set12", "java.util.ImmutableCollections$SetN",
			"java.util.ImmutableCollections$Map1", "java.util.ImmutableCollections$MapN",
			"java.util.Collections$EmptyList", "java.util.Collections$EmptySet", "java.util.Collections$EmptyMap",
			"java.util.Collections$SingletonList", "java.util.Collections$SingletonSet", "java.util.Collections$SingletonMap",
			"java.util.Collections$UnmodifiableCollection", "java.util.Collections$UnmodifiableList",
			"java.util.Collections$UnmodifiableRandomAccessList", "java.util.Collections$UnmodifiableSet",
			"java.util.Collections$UnmodifiableMap"
	);

	private static ObjectInputFilter.Status filterSessionObject(ObjectInputFilter.FilterInfo info) {
		if (info.depth() > 64 || info.arrayLength() > 1_000_000) {
			return ObjectInputFilter.Status.REJECTED;
		}
		Class<?> type = info.serialClass();
		if (type == null) {
			return ObjectInputFilter.Status.UNDECIDED;
		}
		if (Proxy.isProxyClass(type) || (info.depth() == 1 && !Event.class.isAssignableFrom(type))) {
			return ObjectInputFilter.Status.REJECTED;
		}
		return sessionClassAllowed(type) ? ObjectInputFilter.Status.ALLOWED : ObjectInputFilter.Status.REJECTED;
	}

	private static boolean sessionClassNameAllowed(String className) {
		try {
			return sessionClassAllowed(Class.forName(className, false, EventReader.class.getClassLoader()));
		}
		catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}

	private static boolean sessionClassAllowed(Class<?> type) {
		if (Proxy.isProxyClass(type)) {
			return false;
		}
		// Array contents still pass through this filter individually.
		if (type.isArray()) {
			do {
				type = type.getComponentType();
			} while (type.isArray());
			if (type == Object.class || type == Map.Entry.class) {
				return true;
			}
		}
		String packageName = type.getPackageName();
		return type.isPrimitive() || type.isEnum() || Event.class.isAssignableFrom(type)
				|| SESSION_VALUE_CLASSES.contains(type.getName())
				|| packageName.equals("java.time")
				|| packageName.equals("gg.xp.xivsupport.models")
				|| packageName.equals("gg.xp.xivdata.data");
	}

	public static List<Event> readEventsFromResource(String resourcePath) {
		InputStream stream = EventReader.class.getResourceAsStream(resourcePath);
		return readEventsFromInputStream(stream);
	}

	public static List<Event> readEventsFromFile(File file) {
		try (InputStream stream = new FileInputStream(file)) {
			return readEventsFromInputStream(stream);
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static List<Event> readEventsFromInputStream(InputStream stream) {
		List<Event> events;
		try (GZIPInputStream gzip = new GZIPInputStream(stream);
		     ValidatingObjectInputStream ois = new ValidatingObjectInputStream(gzip)) {
			ois.accept(EventReader::sessionClassNameAllowed);
			ois.setObjectInputFilter(EventReader::filterSessionObject);
			events = new ArrayList<>();
			while (true) {
				Event event;
				try {
					event = (Event) ois.readObject();
				}
				catch (EOFException eof) {
					// done reading
					break;
				}
//				catch (WriteAbortedException t) {
//					log.error("Error deserializing event", t);
//					continue;
//				}
				event.setImported(true);
				if (event instanceof Compressible compressible) {
					compressible.decompress();
				}
				events.add(event);
			}
		}
		catch (Throwable e) {
			throw new RuntimeException("Error reading events", e);
		}
		return events;
	}

	public static EventIterator<ACTLogLineEvent> readActLogResource(String resourcePath) {
		List<String> lines;
		InputStream resource = EventReader.class.getResourceAsStream(resourcePath);
		if (resource == null) {
			throw new IllegalArgumentException("The resource '%s' does not exist".formatted(resourcePath));
		}
		lines = IOUtils.readLines(resource, StandardCharsets.UTF_8);
		// TODO: this uses the old tech but the builtin files are small
		return makeIteratorFromRawLines(lines.stream());
	}

	public static EventIterator<ACTLogLineEvent> readActLogFile(File file) {
		Stream<String> lines;
		try {
			lines = Files.lines(file.toPath());
		}
		catch (Throwable t) {
			throw new RuntimeException(t);
		}
		return makeIteratorFromRawLines(lines);
	}

	@NotNull
	private static EventIterator<ACTLogLineEvent> makeIteratorFromRawLines(Stream<String> lines) {
		AtomicInteger ai = new AtomicInteger(1);
		Iterator<ACTLogLineEvent> iterator = lines
				.sequential()
				.filter(s -> !s.isEmpty())
				.map(logLine -> {
					try {
						return new ACTLogLineEvent(logLine, ai.getAndIncrement());
					}
					catch (Throwable t) {
						ActLineParseException exc = new ActLineParseException(logLine, t);
						log.error("Error parsing log line", exc);
						return null;
					}
				})
				.filter(Objects::nonNull)
				.iterator();
		return new EventIterator<ACTLogLineEvent>() {
			private volatile boolean hasNext = iterator.hasNext();
			private volatile Event prev;

			@Override
			public boolean hasMore() {
				return hasNext;
			}

			@Override
			public @Nullable ACTLogLineEvent getNext() {
				ACTLogLineEvent next = iterator.next();
				if (prev instanceof ACTLogLineEvent prevAct) {
					if (next.getLineNum() != prevAct.getLineNum() + 1) {
						log.error("Bad line num!");
					}
				}
				prev = next;
				hasNext = iterator.hasNext();
				return next;
			}
		};
	}

	public static List<Event> readFflogsJson(List<JsonNode> rootNodes) {
		return FflogsImportProcessor.readFflogsJson(rootNodes);
	}
}
