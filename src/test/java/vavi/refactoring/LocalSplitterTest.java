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

    @Test
    void splitsInSwitch() throws Exception {
        Path node = write("Node.java", NODE);
        Path p = write("A.java", """
                class A {
                    Node head;
                    void f(int k) {
                        switch (k) {
                        case 0:
                            Object localObject = new Thread();
                            ((Thread) localObject).start();
                            localObject = this.head;
                            ((Node) localObject).next = null;
                            break;
                        case 1:
                            localObject = new Thread();
                            ((Thread) localObject).run();
                            break;
                        }
                    }
                }
                """);
        Deobfuscator d = new Deobfuscator(List.of(dir), new RenameOptions()).split(true).run();
        assertEquals(1, d.splitter().splits().size(), d.splitter().splits().toString());
        d.write();
        String result = Files.readString(p);
        assertTrue(result.contains("Node node = this.head;"), result);
        assertTrue(result.contains("node.next = null;"), result);
        // the assignment in case 1 starts a piece of its own (a Thread), a jump there leaves the variable unassigned
        assertCompiles(node, p);
    }

    @Test
    void retypesForLoops() throws Exception {
        Path node = write("Node.java", NODE);
        Path p = write("A.java", """
                class A {
                    Node head;
                    int count() {
                        int n = 0;
                        for (Object localObject = this.head; localObject != null; localObject = ((Node) localObject).next) {
                            if (((Node) localObject).action != null) n++;
                        }
                        return n;
                    }
                }
                """);
        Deobfuscator d = new Deobfuscator(List.of(dir), new RenameOptions()).split(true).run();
        assertEquals(1, d.splitter().retyped(), d.splitter().splits().toString());
        d.write();
        String result = Files.readString(p);
        assertTrue(result.contains("for (Node node = this.head; node != null; node = node.next) {"), result);
        assertTrue(result.contains("if (node.action != null) n++;"), result);
        assertCompiles(node, p);
    }

    @Test
    void varForLongTypes() throws Exception {
        Path p = write("A.java", """
                import java.util.concurrent.ConcurrentHashMap;

                class A {
                    void f() {
                        Object localObject = null;
                        localObject = new ConcurrentHashMap<String, Thread>();
                        ((ConcurrentHashMap<String, Thread>) localObject).clear();
                        localObject = (Thread) Thread.currentThread();
                        ((Thread) localObject).interrupt();
                    }
                }
                """);
        new Deobfuscator(List.of(dir), new RenameOptions()).split(true).varOver(20).run().write();
        String result = Files.readString(p);
        assertTrue(result.contains("var concurrentHashMap = new ConcurrentHashMap<String, Thread>();"), result);
        assertTrue(result.contains("concurrentHashMap.clear();"), result);
        assertTrue(result.contains("Thread thread = (Thread) Thread.currentThread();"), "a short type stays");
        assertCompiles(p);
    }

    @Test
    void retypesMethodReturnValues() throws Exception {
        Path node = write("Node.java", NODE);
        Path p = write("A.java", """
                import java.util.ArrayList;
                import java.util.List;

                class A {
                    List<Node> nodes = new ArrayList<>();
                    Node first() { return nodes.get(0); }
                    void f() {
                        Object localObject = null;
                        localObject = first();
                        ((Node) localObject).next = null;
                        localObject = Thread.currentThread();
                        ((Thread) localObject).interrupt();
                        localObject = nodes.get(1);
                        ((Node) localObject).action = null;
                    }
                }
                """);
        Deobfuscator d = new Deobfuscator(List.of(dir), new RenameOptions()).split(true).run();
        assertEquals(3, d.splitter().retyped(), d.splitter().splits().toString());
        d.write();
        String result = Files.readString(p);
        assertTrue(result.contains("Node node = first();"), "a method of the project: " + result);
        assertTrue(result.contains("Thread thread = Thread.currentThread();"), "a jdk method: " + result);
        assertTrue(result.contains("Node node3 = nodes.get(1);"), "a generic return type: " + result);
        assertFalse(result.contains("(Node) "), result);
        assertCompiles(node, p);
    }
}
