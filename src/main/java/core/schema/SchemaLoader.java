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
 * All files in a directory are merged into one set; names and opcodes must be unique across them.
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
                boolean ok = true;
                if (!byName.containsKey(on)) {
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
                            problems.add(source + ": response rule " + on + " -> " + send + " sets unknown field '"
                                    + entry.getKey() + "'");
                            ok = false;
                            continue;
                        }
                        templates.put(entry.getKey(), SchemaValues.fromJson(entry.getValue()));
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

    private static String join(List<String> problems) {
        return "invalid schema:" + System.lineSeparator() + "  - "
                + String.join(System.lineSeparator() + "  - ", problems);
    }
}
