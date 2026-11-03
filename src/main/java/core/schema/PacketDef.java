package core.schema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A packet layout declared in a schema file.
 *
 * @param requiresHandshake whether the dispatcher should gate this packet behind the handshake
 *                          when {@code security.requireHandshake} is on
 */
public record PacketDef(String name, int opcode, boolean requiresHandshake, String description,
                        List<FieldDef> fields) {

    public PacketDef {
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
    }

    public Optional<FieldDef> field(String fieldName) {
        for (FieldDef field : fields) {
            if (field.name().equals(fieldName)) {
                return Optional.of(field);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return String.format("%s(0x%04X)", name, opcode);
    }
}
