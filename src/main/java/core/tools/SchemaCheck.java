package core.tools;

import core.schema.PacketDef;
import core.schema.ResponseRule;
import core.schema.SchemaException;
import core.schema.SchemaLoader;
import core.schema.SchemaSet;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line schema validator for build pipelines.
 *
 * <pre>
 * java -cp "lib/*" core.tools.SchemaCheck [path ...]     (default: ./schemas)
 * gradlew checkSchemas                                    (also runs as part of gradlew check)
 * </pre>
 *
 * Each argument is a schema directory (merged and validated as the engine would load it) or a
 * single file. Exit code 0 when every target is valid, 1 otherwise; problems go to stderr.
 */
public final class SchemaCheck {

    private SchemaCheck() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        List<Path> targets = new ArrayList<>();
        if (args == null || args.length == 0) {
            targets.add(Path.of("schemas"));
        } else {
            for (String arg : args) {
                targets.add(Path.of(arg));
            }
        }

        int failures = 0;
        for (Path target : targets) {
            try {
                SchemaSet set;
                if (Files.isDirectory(target)) {
                    set = SchemaLoader.loadDirectory(target);
                } else if (Files.isRegularFile(target)) {
                    set = SchemaLoader.parse(Files.readString(target, StandardCharsets.UTF_8),
                            target.getFileName().toString());
                } else {
                    failures++;
                    err.println("FAIL " + target + ": no such file or directory");
                    continue;
                }
                out.printf("OK   %s: %d packet(s), %d struct(s), %d response rule(s) from %d file(s)%n", target,
                        set.packets().size(), set.structs().size(), set.rules().size(), set.sources().size());
                for (PacketDef packet : set.packets()) {
                    out.printf("     0x%04X %-28s %d field(s)%s%n", packet.opcode(), packet.name(),
                            packet.fields().size(), packet.requiresHandshake() ? "" : " [no handshake]");
                }
                for (ResponseRule rule : set.rules()) {
                    out.printf("     rule   %s (%d template field(s))%n", rule, rule.templates().size());
                }
            } catch (SchemaException e) {
                failures++;
                err.println("FAIL " + target);
                for (String line : e.getMessage().split("\\R")) {
                    err.println("     " + line);
                }
            } catch (IOException e) {
                failures++;
                err.println("FAIL " + target + ": " + e.getMessage());
            }
        }
        out.println(failures == 0 ? "schema check passed" : "schema check failed: " + failures + " target(s) with problems");
        return failures == 0 ? 0 : 1;
    }
}
