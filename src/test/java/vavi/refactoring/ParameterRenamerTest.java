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
import java.util.Set;
import java.util.stream.Collectors;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vavi.refactoring.JavaProject.Method;
import vavi.refactoring.ParameterRenamer.Rename;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ParameterRenamerTest {

    @TempDir
    Path dir;

    Path write(String name, String source) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, source);
        return p;
    }

    ParameterRenamer run(RenameOptions options, NameMap map) throws IOException {
        ParameterRenamer renamer = new ParameterRenamer(new JavaProject(List.of(dir)), options);
        if (map != null) renamer.apply(map);
        renamer.infer();
        return renamer;
    }

    /** "Owner.method#index" -> new name */
    static Map<String, String> names(ParameterRenamer renamer) {
        return renamer.renames().stream().collect(Collectors.toMap(r -> r.method().ownerName() + "." + r.method().name() + "#" + r.index(), Rename::to, (a, b) -> a));
    }

    @Test
    void propagatesFromCallees() throws Exception {
        write("Api.java", """
                class Api {
                    static void MoveTo(short h, short v) {}
                }
                """);
        write("Port.java", """
                class Port {
                    static void MoveTo(short paramShort1, short paramShort2) { Api.MoveTo(paramShort1, paramShort2); }
                    void moveTo(int paramInt1, int paramInt2) { MoveTo((short) paramInt1, (short) paramInt2); }
                }
                """);
        write("User.java", """
                class User {
                    void go(Port paramPort, int paramInt1, int paramInt2) { paramPort.moveTo(paramInt1, paramInt2); }
                }
                """);
        Map<String, String> names = names(run(new RenameOptions(), null));
        assertEquals("h", names.get("Port.MoveTo#0"));
        assertEquals("v", names.get("Port.MoveTo#1"));
        assertEquals("h", names.get("Port.moveTo#0"));
        assertEquals("h", names.get("User.go#1"));
        // single letters are copied from C API like functions only (and x, y, z, w, h, e from anything)
        assertFalse(names.containsKey("User.go#2"));
        assertEquals("port", names.get("User.go#0")); // type fallback
    }

    @Test
    void settersFieldsOverrides() throws Exception {
        write("Base.java", """
                abstract class Base {
                    abstract void play(float rate);
                }
                """);
        write("Player.java", """
                class Player extends Base {
                    private float volume;
                    private int mDuration;
                    Player(int paramInt) { this.mDuration = paramInt; }
                    void setVolume(float paramFloat) { this.volume = paramFloat; }
                    void setVolume(int paramInt) { volume = paramInt; }
                    @Override void play(float paramFloat) {}
                }
                """);
        ParameterRenamer renamer = run(new RenameOptions(), null);
        Map<String, String> names = names(renamer);
        Map<String, String> setters = renamer.renames().stream().filter(r -> r.method().name().equals("setVolume"))
                .collect(Collectors.toMap(r -> r.method().signature(), Rename::to));
        assertEquals("duration", names.get("Player.<init>#0"));
        assertEquals("volume", setters.get("float"));
        // "volume = volume" would assign the parameter to itself
        assertFalse(setters.containsKey("int"));
        assertEquals("rate", names.get("Player.play#0"));
    }

    @Test
    void neverClashes() throws Exception {
        write("Api.java", """
                class Api {
                    static void Draw(int port, int rect) {}
                }
                """);
        write("Canvas.java", """
                class Canvas {
                    int rect;
                    void draw(int paramInt1, int paramInt2) {
                        int port = 0;
                        Api.Draw(paramInt1, paramInt2 + rect);
                        Api.Draw(paramInt2, 0);
                    }
                }
                """);
        ParameterRenamer renamer = run(new RenameOptions(), null);
        Map<String, String> names = names(renamer);
        assertFalse(names.containsKey("Canvas.draw#0"), "'port' is a local");
        assertFalse(names.containsKey("Canvas.draw#1"), "'port' is a local, 'rect' is a field referenced unqualified");
        assertEquals(1, renamer.unresolved().size());
    }

    @Test
    void choosesOverloadByArgumentTypes() throws Exception {
        write("Component.java", """
                class Component {
                    Component(int type, int subType) {}
                    Component(int nativeRep, Object ref) {}
                }
                """);
        write("Player.java", """
                class Player extends Component {
                    Player(int paramInt, Object paramObject) { super(paramInt, paramObject); }
                }
                """);
        Map<String, String> names = names(run(new RenameOptions(), null));
        assertEquals("nativeRep", names.get("Player.<init>#0"));
        assertEquals("ref", names.get("Player.<init>#1"));
    }

    @Test
    void nameMapAndPoison() throws Exception {
        write("Lib.java", """
                class Lib {
                    static void Set(int startAngle, int value) {}
                    static void Stub(int paramInt1, int paramInt2) {}
                    static void Stub(byte[] paramArrayOfByte, int paramInt) {}
                    static void wrong(int time, int[] paramArrayOfInt) {}
                    void call(int paramInt1, int paramInt2) { Set(paramInt1, paramInt2); }
                }
                """);
        NameMap map = NameMap.parse("""
                # comment
                Lib.Stub(int,int)  a b
                Lib.Stub/2         data -        # typed key above wins for (int,int)
                Lib.wrong/2        =movie time
                Lib.nothing/1      x
                """);
        ParameterRenamer renamer = run(new RenameOptions().poisonNames(Set.of("startAngle")).typeFallback(false), map);
        Map<String, String> names = names(renamer);
        assertEquals("a", names.get("Lib.Stub#0"));
        assertEquals("movie", names.get("Lib.wrong#0"));
        assertEquals("time", names.get("Lib.wrong#1"));
        assertFalse(names.containsKey("Lib.call#0"), "poisoned");
        assertEquals("value", names.get("Lib.call#1"));
        assertTrue(renamer.warnings().stream().anyMatch(w -> w.contains("Lib.nothing/1")), renamer.warnings().toString());
        Method stub = renamer.project().methods().stream().filter(m -> m.name().equals("Stub") && m.signature().equals("byte[],int")).findFirst().get();
        assertEquals("data", stub.name(0));
        assertEquals("paramInt", stub.name(1));
    }

    @Test
    void writesCompilableSources() throws Exception {
        Path api = write("p/Api.java", """
                package p;
                public class Api {
                    public static int Get(int movie, int index) { return movie + index; }
                }
                """);
        Path user = write("p/User.java", """
                package p;

                import java.util.function.IntUnaryOperator;

                public class User {
                    /**
                     * paramInt is the movie, the paramInt1 word stays.
                     * @param paramInt the movie
                     * @param paramInt2 the index
                     */
                    public int get(int paramInt, int paramInt2) {
                        IntUnaryOperator f = paramInt1 -> paramInt1 + 1;
                        Runnable r = new Runnable() {
                            public void run() { Object paramInt = null; System.out.println(paramInt); }
                        };
                        return f.applyAsInt(Api.Get(paramInt, paramInt2));
                    }
                }
                """);
        ParameterRenamer renamer = run(new RenameOptions(), null);
        List<Path> changed = renamer.write();
        assertEquals(List.of(user), changed);
        String result = Files.readString(user);
        assertTrue(result.contains("public int get(int movie, int index)"), result);
        assertTrue(result.contains("@param movie the movie"), result);
        assertTrue(result.contains("paramInt is the movie"), "javadoc prose is kept");
        assertTrue(result.contains("paramInt1 -> paramInt1 + 1"), "lambda parameter is not a method parameter");
        assertTrue(result.contains("Object paramInt = null; System.out.println(paramInt);"), "a local of a nested class is kept");
        assertTrue(result.contains("Api.Get(movie, index)"), result);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null, "-d", dir.resolve("classes").toString(), api.toString(), user.toString());
        assertEquals(0, rc);
    }

    @Test
    void renameOne() throws Exception {
        Path p = write("A.java", """
                class A {
                    void f(int paramInt1, int paramInt2) {
                        int x = paramInt1;
                        g(paramInt2);
                    }
                    void g(int y) {}
                }
                """);
        ParameterRenamer renamer = new ParameterRenamer(new JavaProject(List.of(p)), new RenameOptions());
        Method f = renamer.find(p, 3, null);
        assertEquals("f", f.name());
        assertThrows(IllegalArgumentException.class, () -> renamer.rename(f, 0, "x"));
        assertThrows(IllegalArgumentException.class, () -> renamer.rename(f, 0, "class"));
        renamer.rename(f, 0, "width");
        renamer.write();
        assertTrue(Files.readString(p).contains("void f(int width, int paramInt2)"));
        assertTrue(Files.readString(p).contains("int x = width;"));
    }

    @Test
    void onlyTypeSpelledNamesByDefault() throws Exception {
        write("A.java", """
                class A {
                    void f(int param1, byte[] paramBlock, int paramString, Class<?>[] paramArrayOfClass, java.util.List<String> paramList2) {}
                }
                """);
        ParameterRenamer renamer = run(new RenameOptions(), null);
        Map<String, String> names = names(renamer);
        assertEquals(Map.of("A.f#3", "classes", "A.f#4", "list2"), names);
        assertEquals(0, renamer.unresolved().size());
    }

    @Test
    void customPattern() throws Exception {
        write("A.java", """
                class A {
                    private String title;
                    void setTitle(String var1) { this.title = var1; }
                    void setName(String var1) { title = var1; }
                    void keep(int paramInt) {}
                }
                """);
        Map<String, String> names = names(run(new RenameOptions().obfuscatedPattern("var\\d+"), null));
        assertEquals("title", names.get("A.setTitle#0"));
        assertEquals("name", names.get("A.setName#0"), "the setter name, not the field referenced unqualified");
        assertFalse(names.containsKey("A.keep#0"));
    }
}
