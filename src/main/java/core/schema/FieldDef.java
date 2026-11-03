package core.schema;

import java.util.Objects;

/**
 * One field of a packet or struct.
 *
 * @param name        field name, unique within its container
 * @param type        wire type
 * @param structName  for {@code struct} fields and {@code array} fields of structs: the struct name
 * @param elementType for {@code array} fields: the element type (never {@code array} itself)
 */
public record FieldDef(String name, FieldType type, String structName, FieldType elementType) {

    public FieldDef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }

    public boolean isArray() {
        return type == FieldType.ARRAY;
    }

    /** Whether this field (or its array elements) embeds a named struct. */
    public boolean requiresStruct() {
        return type == FieldType.STRUCT || (type == FieldType.ARRAY && elementType == FieldType.STRUCT);
    }

    /** The definition used for each element of an array field. */
    public FieldDef element() {
        if (!isArray()) {
            throw new IllegalStateException("field '" + name + "' is not an array");
        }
        return new FieldDef(name + "[]", elementType, structName, null);
    }
}
