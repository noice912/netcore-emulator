package core.schema;

import java.util.Map;
import java.util.Objects;

/**
 * Declarative reply: when packet {@code on} arrives, build packet {@code send} from the templates
 * and send it back on the same session.
 *
 * @param templates  field name to template value; strings may contain {@code ${...}} placeholders
 *                   (see {@link TemplateEvaluator}); other JSON values are used as-is
 * @param closeAfter close the session once the response has been written
 */
public record ResponseRule(String on, String send, Map<String, Object> templates, boolean closeAfter,
                           String description) {

    public ResponseRule {
        Objects.requireNonNull(on, "on");
        Objects.requireNonNull(send, "send");
        templates = Map.copyOf(Objects.requireNonNull(templates, "templates"));
        description = description == null ? "" : description;
    }

    @Override
    public String toString() {
        return on + " -> " + send + (closeAfter ? " (then close)" : "");
    }
}
