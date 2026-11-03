package core.schema;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import core.handlers.builtin.Opcodes;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Parses schema files and validates them into a {@link SchemaSet}.
 *
 * <p>File format (comments allowed):
 * <pre>{@code
 * {
 *   "structs": { "Vec3": [ {"name":"x","type":"f32"}, {"name":"y","type":"f32"}, {"name":"z","type":"f32"} ] },
 *   "packets": [
 *     { "name": "MoveReq", "opcode": "0x0200", "fields": [
 *         {"name": "entityId", "type": "u32"},
 *         {"name": "position", "type": "struct", "struct": "Vec3"},
 *         {"name": "path",     "type": "array",  "element": "struct", "struct": "Vec3"} ] }
 *   ],
 *   "responses": [
 *     { "on": "MoveReq", "send": "MoveRsp", "fields": { "entityId": "${request.entityId}", "retcode": 0 } }
 *   ]
 * }
 * }</pre>
 *
 * <p>Validation is structural (unique names and opcodes, reserved range, field types, struct
 * references, cycles) and then semantic for every response rule: each {@code ${request.*}} path
 * must resolve to a field of the {@code on} packet, each other reference must be one the
 * {@link TemplateEvaluator} knows, a type-preserving placeholder must be compatible with the target
 * field, mixed text may only feed a string field, and every constant must coerce to its field's
 * type. All files in a directory are merged into one set; every problem is reported in one message.
 */
public final class SchemaLoader {

    private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.LENIENT).create();

    // ---- raw file shape (Gson DTOs) -------------------------------------------------------------

    static final class FileDto {
        List<PacketDto> packets;
        Map<String, List<FieldDto>> structs;
        List<ResponseDto> responses;
    }

    static final class PacketDto {
        String name;
        JsonElement opcode;
        Boolean requiresHandshake;
        String description;
        List<FieldDto> fields;
    }

    static final class FieldDto {
        String name;
        String type;
        String struct;
        String element;
    }

    static final class ResponseDto {
        String on;
        String send;
        Map<String, JsonElement> fields;
        Boolean closeAfter;
        String description;
    }

    private SchemaLoader() {
    }

    /** Load and merge every {@code *.json} file in a directory. A missing directory yields an empty set. */
    public static SchemaSet loadDirectory(Path directory) throws IOException, SchemaException {
        if (!Files.isDirectory(directory)) {
            return SchemaSet.empty();
        }
        List<Path> files;
        try (Stream<Path> listing = Files.list(directory)) {
            files = listing.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
                    .sorted().toList();
        }
        List<FileDto> dtos = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            try {
                dtos.add(parseDto(Files.readString(file, StandardCharsets.UTF_8), name));
                sources.add(name);
            } catch (SchemaException e) {
                problems.add(e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            throw new SchemaException(join(problems));
        }
        return build(dtos, sources);
    }

    /** Parse one schema document. {@code sourceName} is used only in messages. */
    public static SchemaSet parse(String json, String sourceName) throws SchemaException {
        return build(List.of(parseDto(json, sourceName)), List.of(sourceName));
    }

    static FileDto parseDto(String json, String sourceName) throws SchemaException {
        if (json == null || json.isBlank()) {
            throw new SchemaException(sourceName + " is empty");
        }
        try {
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setStrictness(Strictness.LENIENT);
            FileDto dto = GSON.fromJson(reader, FileDto.class);
            if (dto == null) {
                throw new SchemaException(sourceName + " is empty");
            }
            return dto;
        } catch (JsonParseException | IllegalStateException e) {
            throw new SchemaException(sourceName + " is not valid JSON: " + e.getMessage(), e);
        }
    }

    static SchemaSet build(List<FileDto> files, List<String> sources) throws SchemaException {
        List<String> problems = new ArrayList<>();
        Map<String, StructDef> structs = new LinkedHashMap<>();
        Map<String, String> structSource = new HashMap<>();

        for (int i = 0; i < files.size(); i++) {
            FileDto file = files.get(i);
            String source = sources.get(i);
            if (file.structs == null) {
                continue;
            }
            for (Map.Entry<String, List<FieldDto>> entry : file.structs.entrySet()) {
                String name = entry.getKey() == null ? "" : entry.getKey().trim();
                if (name.isEmpty()) {
                    problems.add(source + ": a struct has a blank name");
                    continue;
                }
                if (structs.containsKey(name)) {
                    problems.add(source + ": struct '" + name + "' is already defined in " + structSource.get(name));
                    continue;
                }
                structs.put(name, new StructDef(name, parseFields(entry.getValue(), source + " struct '" + name + "'", problems)));
                structSource.put(name, source);
            }
        }

        List<PacketDef> packets = new ArrayList<>();
        Map<String, String> packetSource = new HashMap<>();
        Map<Integer, String> opcodeOwner = new HashMap<>();
        for (int i = 0; i < files.size(); i++) {
            FileDto file = files.get(i);
            String source = sources.get(i);
            if (file.packets == null) {
                continue;
            }
            for (PacketDto dto : file.packets) {
                String name = dto.name == null ? "" : dto.name.trim();
                if (name.isEmpty()) {
                    problems.add(source + ": a packet has a blank name");
                    continue;
                }
                if (packetSource.containsKey(name)) {
                    problems.add(source + ": packet '" + name + "' is already defined in " + packetSource.get(name));
                    continue;
                }
                int opcode;
                try {
                    opcode = parseOpcode(dto.opcode);
                } catch (IllegalArgumentException e) {
                    problems.add(source + ": packet '" + name + "': " + e.getMessage());
                    continue;
                }
                if (opcode <= Opcodes.RESERVED_MAX) {
                    problems.add(String.format("%s: packet '%s' uses opcode 0x%04X; opcodes up to 0x%04X are reserved "
                            + "for engine control packets", source, name, opcode, Opcodes.RESERVED_MAX));
                    continue;
                }
                if (opcodeOwner.containsKey(opcode)) {
                    problems.add(String.format("%s: packet '%s' reuses opcode 0x%04X already taken by '%s'",
                            source, name, opcode, opcodeOwner.get(opcode)));
                    continue;
                }
                List<FieldDef> fields = parseFields(dto.fields, source + " packet '" + name + "'", problems);
                packets.add(new PacketDef(name, opcode, dto.requiresHandshake == null || dto.requiresHandshake,
                        dto.description, fields));
                packetSource.put(name, source);
                opcodeOwner.put(opcode, name);
            }
        }

        for (StructDef struct : structs.values()) {
            checkStructReferences(struct.fields(), structs, "struct '" + struct.name() + "'", problems);
        }
        for (PacketDef packet : packets) {
            checkStructReferences(packet.fields(), structs, "packet '" + packet.name() + "'", problems);
        }
        detectCycles(structs, problems);
        boolean structurallySound = problems.isEmpty();
        SchemaSet probe = new SchemaSet(structs, packets, List.of(), sources);

        List<ResponseRule> rules = new ArrayList<>();
        Map<String, PacketDef> byName = new HashMap<>();
        packets.forEach(p -> byName.put(p.name(), p));
        for (int i = 0; i < files.size(); i++) {
            FileDto file = files.get(i);
            String source = sources.get(i);
            if (file.responses == null) {
                continue;
            }
            for (ResponseDto dto : file.responses) {
                String on = dto.on == null ? "" : dto.on.trim();
                String send = dto.send == null ? "" : dto.send.trim();
                String context = source + ": response rule " + on + " -> " + send;
                boolean ok = true;
                PacketDef sourcePacket = byName.get(on);
                if (sourcePacket == null) {
                    problems.add(source + ": response rule 'on' references unknown packet '" + on + "'");
                    ok = false;
                }
                PacketDef target = byName.get(send);
                if (target == null) {
                    problems.add(source + ": response rule 'send' references unknown packet '" + send + "'");
                    ok = false;
                }
                Map<String, Object> templates = new LinkedHashMap<>();
                if (dto.fields != null && target != null) {
                    for (Map.Entry<String, JsonElement> entry : dto.fields.entrySet()) {
                        if (target.field(entry.getKey()).isEmpty()) {
                            problems.add(context + " sets unknown field '" + entry.getKey() + "'");
                            ok = false;
                            continue;
                        }
                        templates.put(entry.getKey(), SchemaValues.fromJson(entry.getValue()));
                    }
                }
                if (ok && structurallySound) {
                    for (Map.Entry<String, Object> template : templates.entrySet()) {
                        FieldDef targetField = target.field(template.getKey()).orElseThrow();
                        validateTemplate(template.getValue(), targetField, sourcePacket, probe,
                                context + ", field '" + template.getKey() + "'", problems);
                    }
                }
                if (ok) {
                    rules.add(new ResponseRule(on, send, templates, dto.closeAfter != null && dto.closeAfter,
                            dto.description));
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new SchemaException(join(problems));
        }
        return new SchemaSet(structs, packets, rules, sources);
    }

    // ---- field parsing --------------------------------------------------------------------------

    static List<FieldDef> parseFields(List<FieldDto> dtos, String context, List<String> problems) {
        if (dtos == null) {
            return List.of();
        }
        List<FieldDef> fields = new ArrayList<>(dtos.size());
        Set<String> seen = new HashSet<>();
        for (FieldDto dto : dtos) {
            String name = dto.name == null ? "" : dto.name.trim();
            if (name.isEmpty()) {
                problems.add(context + ": a field has a blank name");
                continue;
            }
            if (!seen.add(name)) {
                problems.add(context + ": field '" + name + "' is defined twice");
                continue;
            }
            FieldType type;
            try {
                type = FieldType.parse(dto.type);
            } catch (IllegalArgumentException e) {
                problems.add(context + ": field '" + name + "': " + e.getMessage());
                continue;
            }
            String structName = dto.struct == null || dto.struct.isBlank() ? null : dto.struct.trim();
            FieldType element = null;
            if (type == FieldType.STRUCT && structName == null) {
                problems.add(context + ": field '" + name + "' is a struct but names no 'struct'");
                continue;
            }
            if (type == FieldType.ARRAY) {
                if (dto.element == null || dto.element.isBlank()) {
                    problems.add(context + ": array field '" + name + "' needs an 'element' type");
                    continue;
                }
                try {
                    element = FieldType.parse(dto.element);
                } catch (IllegalArgumentException e) {
                    problems.add(context + ": array field '" + name + "': " + e.getMessage());
                    continue;
                }
                if (element == FieldType.ARRAY) {
                    problems.add(context + ": array field '" + name + "': nested arrays are not supported; wrap the inner array in a struct");
                    continue;
                }
                if (element == FieldType.STRUCT && structName == null) {
                    problems.add(context + ": array field '" + name + "' has struct elements but names no 'struct'");
                    continue;
                }
            }
            fields.add(new FieldDef(name, type, structName, element));
        }
        return fields;
    }

    private static void checkStructReferences(List<FieldDef> fields, Map<String, StructDef> structs, String context,
                                              List<String> problems) {
        for (FieldDef field : fields) {
            if (field.requiresStruct() && !structs.containsKey(field.structName())) {
                problems.add(context + ": field '" + field.name() + "' references unknown struct '"
                        + field.structName() + "'");
            }
        }
    }

    private static void detectCycles(Map<String, StructDef> structs, List<String> problems) {
        Set<String> done = new HashSet<>();
        for (String name : structs.keySet()) {
            visit(name, structs, new ArrayList<>(), done, problems);
        }
    }

    private static void visit(String name, Map<String, StructDef> structs, List<String> path, Set<String> done,
                              List<String> problems) {
        if (done.contains(name)) {
            return;
        }
        if (path.contains(name)) {
            problems.add("struct cycle: " + String.join(" -> ", path.subList(path.indexOf(name), path.size()))
                    + " -> " + name);
            return;
        }
        StructDef struct = structs.get(name);
        if (struct == null) {
            return;
        }
        path.add(name);
        for (FieldDef field : struct.fields()) {
            if (field.requiresStruct()) {
                visit(field.structName(), structs, path, done, problems);
            }
        }
        path.remove(path.size() - 1);
        done.add(name);
    }

    static int parseOpcode(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("missing 'opcode'");
        }
        int value;
        try {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
                value = element.getAsInt();
            } else {
                String text = element.getAsString().trim();
                value = text.startsWith("0x") || text.startsWith("0X")
                        ? Integer.parseInt(text.substring(2), 16) : Integer.parseInt(text);
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("'opcode' must be an integer or a hex string like \"0x0100\"");
        }
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException("'opcode' " + value + " is outside 0..65535");
        }
        return value;
    }

    // ---- template validation --------------------------------------------------------------------

    /**
     * Check one template against the field it fills. Strings are scanned for placeholders;
     * objects and arrays recurse into the struct / element definitions; everything else must coerce.
     */
    static void validateTemplate(Object template, FieldDef target, PacketDef source, SchemaSet probe,
                                 String context, List<String> problems) {
        if (template instanceof String text) {
            List<String> references = TemplateEvaluator.references(text);
            if (references.isEmpty()) {
                checkConstant(text, target, probe, context, problems);
                return;
            }
            boolean whole = TemplateEvaluator.isWholePlaceholder(text);
            for (String reference : references) {
                if (reference.startsWith("request.")) {
                    String path = reference.substring("request.".length());
                    FieldDef resolved = resolvePath(path, source.fields(), probe);
                    if (resolved == null) {
                        problems.add(context + ": '${" + reference + "}' does not resolve to a field of "
                                + source.name() + " (path '" + path + "')");
                    } else if (whole) {
                        checkCompatible(resolved, target, reference, context, problems);
                    }
                } else if (!TemplateEvaluator.isKnownReference(reference)) {
                    problems.add(context + ": unknown template reference '${" + reference + "}'");
                }
            }
            if (!whole && target.type() != FieldType.STRING) {
                problems.add(context + ": field '" + target.name() + "' is " + describe(target) + " but the template '"
                        + text + "' mixes text and placeholders, which always yields a string");
            }
            return;
        }
        if (template instanceof Map<?, ?> map && target.type() == FieldType.STRUCT) {
            StructDef struct = probe.struct(target.structName()).orElse(null);
            if (struct == null) {
                return; // reported by the structural checks
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                FieldDef inner = struct.field(key).orElse(null);
                if (inner == null) {
                    problems.add(context + ": struct " + struct.name() + " has no field '" + key + "'");
                    continue;
                }
                validateTemplate(entry.getValue(), inner, source, probe, context, problems);
            }
            return;
        }
        if (template instanceof List<?> list && target.type() == FieldType.ARRAY) {
            FieldDef element = target.element();
            for (Object item : list) {
                validateTemplate(item, element, source, probe, context, problems);
            }
            return;
        }
        checkConstant(template, target, probe, context, problems);
    }

    private static void checkConstant(Object value, FieldDef target, SchemaSet probe, String context,
                                      List<String> problems) {
        try {
            SchemaValues.coerce(value, target, probe, 0);
        } catch (RuntimeException e) {
            problems.add(context + ": constant for field '" + target.name() + "' is not valid: " + e.getMessage());
        }
    }

    /** Walk a dotted path ({@code pos.x}, {@code items.0.id}) through the fields; null when it does not exist. */
    static FieldDef resolvePath(String path, List<FieldDef> fields, SchemaSet probe) {
        List<FieldDef> current = fields;
        FieldDef resolved = null;
        boolean expectIndex = false;
        for (String part : path.split("\\.")) {
            if (expectIndex) {
                if (part.isEmpty() || !part.chars().allMatch(Character::isDigit)) {
                    return null;
                }
                expectIndex = false;
                resolved = resolved.element();
                current = resolved.type() == FieldType.STRUCT
                        ? probe.struct(resolved.structName()).map(StructDef::fields).orElse(null) : null;
                continue;
            }
            if (current == null) {
                return null;
            }
            FieldDef match = null;
            for (FieldDef field : current) {
                if (field.name().equals(part)) {
                    match = field;
                    break;
                }
            }
            if (match == null) {
                return null;
            }
            resolved = match;
            if (match.type() == FieldType.STRUCT) {
                current = probe.struct(match.structName()).map(StructDef::fields).orElse(null);
            } else if (match.type() == FieldType.ARRAY) {
                current = null;
                expectIndex = true;
            } else {
                current = null;
            }
        }
        return resolved;
    }

    private static void checkCompatible(FieldDef sourceField, FieldDef target, String reference, String context,
                                        List<String> problems) {
        FieldType s = sourceField.type();
        FieldType d = target.type();
        boolean ok;
        if (d == FieldType.STRING) {
            ok = true;
        } else if (d.isInteger() || d.isFloat() || d == FieldType.BOOL) {
            ok = s.isInteger() || s.isFloat() || s == FieldType.BOOL || s == FieldType.STRING;
        } else if (d == FieldType.BYTES) {
            ok = s == FieldType.BYTES || s == FieldType.STRING || s == FieldType.ARRAY;
        } else if (d == FieldType.STRUCT) {
            ok = s == FieldType.STRUCT && Objects.equals(sourceField.structName(), target.structName());
        } else {
            ok = s == FieldType.ARRAY && sourceField.elementType() == target.elementType()
                    && (sourceField.elementType() != FieldType.STRUCT
                    || Objects.equals(sourceField.structName(), target.structName()));
        }
        if (!ok) {
            problems.add(context + ": '${" + reference + "}' is " + describe(sourceField) + " but field '"
                    + target.name() + "' is " + describe(target));
        }
    }

    static String describe(FieldDef field) {
        return switch (field.type()) {
            case STRUCT -> "struct " + field.structName();
            case ARRAY -> "array of " + (field.elementType() == FieldType.STRUCT
                    ? "struct " + field.structName() : field.elementType().id());
            default -> field.type().id();
        };
    }

    private static String join(List<String> problems) {
        return "invalid schema:" + System.lineSeparator() + "  - "
                + String.join(System.lineSeparator() + "  - ", problems);
    }
}
