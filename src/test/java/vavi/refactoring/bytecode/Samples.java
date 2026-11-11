/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.refactoring.bytecode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * Sample classes: an old version compiled with a LocalVariableTable (the reference) and an
 * obfuscated jar made of it.
 */
class Samples {

    static final String COUNTER = """
            package sample;

            public class Counter {
                private int count;
                private final String label;

                public Counter(String label) {
                    this.label = label;
                }

                public void add(int amount) {
                    int before = count;
                    count += amount;
                    if (count < before) throw new IllegalStateException("overflow " + label);
                }

                public int total() {
                    return count;
                }

                public String describe() {
                    StringBuilder text = new StringBuilder(label);
                    text.append('=').append(count);
                    return text.toString();
                }
            }
            """;

    static final String REGISTRY = """
            package sample;

            import java.util.ArrayList;
            import java.util.List;

            public class Registry {
                private final List<Counter> counters = new ArrayList<>();

                public Counter create(String name) {
                    Counter counter = new Counter(name);
                    counters.add(counter);
                    return counter;
                }

                public int sum() {
                    int result = 0;
                    for (Counter c : counters) result += c.total();
                    return result;
                }

                public void reset() throws java.io.IOException {
                    if (counters.isEmpty()) throw new java.io.IOException("empty");
                    counters.clear();
                }
            }
            """;

    /** the real names → obfuscated, proguard format */
    static final String OBFUSCATION = """
            sample.Counter -> a.a:
                int count -> a
                java.lang.String label -> b
                void add(int) -> a
                int total() -> b
                java.lang.String describe() -> c
            sample.Registry -> a.b:
                java.util.List counters -> a
                sample.Counter create(java.lang.String) -> a
                int sum() -> b
                void reset() -> c
            """;

    /** compiles the sources into dir */
    static void compile(Path src, Path dir, String... options) throws IOException {
        List<String> args = new ArrayList<>(List.of(options));
        args.addAll(List.of("-d", dir.toString()));
        try (Stream<Path> s = Files.walk(src)) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
        }
        Files.createDirectories(dir);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)), "javac " + args);
    }

    /** writes the sources, @return the source directory */
    static Path sources(Path dir) throws IOException {
        Path src = dir.resolve("sample-src");
        Files.createDirectories(src.resolve("sample"));
        Files.writeString(src.resolve("sample/Counter.java"), COUNTER);
        Files.writeString(src.resolve("sample/Registry.java"), REGISTRY);
        return src;
    }

    /** the old version with a LocalVariableTable, @return the class directory */
    static Path reference(Path dir) throws IOException {
        Path classes = dir.resolve("ref");
        compile(sources(dir), classes, "-g");
        return classes;
    }

    /** the old version, obfuscated, @return the jar */
    static Path obfuscated(Path dir, Path reference) throws IOException {
        Path mapping = dir.resolve("obfuscation.txt");
        Files.writeString(mapping, OBFUSCATION);
        Path jar = dir.resolve("obf.jar");
        JarMatcher.capture("rename", reference.toString(), mapping.toString(), jar.toString());
        return jar;
    }
}
