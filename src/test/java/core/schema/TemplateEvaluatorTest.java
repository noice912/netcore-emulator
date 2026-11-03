package core.schema;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateEvaluatorTest {

    private static SchemaPacket request() throws SchemaException {
        SchemaSet set = SchemaLoader.parse("""
                { "structs": { "P": [ {"name":"x","type":"i32"} ] },
                  "packets": [ { "name": "Req", "opcode": 256, "fields": [
                    {"name":"name","type":"string"}, {"name":"pos","type":"struct","struct":"P"},
                    {"name":"ids","type":"array","element":"u8"} ] } ] }
                """, "t");
        return new SchemaPacket(set.packet("Req").orElseThrow(), set)
                .set("name", "zed").set("pos", Map.of("x", 7)).set("ids", List.of(5, 6));
    }

    @Test
    void wholePlaceholderKeepsTheValueType() throws Exception {
        assertInstanceOf(Long.class, TemplateEvaluator.evaluate("${now.millis}", null, null));
        assertEquals(7L, TemplateEvaluator.evaluate("${request.pos.x}", request(), null));
        assertEquals(6L, TemplateEvaluator.evaluate("${request.ids.1}", request(), null));
        assertInstanceOf(Map.class, TemplateEvaluator.evaluate("${request.pos}", request(), null));
    }

    @Test
    void embeddedPlaceholdersAreStringified() throws Exception {
        Object value = TemplateEvaluator.evaluate("hi ${request.name}, x=${request.pos.x}!", request(), null);
        assertEquals("hi zed, x=7!", value);
    }

    @Test
    void unknownReferencesResolveToNull() {
        assertNull(TemplateEvaluator.evaluate("${nope.thing}", null, null));
        assertEquals("[]", TemplateEvaluator.evaluate("[${nope.thing}]", null, null));
    }

    @Test
    void containersAreEvaluatedRecursively() {
        Object value = TemplateEvaluator.evaluate(
                Map.of("a", "${random.int}", "b", List.of("${now.seconds}", "plain")), null, null);
        Map<?, ?> map = (Map<?, ?>) value;
        assertInstanceOf(Long.class, map.get("a"));
        List<?> list = (List<?>) map.get("b");
        assertInstanceOf(Long.class, list.get(0));
        assertEquals("plain", list.get(1));
    }

    @Test
    void literalsPassThroughUntouched() {
        assertEquals(5L, TemplateEvaluator.evaluate(5L, null, null));
        assertEquals(true, TemplateEvaluator.evaluate(true, null, null));
        assertEquals("no placeholders", TemplateEvaluator.evaluate("no placeholders", null, null));
        assertNull(TemplateEvaluator.evaluate(null, null, null));
    }

    @Test
    void timeAndRandomReferencesProduceSensibleValues() {
        long millis = (Long) TemplateEvaluator.evaluate("${now.millis}", null, null);
        long seconds = (Long) TemplateEvaluator.evaluate("${now.seconds}", null, null);
        assertTrue(Math.abs(millis / 1000 - seconds) <= 1);
        assertTrue(((String) TemplateEvaluator.evaluate("${now.iso}", null, null)).endsWith("Z"));
        assertTrue(((Long) TemplateEvaluator.evaluate("${random.int}", null, null)) >= 0);
        assertEquals(36, ((String) TemplateEvaluator.evaluate("${random.uuid}", null, null)).length());
    }
}
