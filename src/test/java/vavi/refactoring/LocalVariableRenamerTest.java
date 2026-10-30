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
import java.util.Map;
import java.util.stream.Collectors;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vavi.refactoring.LocalVariableRenamer.Local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class LocalVariableRenamerTest {

    @TempDir
    Path dir;

    Path write(String name, String source) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, source);
        return p;
    }

    LocalVariableRenamer run() throws IOException {
        ParameterRenamer parameters = new ParameterRenamer(new JavaProject(List.of(dir)), new RenameOptions());
        parameters.infer();
        LocalVariableRenamer locals = new LocalVariableRenamer(parameters);
        locals.infer();
        return locals;
    }

    /** "method original@line" -> new name */
    static Map<String, String> names(LocalVariableRenamer locals) {
        return locals.renames().stream().collect(Collectors.toMap(l -> l.method().name() + " " + l.originalName() + "@" + l.line(), Local::name));
    }

    @Test
    void sources() throws Exception {
        write("Api.java", """
                class Api {
                    static int GetMovieBox(int movie, int[] box) { return 0; }
                }
                """);
        write("Movie.java", """
                import java.util.List;

                class Movie {
                    private Track mainTrack;
                    List<Track> tracks;
                    Track getTrack(int index) { return null; }
                    void a() {
                        int[] arrayOfInt = new int[1];
                        Api.GetMovieBox(0, arrayOfInt);
                    }
                    void b() {
                        Track localTrack = getTrack(1);
                        this.mainTrack = localTrack;
                    }
                    void c() {
                        Track localTrack = getTrack(1);
                        localTrack.toString();
                    }
                    void d() {
                        for (Track localTrack : tracks) localTrack.toString();
                        try {
                            c();
                        } catch (RuntimeException localRuntimeException) {
                            throw localRuntimeException;
                        }
                    }
                    Track getFirst() {
                        Track localTrack = tracks.get(0);
                        return localTrack;
                    }
                    void e() {
                        Object localObject = new Movie();
                        String str = "";
                        int[] arrayOfInt = { 1 };
                    }
                }
                """);
        write("Track.java", "class Track {}\n");
        Map<String, String> names = names(run());
        assertEquals("box", names.get("a arrayOfInt@8"));
        assertEquals("mainTrack", names.get("b localTrack@12"));
        assertEquals("track", names.get("c localTrack@16"));
        assertEquals("track", names.get("d localTrack@20"), "for-each, singular of tracks");
        assertEquals("e", names.get("d localRuntimeException@23"));
        assertEquals("first", names.get("getFirst localTrack@28"));
        // nothing tells the meaning
        assertNull(names.get("e localObject@32"));
        assertNull(names.get("e str@33"));
        assertNull(names.get("e arrayOfInt@34"));
    }

    @Test
    void scopes() throws Exception {
        Path p = write("A.java", """
                class A {
                    Thread getThread() { return null; }
                    void f(boolean b) {
                        if (b) {
                            Thread localThread = getThread();
                            localThread.start();
                        } else {
                            Thread localThread = new Thread();
                            localThread.run();
                        }
                        Runnable r = new Runnable() {
                            public void run() {
                                Thread localThread = Thread.currentThread();
                                localThread.interrupt();
                            }
                        };
                        Object o = r;
                        if (o instanceof Thread localThread) localThread.getName();
                    }
                }
                """);
        LocalVariableRenamer locals = run();
        Map<String, String> names = names(locals);
        assertEquals("thread", names.get("f localThread@5"));
        assertEquals("thread", names.get("f localThread@8"), "sibling blocks");
        assertEquals("thread", names.get("run localThread@13"), "nothing of the outer method is visible there");
        // the flow scope of a pattern variable is taken as the whole block around it, so it keeps away from the locals in it
        assertEquals("thread2", names.get("f localThread@18"), "a pattern variable");
        locals.write();
        String result = Files.readString(p);
        assertTrue(result.contains("thread.start();"), result);
        assertTrue(result.contains("thread.run();"), result);
        assertTrue(result.contains("o instanceof Thread thread2) thread2.getName()"), result);
        assertFalse(result.contains("localThread"), result);
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dir.resolve("classes").toString(), p.toString());
        assertEquals(0, rc);
    }

    @Test
    void neverClashes() throws Exception {
        write("A.java", """
                class A {
                    Thread thread;
                    Thread getThread() { return null; }
                    void f() {
                        Thread localThread = getThread();
                        thread = localThread;
                        Thread localThread2 = getThread();
                        int thread2 = 0;
                    }
                }
                """);
        Map<String, String> names = names(run());
        assertFalse(names.containsValue("thread"), names.toString());
        assertFalse(names.containsValue("thread2"), names.toString());
    }

    @Test
    void parametersFirst() throws Exception {
        Path p = write("A.java", """
                class A {
                    static void Draw(int port, int[] rect) {}
                    void draw(int paramInt) {
                        int[] arrayOfInt = new int[4];
                        Draw(paramInt, arrayOfInt);
                    }
                }
                """);
        run().write();
        String result = Files.readString(p);
        assertTrue(result.contains("void draw(int port) {"), result);
        assertTrue(result.contains("int[] rect = new int[4];"), result);
        assertTrue(result.contains("Draw(port, rect);"), result);
    }

    @Test
    void helpers() {
        assertEquals("track", LocalVariableRenamer.getterName("getTrack"));
        assertNull(LocalVariableRenamer.getterName("getIntAt"));
        assertNull(LocalVariableRenamer.getterName("getStringFromPointer"));
        assertEquals("info", LocalVariableRenamer.getterName("getInfo"));
        assertEquals("entry", LocalVariableRenamer.singular("entries"));
        assertEquals("box", LocalVariableRenamer.singular("boxes"));
        assertEquals("listener", LocalVariableRenamer.singular("listeners"));
        assertNull(LocalVariableRenamer.singular("class"));
    }
}
