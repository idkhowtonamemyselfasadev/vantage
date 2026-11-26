package dev.vantage.render;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** STYLE.md: {@code render} may import core, never a concrete module. Scans every source file under render/ and names file and line. */
class RenderImportsTest {
    private static final Path ROOT = Path.of("src/main/java/dev/vantage/render");

    @Test
    void renderNeverNamesAModule() throws IOException {
        assertTrue(Files.isDirectory(ROOT), "run from the mod/ folder, missing " + ROOT.toAbsolutePath());
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains("dev.vantage.module.")) bad.add(f + ":" + (i + 1) + ": " + lines.get(i).trim());
                }
            }
        }
        assertTrue(bad.isEmpty(), "render/ names a module:\n" + String.join("\n", bad));
    }

    @Test
    void r3DoesNotLookUpModules() throws IOException {
        List<String> lines = Files.readAllLines(ROOT.resolve("R3.java"));
        for (int i = 0; i < lines.size(); i++) assertTrue(!lines.get(i).contains("Modules.get("), "R3.java:" + (i + 1) + " calls Modules.get(");
    }
}
