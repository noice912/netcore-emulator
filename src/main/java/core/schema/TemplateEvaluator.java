package core.schema;

import core.network.NetworkSession;
import io.netty.buffer.ByteBufUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${...}} placeholders in response-rule templates.
 *
 * <p>A string that is exactly one placeholder yields the referenced value with its type intact
 * (so {@code "${request.uid}"} stays an integer); placeholders embedded in longer text are
 * stringified. Objects and arrays are evaluated recursively. Available references:
 *
 * <ul>
 *   <li>{@code request.<path>} - any field of the packet being answered, dotted for nesting</li>
 *   <li>{@code session.id}, {@code session.remote}, {@code session.address}, {@code session.port},
 *       {@code session.protocol}, {@code session.protocolVersion}, {@code session.loopback},
 *       {@code session.packetsIn}, {@code session.openedAt}</li>
 *   <li>{@code now.millis}, {@code now.seconds}, {@code now.iso}</li>
 *   <li>{@code random.int}, {@code random.long}, {@code random.uuid}</li>
 * </ul>
 *
 * {@link SchemaLoader} validates every reference when a schema is loaded, so an unresolvable path
 * is a load-time error. Should one still reach runtime (a packet whose layout changed under a
 * live session), it resolves to {@code null}, the field keeps its default, and a warning is logged
 * once; the handler never fails.
 */
public final class TemplateEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TemplateEvaluator.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.\\-]+)}");
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** Every reference that is not {@code request.*}. */
    static final Set<String> KNOWN_REFERENCES = Set.of(
            "session.id", "session.remote", "session.address", "session.port", "session.protocol",
            "session.protocolVersion", "session.loopback", "session.packetsIn", "session.openedAt",
            "now.millis", "now.seconds", "now.iso",
            "random.int", "random.long", "random.uuid");

    private TemplateEvaluator() {
    }

    /** All placeholder references in the text, in order (empty for plain text or null). */
    public static List<String> references(String text) {
        List<String> refs = new ArrayList<>();
        if (text != null) {
            Matcher matcher = PLACEHOLDER.matcher(text);
            while (matcher.find()) {
                refs.add(matcher.group(1));
            }
        }
        return refs;
    }

    /** Whether the text is exactly one placeholder (type-preserving) rather than mixed text. */
    public static boolean isWholePlaceholder(String text) {
        return text != null && PLACEHOLDER.matcher(text).matches();
    }

    /** Whether the engine can resolve this reference at all ({@code request.*} paths are checked separately). */
    public static boolean isKnownReference(String reference) {
        return reference.startsWith("request.") || KNOWN_REFERENCES.contains(reference);
    }

    public static Object evaluate(Object template, SchemaPacket request, NetworkSession session) {
        if (template instanceof String text) {
            Matcher matcher = PLACEHOLDER.matcher(text);
            if (matcher.matches()) {
                return resolve(matcher.group(1), request, session);
            }
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(
                        stringify(resolve(matcher.group(1), request, session))));
            }
            matcher.appendTail(out);
            return out.toString();
        }
        if (template instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), evaluate(v, request, session)));
            return out;
        }
        if (template instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(evaluate(item, request, session));
            }
            return out;
        }
        return template;
    }

    static Object resolve(String reference, SchemaPacket request, NetworkSession session) {
        if (reference.startsWith("request.")) {
            return request == null ? null : request.get(reference.substring("request.".length()));
        }
        switch (reference) {
            case "session.id":
                return session == null ? null : session.id();
            case "session.remote":
                return session == null ? null : session.remoteAddress().toString();
            case "session.address":
                return session == null ? null : session.remoteAddress().getAddress().getHostAddress();
            case "session.port":
                return session == null ? null : (long) session.remoteAddress().getPort();
            case "session.protocol":
                return session == null ? null : session.protocol().name();
            case "session.protocolVersion":
                return session == null ? null : (long) session.protocolVersion();
            case "session.loopback":
                return session == null ? null : session.isLoopback();
            case "session.packetsIn":
                return session == null ? null : session.packetsIn();
            case "session.openedAt":
                return session == null ? null : session.openedAtMillis();
            case "now.millis":
                return System.currentTimeMillis();
            case "now.seconds":
                return System.currentTimeMillis() / 1000L;
            case "now.iso":
                return Instant.now().toString();
            case "random.int":
                return (long) ThreadLocalRandom.current().nextInt(0, Integer.MAX_VALUE);
            case "random.long":
                return ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE);
            case "random.uuid":
                return UUID.randomUUID().toString();
            default:
                if (WARNED.add(reference)) {
                    log.warn("unknown template reference '${{}}' resolves to the field default", reference);
                }
                return null;
        }
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof byte[] bytes) {
            return ByteBufUtil.hexDump(bytes);
        }
        return String.valueOf(value);
    }
}
