/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.refactoring.bytecode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class JarDeobfuscatorTest {

    @TempDir
    Path dir;

    @Test
    void run() throws Exception {
        Path ref = Samples.reference(dir);
        Path obf = Samples.obfuscated(dir, ref);
        Path out = dir.resolve("out");
        JarDeobfuscator d = new JarDeobfuscator(obf, List.of(ref), dir.resolve("work"));
        String log = d.run(out, false);

        String counter = Files.readString(out.resolve("sample/Counter.java"));
        String registry = Files.readString(out.resolve("sample/Registry.java"));
        assertTrue(counter.contains("public class Counter"), counter);
        assertTrue(counter.contains("public void add(int amount)"), counter);
        assertTrue(counter.contains("int before = this.count;"), counter);
        assertTrue(counter.contains("StringBuilder text = "), counter);
        assertTrue(registry.contains("Counter counter = new Counter(name);"), registry);
        assertTrue(registry.contains("int result = 0;"), registry);
        assertTrue(registry.contains("public void reset() throws IOException"), registry);
        assertFalse((counter + registry).matches("(?s).*\\bvar\\d+\\b.*"), counter + registry);
        assertTrue(log.contains("locals from the bytecode: "), log);
        assertTrue(Files.exists(dir.resolve("work/src.orig/sample/Counter.java")));

        // the sources compile
        Samples.compile(out, dir.resolve("recompiled"));
    }

    @Test
    void patch() throws Exception {
        Path ref = Samples.reference(dir);
        Path obf = Samples.obfuscated(dir, ref);
        Path work = dir.resolve("work");
        Path out = dir.resolve("out");
        JarDeobfuscator d = new JarDeobfuscator(obf, List.of(ref), work);
        d.match();
        d.prepare();
        d.decompile(out);
        // a fix by hand, against src.orig
        String orig = Files.readString(work.resolve("src.orig/sample/Counter.java"));
        List<String> lines = orig.lines().toList();
        int n = lines.indexOf(lines.stream().filter(l -> l.contains("public int total()")).findFirst().orElseThrow());
        Path patch = dir.resolve("fixes.patch");
        Files.writeString(patch, """
                --- sample/Counter.java
                +++ sample/Counter.java
                @@ -%d,2 +%d,3 @@
                 %s
                +    /** fixed by hand */
                 %s
                """.formatted(n, n, lines.get(n - 1), lines.get(n)));
        new JarDeobfuscator(null, List.of(), work).patch(patch).decompile(out);
        assertTrue(Files.readString(out.resolve("sample/Counter.java")).contains("/** fixed by hand */"));
    }
}
