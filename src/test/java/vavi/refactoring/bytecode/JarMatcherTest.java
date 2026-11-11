/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.refactoring.bytecode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class JarMatcherTest {

    @TempDir
    Path dir;

    @Test
    void match() throws Exception {
        Path ref = Samples.reference(dir);
        Path obf = Samples.obfuscated(dir, ref);
        Path mapping = dir.resolve("mapping.txt");
        JarMatcher.capture("match", obf.toString(), ref.toString(), "-o", mapping.toString());
        List<String> lines = Files.readAllLines(mapping);
        assertTrue(lines.contains("a.a -> sample.Counter:"), String.join("\n", lines));
        assertTrue(lines.contains("a.b -> sample.Registry:"), String.join("\n", lines));
        for (String member : List.of("count", "label", "add", "total", "describe", "counters", "create", "sum", "reset")) {
            assertTrue(lines.stream().anyMatch(l -> l.endsWith(" -> " + member)), member + "\n" + String.join("\n", lines));
        }
    }

    @Test
    void renameBack() throws Exception {
        Path ref = Samples.reference(dir);
        Path obf = Samples.obfuscated(dir, ref);
        Path mapping = dir.resolve("mapping.txt");
        JarMatcher.capture("match", obf.toString(), ref.toString(), "-o", mapping.toString());
        Path renamed = dir.resolve("renamed.jar");
        JarMatcher.capture("rename", obf.toString(), mapping.toString(), renamed.toString());
        // the locals of the renamed jar: the same methods as the reference's
        Path locals = dir.resolve("locals.txt");
        JarMatcher.capture("locals", renamed.toString(), ref.toString(), "-o", locals.toString());
        List<String> lines = Files.readAllLines(locals);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("sample.Counter.add(int) ") && l.contains(":before")), String.join("\n", lines));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("sample.Counter.describe() ") && l.contains(":text")), String.join("\n", lines));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("sample.Registry.create(java.lang.String) ") && l.contains(":counter")), String.join("\n", lines));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("sample.Registry.sum() ") && l.contains(":result")), String.join("\n", lines));
    }

    @Test
    void usage() {
        assertThrows(IllegalArgumentException.class, () -> JarMatcher.capture("match", "x"));
        assertThrows(IllegalArgumentException.class, () -> JarMatcher.capture("nothing", "x"));
    }

    @Test
    void capture() throws IOException {
        Path ref = Samples.reference(dir);
        Path obf = Samples.obfuscated(dir, ref);
        String log = JarMatcher.capture("unshare", obf.toString(), dir.resolve("u.jar").toString(), "--all");
        assertTrue(log.contains("methods changed"), log);
    }
}
