package core.schema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaLoaderTest {

    @Test
    void parsesValidSchemaWithHexAndDecimalOpcodes() throws SchemaException {
        SchemaSet set = SchemaLoader.parse("""
                { "packets": [
                    { "name": "A", "opcode": "0x0100", "fields": [ {"name":"n","type":"u8"} ] },
                    { "name": "B", "opcode": 300, "requiresHandshake": false, "description": "second" } ],
                  "responses": [ { "on": "A", "send": "B" } ] }
                """, "test");
        assertEquals(2, set.packets().size());
        assertEquals(0x0100, set.packet("A").orElseThrow().opcode());
        assertEquals(300, set.packet("B").orElseThrow().opcode());
        assertFalse(set.packet("B").orElseThrow().requiresHandshake());
        assertEquals("second", set.packet("B").orElseThrow().description());
        assertTrue(set.packet(300).isPresent());
        assertEquals(1, set.rulesFor("A").size());
        assertTrue(set.rulesFor("B").isEmpty());
        assertEquals("B", set.rules().get(0).send());
    }

    @Test
    void reportsEveryProblemAtOnce() {
        SchemaException e = assertThrows(SchemaException.class, () -> SchemaLoader.parse("""
                { "packets": [
                    { "name": "Reserved", "opcode": 5 },
                    { "name": "BadType", "opcode": 256, "fields": [ {"name":"x","type":"u128"} ] },
                    { "name": "BadRef", "opcode": 257, "fields": [ {"name":"p","type":"struct","struct":"Nope"} ] },
                    { "name": "BadRef", "opcode": 258 } ] }
                """, "f.json"));
        String message = e.getMessage();
        assertTrue(message.contains("reserved"), message);
        assertTrue(message.contains("u128"), message);
        assertTrue(message.contains("unknown struct 'Nope'"), message);
        assertTrue(message.contains("already defined"), message);
    }

    @Test
    void duplicateOpcodesAreRejected() {
        SchemaException e = assertThrows(SchemaException.class, () -> SchemaLoader.parse("""
                { "packets": [ { "name": "A", "opcode": 256 }, { "name": "B", "opcode": 256 } ] }
                """, "f.json"));
        assertTrue(e.getMessage().contains("reuses opcode"));
    }

    @Test
    void structCyclesAreRejected() {
        SchemaException e = assertThrows(SchemaException.class, () -> SchemaLoader.parse("""
                { "structs": {
                    "A": [ {"name":"b","type":"struct","struct":"B"} ],
                    "B": [ {"name":"a","type":"struct","struct":"A"} ] } }
                """, "f.json"));
        assertTrue(e.getMessage().contains("struct cycle"), e.getMessage());
    }

    @Test
    void responseRulesMustReferenceKnownPacketsAndFields() {
        SchemaException e = assertThrows(SchemaException.class, () -> SchemaLoader.parse("""
                { "packets": [ { "name": "A", "opcode": 256, "fields": [ {"name":"x","type":"u8"} ] } ],
                  "responses": [
                    { "on": "Missing", "send": "A" },
                    { "on": "A", "send": "A", "fields": { "y": 1 } } ] }
                """, "f.json"));
        assertTrue(e.getMessage().contains("unknown packet 'Missing'"), e.getMessage());
        assertTrue(e.getMessage().contains("unknown field 'y'"), e.getMessage());
    }

    @Test
    void nestedArraysAndMissingElementTypesAreRejected() {
        SchemaException e = assertThrows(SchemaException.class, () -> SchemaLoader.parse("""
                { "packets": [ { "name": "A", "opcode": 256, "fields": [
                    {"name":"grid","type":"array","element":"array"},
                    {"name":"list","type":"array"},
                    {"name":"s","type":"struct"} ] } ] }
                """, "f.json"));
        assertTrue(e.getMessage().contains("nested arrays"), e.getMessage());
        assertTrue(e.getMessage().contains("needs an 'element'"), e.getMessage());
        assertTrue(e.getMessage().contains("names no 'struct'"), e.getMessage());
    }

    @Test
    void invalidOpcodesAreRejected() {
        assertThrows(SchemaException.class, () -> SchemaLoader.parse(
                "{ \"packets\": [ { \"name\": \"A\", \"opcode\": \"zz\" } ] }", "f"));
        assertThrows(SchemaException.class, () -> SchemaLoader.parse(
                "{ \"packets\": [ { \"name\": \"A\", \"opcode\": 70000 } ] }", "f"));
        assertThrows(SchemaException.class, () -> SchemaLoader.parse(
                "{ \"packets\": [ { \"name\": \"A\" } ] }", "f"));
    }

    @Test
    void loadDirectoryMergesFilesAndReportsBadOnes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.json"),
                "{ \"packets\": [ { \"name\": \"A\", \"opcode\": 256 } ] }", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("b.json"),
                "{ \"packets\": [ { \"name\": \"B\", \"opcode\": 257 } ], \"responses\": [ { \"on\": \"A\", \"send\": \"B\" } ] }",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("notes.txt"), "ignored", StandardCharsets.UTF_8);

        SchemaSet set = SchemaLoader.loadDirectory(dir);
        assertEquals(2, set.packets().size());
        assertEquals(2, set.sources().size());
        assertEquals(1, set.rules().size(), "rules may reference packets from another file");

        Files.writeString(dir.resolve("c.json"), "{ broken", StandardCharsets.UTF_8);
        SchemaException e = assertThrows(SchemaException.class, () -> SchemaLoader.loadDirectory(dir));
        assertTrue(e.getMessage().contains("c.json"));
    }

    @Test
    void missingDirectoryYieldsEmptySet(@TempDir Path dir) throws Exception {
        SchemaSet set = SchemaLoader.loadDirectory(dir.resolve("does-not-exist"));
        assertTrue(set.isEmpty());
        assertTrue(SchemaSet.empty().isEmpty());
    }

    @Test
    void emptyAndNonJsonDocumentsAreRejected() {
        assertThrows(SchemaException.class, () -> SchemaLoader.parse("   ", "f"));
        assertThrows(SchemaException.class, () -> SchemaLoader.parse("[1,2]", "f"));
    }

    // ---- template validation --------------------------------------------------------------------

    static final String TEMPLATE_SCHEMA_HEAD = """
            { "structs": { "Vec3": [ {"name":"x","type":"f32"}, {"name":"y","type":"f32"} ],
                           "Item": [ {"name":"id","type":"u32"} ] },
              "packets": [
                { "name": "Req", "opcode": 256, "fields": [
                    {"name":"name","type":"string"}, {"name":"count","type":"u16"},
                    {"name":"pos","type":"struct","struct":"Vec3"},
                    {"name":"items","type":"array","element":"struct","struct":"Item"} ] },
                { "name": "Rsp", "opcode": 257, "fields": [
                    {"name":"greeting","type":"string"}, {"name":"count","type":"u16"},
                    {"name":"pos","type":"struct","struct":"Vec3"}, {"name":"firstId","type":"u32"},
                    {"name":"ok","type":"bool"}, {"name":"items","type":"array","element":"struct","struct":"Item"} ] } ],
              "responses": [ { "on": "Req", "send": "Rsp", "fields":
            """;

    private static String rule(String fields) {
        return TEMPLATE_SCHEMA_HEAD + fields + " } ] }";
    }

    @Test
    void validTemplatePathsAndTypesPass() throws SchemaException {
        SchemaSet set = SchemaLoader.parse(rule("""
                { "greeting": "hi ${request.name} #${request.count} at ${now.iso}", "count": "${request.count}",
                  "pos": "${request.pos}", "firstId": "${request.items.0.id}", "ok": true,
                  "items": "${request.items}" }"""), "ok.json");
        assertEquals(1, set.rules().size());
        assertEquals(6, set.rules().get(0).templates().size());
    }

    @Test
    void nestedConstantsAreValidatedFieldByField() throws SchemaException {
        SchemaSet set = SchemaLoader.parse(rule("""
                { "pos": { "x": 1.5, "y": "${request.count}" }, "items": [ { "id": 7 }, { "id": "${request.count}" } ] }"""),
                "ok.json");
        assertEquals(2, set.rules().get(0).templates().size());

        SchemaException e = assertThrows(SchemaException.class, () -> SchemaLoader.parse(rule("""
                { "pos": { "x": 1.5, "z": 2 }, "items": [ { "id": "not a number" } ] }"""), "bad.json"));
        assertTrue(e.getMessage().contains("struct Vec3 has no field 'z'"), e.getMessage());
        assertTrue(e.getMessage().contains("constant for field 'id' is not valid"), e.getMessage());
    }

    @Test
    void unresolvableRequestPathsAreRejectedAtLoadTime() {
        SchemaException e = assertThrows(SchemaException.class, () -> SchemaLoader.parse(rule("""
                { "greeting": "${request.nope}", "count": "${request.pos.z}", "firstId": "${request.items.id}" }"""),
                "bad.json"));
        String m = e.getMessage();
        assertTrue(m.contains("'${request.nope}' does not resolve"), m);
        assertTrue(m.contains("'${request.pos.z}' does not resolve"), m);
        assertTrue(m.contains("'${request.items.id}' does not resolve"), m);
    }

    @Test
    void unknownReferencesAndTypeMismatchesAreRejected() {
        SchemaException e = assertThrows(SchemaException.class, () -> SchemaLoader.parse(rule("""
                { "greeting": "${server.name}", "count": "${request.pos}", "pos": "${request.count}",
                  "firstId": "id=${request.count}", "ok": "maybe", "items": "${request.pos}" }"""), "bad.json"));
        String m = e.getMessage();
        assertTrue(m.contains("unknown template reference '${server.name}'"), m);
        assertTrue(m.contains("'${request.pos}' is struct Vec3 but field 'count' is u16"), m);
        assertTrue(m.contains("'${request.count}' is u16 but field 'pos' is struct Vec3"), m);
        assertTrue(m.contains("field 'firstId' is u32 but the template"), m);
        assertTrue(m.contains("constant for field 'ok' is not valid"), m);
        assertTrue(m.contains("field 'items' is array of struct Item"), m);
    }

    @Test
    void resolvePathWalksStructsAndArrays() throws SchemaException {
        SchemaSet set = SchemaLoader.parse(TEMPLATE_SCHEMA_HEAD + "{} } ] }", "t.json");
        var fields = set.packet("Req").orElseThrow().fields();
        assertEquals(FieldType.F32, SchemaLoader.resolvePath("pos.x", fields, set).type());
        assertEquals(FieldType.U32, SchemaLoader.resolvePath("items.3.id", fields, set).type());
        assertEquals(FieldType.ARRAY, SchemaLoader.resolvePath("items", fields, set).type());
        assertEquals(FieldType.STRUCT, SchemaLoader.resolvePath("items.0", fields, set).type());
        assertEquals(null, SchemaLoader.resolvePath("items.x.id", fields, set));
        assertEquals(null, SchemaLoader.resolvePath("name.length", fields, set));
        assertEquals(null, SchemaLoader.resolvePath("", fields, set));
    }
}
