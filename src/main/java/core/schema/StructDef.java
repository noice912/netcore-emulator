package core.schema;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A named, reusable group of fields that packets and other structs embed inline. */
public record StructDef(String name, List<FieldDef> fields) {

    public StructDef {
        Objects.requireNonNull(name, "name");
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
}
