/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.refactoring;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class LocalNameTransferTest {

    @TempDir
    Path dir;

    static final String BOX = """
            package sample;

            public class Box {
                private int total;

                int run(int n) {
                    int var2 = n * 2;
                    Runnable var3 = () -> {
                        int var4 = 1;
                        System.out.println(var4);
                    };
                    int var5 = var2;
                    {
                        int var6 = var5 + 1;
                        this.total = var6;
                    }
                    var3.run();
                    return var5;
                }

                Box(java.util.List<String> var1) {
                    int var2 = var1.size();
                    this.total = var2;
                }

                class Inner {
                    int get(int... values) {
                        int var2 = values.length;
                        return var2;
                    }
                }
            }
            """;

    static final String LOCALS = """
            # method slot:name
            sample.Box.run(int) 2:doubled 3:task 4:wrong 5:value 6:value
            sample.Box.<init>(java.util.List) 2:size
            sample.Box$Inner.<init>(sample.Box)
            sample.Box$Inner.get(int[]) 2:count
            """;

    String transfer() throws IOException {
        Path file = dir.resolve("sample/Box.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, BOX);
        Deobfuscator d = new Deobfuscator(List.of(dir), new RenameOptions().obfuscatedPattern("var\\d+").typeFallback(false))
                .transfer(LocalNameTransfer.parse(LOCALS))
                .localPattern("var\\d+")
                .infer(false)
                .run();
        d.write();
        assertEquals(6, d.transferred(), d.warnings().toString());
        return Files.readString(file);
    }

    @Test
    void names() throws Exception {
        String s = transfer();
        assertTrue(s.contains("int doubled = n * 2;"), s);
        assertTrue(s.contains("Runnable task = () -> {"), s);
        assertTrue(s.contains("int value = doubled;"), s);
        assertTrue(s.contains("int size = var1.size();"), s); // a parameter is not a local
        assertTrue(s.contains("int count = values.length;"), s); // varargs, inner class
    }

    @Test
    void lambda() throws Exception {
        String s = transfer();
        assertTrue(s.contains("int var4 = 1;"), s); // the slots of a lambda are those of another method
    }

    @Test
    void clash() throws Exception {
        String s = transfer();
        assertTrue(s.contains("int value2 = value + 1;"), s);
    }

    @Test
    void simpleType() {
        assertEquals("D[]", LocalNameTransfer.simpleType("a.b.C$D[]"));
        assertEquals("List", LocalNameTransfer.simpleType("java.util.List<java.lang.String>"));
        assertEquals("Entry[]", LocalNameTransfer.simpleType("Map.Entry<K, V>..."));
    }
}
