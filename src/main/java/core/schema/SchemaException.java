package core.schema;

/** A schema file could not be parsed or failed validation. The message lists every problem found. */
public class SchemaException extends Exception {

    private static final long serialVersionUID = 1L;

    public SchemaException(String message) {
        super(message);
    }

    public SchemaException(String message, Throwable cause) {
        super(message, cause);
    }
}
