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
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class LocalSplitterTest {

    @TempDir
    Path dir;

    Path write(String name, String source) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, source);
        return p;
    }

    void assertCompiles(Path... files) {
        String[] args = new String[files.length + 2];
        args[0] = "-d";
        args[1] = dir.resolve("classes").toString();
        for (int i = 0; i < files.length; i++) args[i + 2] = files[i].toString();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args));
    }

    static final String NODE = """
            class Node {
                Object action;
                Node next;
            }
            """;

    /** SimpleActionList.removeTarget of quicktime as JD decompiled it */
    static final String LIST = """
            class List {
                Node list;
                Object getTarget(Node n) { return n.action; }
                void removeTarget(Object target) {
                    if (this.list != null) {
                        Object localObject1;
                        do {
                            localObject1 = getTarget(this.list);
                            if ((localObject1 == null) || (!localObject1.equals(target)))
                                break;
                            this.list = this.list.next;
                        } while (this.list != null);
                        localObject1 = this.list;
                        Node node = localObject1 != null ? ((Node) localObject1).next : null;
                        while (node != null) {
                            if (target.equals(node.action)) {
                                ((Node) localObject1).next = node.next;
                                node = node.next;
                            } else {
                                localObject1 = node;
                                node = node.next;
                            }
                        }
                    }
                }
            }
            """;

    @Test
    void splitsAndRetypes() throws Exception {
        Path node = write("Node.java", NODE);
        Path list = write("List.java", LIST);
        Deobfuscator d = new Deobfuscator(List.of(dir), new RenameOptions()).split(true).run();
        assertEquals(1, d.splitter().splits().size(), d.splitter().splits().toString());
        assertEquals(1, d.splitter().retyped());
        d.write();
        String result = Files.readString(list);
        // the first value is read through equals() and stays an Object, the node piece is a Node
        assertTrue(result.contains("Object localObject1;"), result);
        // named by its type, "node" is taken
        assertTrue(result.contains("Node node2 = this.list;"), result);
        assertTrue(result.contains("node2 != null ? node2.next : null"), result);
        assertTrue(result.contains("node2.next = node.next;"), result);
        assertTrue(result.contains("node2 = node;"), result);
        assertFalse(result.contains("(Node) "), result);
        assertCompiles(node, list);
    }

    @Test
    void dropsAnUnusedDeclaration() throws Exception {
        Path p = write("A.java", """
                class A {
                    Object file() { return new java.io.File("a"); }
                    void f() {
                        Object localObject = null;
                        localObject = (java.io.File) file();
                        System.out.println(((java.io.File) localObject).length());
                        localObject = new Thread();
                        ((Thread) localObject).start();
                    }
                }
                """);
        Deobfuscator d = new Deobfuscator(List.of(dir), new RenameOptions()).split(true).run();
        d.write();
        String result = Files.readString(p);
        assertFalse(result.contains("localObject"), result);
        assertTrue(result.contains("java.io.File file = (java.io.File) file();"), result);
        assertTrue(result.contains("System.out.println(file.length());"), result);
        assertTrue(result.contains("Thread thread = new Thread();"), result);
        assertTrue(result.contains("thread.start();"), result);
        assertCompiles(p);
    }

    @Test
    void keepsWhatCanNotBeProven() throws Exception {
        String source = """
                class A {
                    void print(Object o) {}
                    void print(String s) {}
                    void f(boolean b) {
                        Object localObject = (String) "a";
                        print(localObject);
                        if (b) {
                            localObject = new Thread();
                        }
                        localObject = localObject.toString();
                        print(localObject);
                    }
                }
                """;
        Path p = write("A.java", source);
        Deobfuscator d = new Deobfuscator(List.of(dir), new RenameOptions()).split(true).run();
        // an argument (the overload could change), a nested assignment, a value reading the variable
        assertEquals(0, d.splitter().splits().size(), d.splitter().splits().toString());
        d.write();
        assertEquals(source, Files.readString(p));
    }

    @Test
    void nameMapForLocals() throws Exception {
        Path p = write("A.java", """
                class A {
                    void f() {
                        String str = "a";
                        System.out.println(str);
                        {
                            String str2 = "b";
                            System.out.println(str2);
                        }
                        int count = 1;
                    }
                    void g() {
                        String str = "c";
                        System.out.println(str);
                    }
                }
                """);
        NameMap map = NameMap.parse("""
                A.f/0:str     first
                A.f()         # nothing for parameters
                A.f/0:str2@1  second
                A.f/0:count   total    # not obfuscated, renamed anyway
                A.g/0:missing x
                """.replace("A.f()         # nothing for parameters\n", ""));
        Deobfuscator d = new Deobfuscator(List.of(dir), new RenameOptions()).nameMap(map).locals(true).run();
        assertTrue(d.warnings().stream().anyMatch(w -> w.contains("A.g/0:missing")), d.warnings().toString());
        d.write();
        String result = Files.readString(p);
        assertTrue(result.contains("String first = \"a\";"), result);
        assertTrue(result.contains("System.out.println(second);"), result);
        assertTrue(result.contains("int total = 1;"), result);
        assertTrue(result.contains("String str = \"c\";"), "g is not in the map");
        assertCompiles(p);
    }
}
