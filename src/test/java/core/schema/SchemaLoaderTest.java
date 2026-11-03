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
}
