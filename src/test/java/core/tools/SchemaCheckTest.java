package core.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaCheckTest {

    private record Run(int code, String out, String err) {
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SchemaCheck.run(args, new PrintStream(out, true), new PrintStream(err, true));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void validDirectoryPasses(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.json"), """
                { "packets": [ { "name": "A", "opcode": 256, "fields": [ {"name":"x","type":"u8"} ] },
                               { "name": "B", "opcode": 257, "fields": [ {"name":"x","type":"u8"} ] } ],
                  "responses": [ { "on": "A", "send": "B", "fields": { "x": "${request.x}" } } ] }
                """, StandardCharsets.UTF_8);
        Run run = run(dir.toString());
        assertEquals(0, run.code(), run.err());
        assertTrue(run.out().contains("OK   " + dir), run.out());
        assertTrue(run.out().contains("0x0100 A"), run.out());
        assertTrue(run.out().contains("rule   A -> B"), run.out());
        assertTrue(run.out().contains("schema check passed"), run.out());
        assertEquals("", run.err());
    }

    @Test
    void invalidFileFailsWithEveryProblemListed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bad.json");
        Files.writeString(file, """
                { "packets": [ { "name": "A", "opcode": 5 }, { "name": "B", "opcode": 300, "fields": [ {"name":"x","type":"nope"} ] } ] }
                """, StandardCharsets.UTF_8);
        Run run = run(file.toString());
        assertEquals(1, run.code());
        assertTrue(run.err().contains("FAIL " + file), run.err());
        assertTrue(run.err().contains("reserved"), run.err());
        assertTrue(run.err().contains("unknown field type 'nope'"), run.err());
        assertTrue(run.out().contains("schema check failed: 1 target(s)"), run.out());
    }

    @Test
    void missingTargetFails(@TempDir Path dir) {
        Run run = run(dir.resolve("absent").toString());
        assertEquals(1, run.code());
        assertTrue(run.err().contains("no such file or directory"), run.err());
    }

    @Test
    void shippedExampleSchemaIsValid() {
        // Gradle runs tests with the project directory as the working directory.
        Run run = run("schemas");
        assertEquals(0, run.code(), run.err());
        assertTrue(run.out().contains("HelloRequest"), run.out());
        assertTrue(run.out().contains("3 response rule(s)"), run.out());
    }
}
