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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class VersionAlignerTest {

    @TempDir
    Path dir;

    /** writes sample/S.java of both versions */
    private VersionAligner aligner(String a, String b) throws IOException {
        Path pa = dir.resolve("a/sample"), pb = dir.resolve("b/sample");
        Files.createDirectories(pa);
        Files.createDirectories(pb);
        Files.writeString(pa.resolve("S.java"), a);
        Files.writeString(pb.resolve("S.java"), b);
        return new VersionAligner(dir.resolve("a"), dir.resolve("b"));
    }

    private String a() throws IOException {
        return Files.readString(dir.resolve("a/sample/S.java"));
    }

    private String b() throws IOException {
        return Files.readString(dir.resolve("b/sample/S.java"));
    }

    @Test
    void realNameWins() throws IOException {
        VersionAligner va = aligner("""
                package sample;
                class S {
                    int sum(int[] values) {
                        int total = 0;
                        for (int value : values) total += value;
                        return total;
                    }
                }
                """, """
                package sample;
                class S {
                    int sum(int[] values) {
                        int var2 = 0;
                        for (int var4 : values) var2 += var4;
                        return var2;
                    }
                }
                """);
        assertEquals(2, va.alignNames());
        va.write();
        assertEquals(a(), b());
    }

    @Test
    void preferredSideOnTie() throws IOException {
        String a = """
                package sample;
                class S {
                    int f(int n) {
                        int count = n + 1;
                        return count * count;
                    }
                }
                """;
        VersionAligner va = aligner(a, a.replace("count", "size"));
        va.prefer(VersionAligner.Side.B).alignNames();
        va.write();
        assertTrue(a().contains("int size"), a());
        assertEquals(a(), b());
    }

    /** the parameters were reordered, the other version named them by position */
    @Test
    void reorderedParameters() throws IOException {
        VersionAligner va = aligner("""
                package sample;
                class S {
                    int[] colors = new int[16];
                    int[][] g = new int[8][8], planes = new int[8][8];
                    void setPixel(int color, int x, int y) {
                        int high = colors[color >>> 4];
                        int low = colors[color & 15];
                        int mid = colors[color >>> 2];
                        g[x][y] = g[x][y] & 0xff00 | color;
                        planes[x | 4][y] = high;
                        planes[x | 2][y] = low;
                    }
                }
                """, """
                package sample;
                class S {
                    int[] colors = new int[16];
                    int[][] g = new int[8][8], planes = new int[8][8];
                    void setPixel(int color, int x, int y) {
                        int high = colors[y >>> 4];
                        int low = colors[y & 15];
                        int mid = colors[y >>> 2];
                        g[color][x] = g[color][x] & 0xff00 | y;
                        planes[color | 4][x] = high;
                        planes[color | 2][x] = low;
                    }
                }
                """);
        va.alignNames();
        va.write();
        assertTrue(b().contains("void setPixel(int x, int y, int color)"), b());
        assertTrue(b().contains("g[x][y] = g[x][y] & 0xff00 | color;"), b());
    }

    /** a field used unqualified would be captured */
    @Test
    void noCapture() throws IOException {
        VersionAligner va = aligner("""
                package sample;
                class S {
                    int count;
                    int f() {
                        int count = 1;
                        return count + count;
                    }
                }
                """, """
                package sample;
                class S {
                    int count;
                    int f() {
                        int var1 = 1;
                        int x = count;
                        return var1 + var1 + x;
                    }
                }
                """);
        va.alignNames();
        va.write();
        assertTrue(b().contains("int var1 = 1;"), b());
    }

    /** a different type is not the same variable */
    @Test
    void sameTypeOnly() throws IOException {
        VersionAligner va = aligner("""
                package sample;
                class S {
                    Object f() {
                        long size = 1;
                        return size + size;
                    }
                }
                """, """
                package sample;
                class S {
                    Object f() {
                        int var1 = 1;
                        return var1 + var1;
                    }
                }
                """);
        assertEquals(0, va.alignNames());
    }

    @Test
    void labels() throws IOException {
        String a = """
                package sample;
                class S {
                    void f(int[][] m) {
                        outer:
                        for (int[] row : m) {
                            for (int v : row) {
                                if (v == 0) break outer;
                                if (v < 0) continue outer;
                            }
                        }
                    }
                }
                """;
        VersionAligner va = aligner(a, a.replace("outer", "label12"));
        va.alignNames();
        va.write();
        assertEquals(a(), b());
    }

    @Test
    void members() throws IOException {
        VersionAligner va = aligner("""
                package sample;
                class S {
                    static final int MAX = 16;
                    int[] buffer = new int[MAX];
                    int count;
                    static final int MIN = 1;

                    void b() {
                    }

                    S() {
                    }

                    void a(int x) {
                    }
                }
                """, """
                package sample;
                class S {
                    static final int MIN = 1;
                    int count;
                    int[] buffer = new int[MAX];
                    static final int MAX = 16;

                    S() {
                    }

                    void a(int x) {
                    }

                    void b() {
                    }
                }
                """);
        assertEquals(5, va.alignMembers());
        va.write();
        List<String> lines = Files.readAllLines(dir.resolve("a/sample/S.java"));
        // fields with an initializer stay, the others take the places in the order of b
        assertEquals("    static final int MIN = 1;", lines.get(2));
        assertEquals("    int[] buffer = new int[MAX];", lines.get(3));
        assertEquals("    int count;", lines.get(4));
        assertEquals("    static final int MAX = 16;", lines.get(5));
        assertTrue(a().indexOf("S()") < a().indexOf("void a(") && a().indexOf("void a(") < a().indexOf("void b("), a());
    }

    @Test
    void aliases() throws IOException {
        VersionAligner va = aligner("""
                package sample;
                class S {
                    void readType1() {
                    }

                    void readType2() {
                    }
                }
                """, """
                package sample;
                class S {
                    void readType3() {
                    }

                    void readType2() {
                    }
                }
                """);
        Path aliases = dir.resolve("aliases.txt");
        Files.writeString(aliases, "# renumbered\nsample/S.java readType1 readType3\nsample/S.java readType2 readType1\n");
        va.aliases(aliases);
        assertEquals(0, va.alignMembers()); // readType1 (a) = readType3 (b) comes first already
        Files.writeString(aliases, "sample/S.java readType1 readType2\nsample/S.java readType2 readType3\n");
        va = new VersionAligner(dir.resolve("a"), dir.resolve("b")).aliases(aliases);
        assertEquals(2, va.alignMembers());
        va.write();
        assertTrue(a().indexOf("readType2") < a().indexOf("readType1"), a());
    }

    @Test
    void sequenceMatcher() {
        var sm = new VersionAligner.SequenceMatcher(List.of("a", "b", "x", "c", "d"), List.of("a", "b", "y", "c", "d", "e"));
        List<int[]> ops = sm.opcodes();
        assertEquals(4, ops.size());
        assertEquals(List.of(0, 0, 2, 0, 2), List.of(ops.get(0)[0], ops.get(0)[1], ops.get(0)[2], ops.get(0)[3], ops.get(0)[4]));
        assertEquals(1, ops.get(1)[0]); // x -> y
        assertEquals(0, ops.get(2)[0]);
        assertEquals(3, ops.get(3)[0]); // e
        assertFalse(sm.matchingBlocks().isEmpty());
    }
}
