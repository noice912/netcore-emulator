package core.schema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable, validated set of structs, packets and response rules loaded from schema files. */
public final class SchemaSet {

    private static final SchemaSet EMPTY = new SchemaSet(Map.of(), List.of(), List.of(), List.of());

    private final Map<String, StructDef> structs;
    private final Map<String, PacketDef> packetsByName;
    private final Map<Integer, PacketDef> packetsByOpcode;
    private final List<PacketDef> packets;
    private final List<ResponseRule> rules;
    private final Map<String, List<ResponseRule>> rulesByPacket;
    private final List<String> sources;

    SchemaSet(Map<String, StructDef> structs, List<PacketDef> packets, List<ResponseRule> rules, List<String> sources) {
        this.structs = Collections.unmodifiableMap(new LinkedHashMap<>(structs));
        List<PacketDef> sorted = new ArrayList<>(packets);
        sorted.sort(Comparator.comparingInt(PacketDef::opcode));
        this.packets = Collections.unmodifiableList(sorted);
        Map<String, PacketDef> byName = new LinkedHashMap<>();
        Map<Integer, PacketDef> byOpcode = new LinkedHashMap<>();
        for (PacketDef def : sorted) {
            byName.put(def.name(), def);
            byOpcode.put(def.opcode(), def);
        }
        this.packetsByName = Collections.unmodifiableMap(byName);
        this.packetsByOpcode = Collections.unmodifiableMap(byOpcode);
        this.rules = List.copyOf(rules);
        Map<String, List<ResponseRule>> grouped = new LinkedHashMap<>();
        for (ResponseRule rule : rules) {
            grouped.computeIfAbsent(rule.on(), k -> new ArrayList<>()).add(rule);
        }
        grouped.replaceAll((k, v) -> Collections.unmodifiableList(v));
        this.rulesByPacket = Collections.unmodifiableMap(grouped);
        this.sources = List.copyOf(sources);
    }

    public static SchemaSet empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return packets.isEmpty() && structs.isEmpty();
    }

    public Collection<StructDef> structs() {
        return structs.values();
    }

    public Optional<StructDef> struct(String name) {
        return Optional.ofNullable(structs.get(name));
    }

    /** Packets ordered by opcode. */
    public List<PacketDef> packets() {
        return packets;
    }

    public Optional<PacketDef> packet(String name) {
        return Optional.ofNullable(packetsByName.get(name));
    }

    public Optional<PacketDef> packet(int opcode) {
        return Optional.ofNullable(packetsByOpcode.get(opcode));
    }

    public List<ResponseRule> rules() {
        return rules;
    }

    public List<ResponseRule> rulesFor(String packetName) {
        return rulesByPacket.getOrDefault(packetName, List.of());
    }

    /** The files this set was built from. */
    public List<String> sources() {
        return sources;
    }
}
