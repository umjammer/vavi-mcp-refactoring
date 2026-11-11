/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.refactoring.bytecode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodBuilder;
import java.lang.classfile.MethodElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.LocalVariableInfo;
import java.lang.classfile.attribute.LocalVariableTableAttribute;
import java.lang.classfile.attribute.MethodParameterInfo;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.ConstantPool;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.Label;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.DiscontinuedInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.LocalVariableType;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;


/**
 * Derives a de-obfuscation mapping for an obfuscated jar from reference jars (older versions,
 * obfuscated or not) by structural matching, and prepares the jar for decompiling.
 * <p>
 * Nothing in here knows about a particular project. Requires JDK 24+
 * (java.lang.classfile), no other dependency. The commands ({@link #run(String...)}, {@link #main(String[])}):
 * <pre>
 *   JarMatcher match &lt;obf&gt; &lt;ref&gt; [&lt;ref2&gt; ...] -o mapping.txt [-p params.txt] [-r report.txt]
 *                              [--keep &lt;regex&gt;] [--min &lt;score&gt;]
 *       &lt;obf&gt;, &lt;ref&gt;: jar or classes directory. With several references the matches are
 *       chained (obf -&gt; ref -&gt; ref2), names and parameter names are taken from the last one.
 *       e.g. new obfuscated jar -&gt; old obfuscated jar -&gt; old de-obfuscated classes.
 *       Everything without a counterpart gets a stable, unique placeholder name
 *       (class Obf_x_y, field f_x_y__name, method m_x_y__name) to be named by hand,
 *       except the names the obfuscator did not touch (--keep, default: 4 letters or more).
 *       The report lists those as HINT too, when the reference names them differently.
 *       Matches scoring under --min (default 0.5) are not applied, the report lists them as LOW.
 *
 *   JarMatcher names &lt;mapping.txt&gt; &lt;names.txt&gt; -o &lt;out.txt&gt; [-p &lt;params.txt&gt; &lt;params.out.txt&gt;]
 *       replaces placeholders, names.txt lines: "placeholder newName" ('#' comment).
 *       a class placeholder may be given a fully qualified name to move it.
 *       "pkg.Class.member newName" renames any member (all of its overloads) to fix a name by hand.
 *
 *   JarMatcher compose &lt;a2b.txt&gt; &lt;b2c.txt&gt; -o &lt;a2c.txt&gt;
 *       composes two proguard mappings (a later version: match new.jar old.jar, then compose
 *       with the old jar's mapping).
 *
 *   JarMatcher rename &lt;in.jar&gt; &lt;mapping.txt&gt; &lt;out.jar&gt;
 *       applies the mapping (instead of proguard -applymapping, which cannot give two names to
 *       methods the obfuscator named alike although one cannot override the other).
 *
 *   JarMatcher throws &lt;in.jar&gt; &lt;out.jar&gt;
 *       derives the throws clauses the obfuscator dropped from the code.
 *
 *   JarMatcher inject &lt;in.jar&gt; &lt;params.txt&gt; &lt;out.jar&gt; [--lvt]
 *       writes parameter names (LocalVariableTable + MethodParameters) into a jar
 *       so that decompilers emit them. params.txt lines: "pkg.Owner.method(int,byte[]) name1 name2"
 *
 *   JarMatcher unshare &lt;in.jar&gt; &lt;out.jar&gt; [--all]
 *       gives the variables that share a local slot with a parameter (--all: with any variable)
 *       a slot of their own, to be run before inject and decompiling.
 *
 *   JarMatcher defaults &lt;in.jar&gt; &lt;out.jar&gt; &lt;interface&gt; ...
 *       makes the methods of interfaces the obfuscator merged default methods doing nothing
 *       (the classes implement a part of such an interface only).
 *
 *   JarMatcher locals &lt;in.jar&gt; &lt;ref&gt; -o &lt;locals.txt&gt; [-r report.txt]
 *       names the local variables after the methods of the same class, name and descriptor in
 *       &lt;ref&gt; (classes with a LocalVariableTable, e.g. the old version compiled from the
 *       de-obfuscated sources), aligned by the order of the first store, the kind and the
 *       instruction producing the value. Run on the renamed jar after unshare --all (one slot
 *       per variable). locals.txt lines: "pkg.Owner.method(int,byte[]) slot:name ..."
 *       {@link vavi.refactoring.LocalNameTransfer} applies them to the sources decompiled from that jar.
 *
 *   JarMatcher todo &lt;mapping.txt&gt;
 *       lists the placeholders left in a mapping.
 * </pre>
 * The mapping is in proguard format (-applymapping).
 * {@link JarDeobfuscator} runs them in their order.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-11-11 nsano initial version <br>
 */
public class JarMatcher {

    /** where the commands write their results (todo) and their progress, replaceable for a server */
    static PrintStream out = System.out, log = System.err;

    // ---- model ----------------------------------------------------------------------------------

    static class Side {
        String label;
        Map<String, Cls> classes = new TreeMap<>();
    }

    static class Cls {
        Side side;
        String name, sup;
        List<String> itfs = new ArrayList<>();
        int flags;
        List<Fld> fields = new ArrayList<>();
        List<Mth> methods = new ArrayList<>();
        Cls match, back;
        double score;
        String how;
        Set<String> libMethods; // name+desc of methods inherited from library types

        String pkg() { int i = name.lastIndexOf('/'); return i < 0 ? "" : name.substring(0, i); }
        String simple() { return name.substring(name.lastIndexOf('/') + 1); }
        @Override public String toString() { return name; }
    }

    static class Fld {
        Cls owner;
        String name, desc, id;
        int flags, index;
        String constVal;
        List<Object[]> uses = new ArrayList<>(); // {Mth, "G"|"P", index of the token}
        Fld match, back;
        double score;
        String how;
        boolean isStatic() { return (flags & ClassFile.ACC_STATIC) != 0; }
        @Override public String toString() { return owner.name + "." + name + " " + desc; }
    }

    static class Mth {
        Cls owner;
        String name, desc, id;
        int flags, index;
        boolean hasCode, libOverride;
        List<Tok> toks = new ArrayList<>();
        String[] params;
        Mth match, back;
        double score;
        String how;
        int family = -1;
        boolean isStatic() { return (flags & ClassFile.ACC_STATIC) != 0; }
        boolean special() { return name.startsWith("<"); }
        boolean virtual() { return !isStatic() && !special() && (flags & ClassFile.ACC_PRIVATE) == 0; }
        int arity() { return MethodTypeDesc.ofDescriptor(desc).parameterCount(); }
        @Override public String toString() { return owner.name + "." + name + desc; }
    }

    /** kind: S string, I number, O operation, C switch case, T type, M invoke, G field get, P field put */
    static class Tok {
        char kind;
        String owner, name, desc;
        Object target; // Fld or Mth of the same side, null: library
        Tok(char kind, String owner, String name, String desc) { this.kind = kind; this.owner = owner; this.name = name; this.desc = desc; }
    }

    // ---- loading --------------------------------------------------------------------------------

    static Map<String, byte[]> readEntries(Path p) throws IOException {
        Map<String, byte[]> map = new LinkedHashMap<>();
        if (Files.isDirectory(p)) {
            try (Stream<Path> s = Files.walk(p)) {
                for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile).sorted()::iterator) {
                    map.put(p.relativize(f).toString().replace('\\', '/'), Files.readAllBytes(f));
                }
            }
        } else {
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(p))) {
                for (ZipEntry e; (e = zis.getNextEntry()) != null;) {
                    map.put(e.getName(), e.isDirectory() ? null : zis.readAllBytes());
                }
            }
        }
        return map;
    }

    static Side load(Path p) throws IOException {
        Side side = new Side();
        side.label = p.toString();
        for (Map.Entry<String, byte[]> e : readEntries(p).entrySet()) {
            if (!e.getKey().endsWith(".class") || e.getValue() == null || e.getKey().endsWith("module-info.class")) continue;
            Cls c = loadClass(side, ClassFile.of().parse(e.getValue()));
            side.classes.put(c.name, c);
        }
        // resolve the references
        for (Cls c : side.classes.values()) {
            for (Mth m : c.methods) {
                for (int ti = 0; ti < m.toks.size(); ti++) {
                    Tok t = m.toks.get(ti);
                    if (t.kind == 'M') {
                        t.target = findMethod(side, t.owner, t.name, t.desc, new HashSet<>());
                        if (t.target == null && side.classes.containsKey(t.owner)) t.owner = "~"; // inherited from a library
                    } else if (t.kind == 'G' || t.kind == 'P') {
                        Fld f = findField(side, t.owner, t.name, t.desc, new HashSet<>());
                        t.target = f;
                        if (f != null) f.uses.add(new Object[] {m, String.valueOf(t.kind), ti});
                        else if (side.classes.containsKey(t.owner)) t.owner = "~";
                    }
                }
            }
        }
        for (Cls c : side.classes.values()) {
            c.libMethods = new HashSet<>();
            collectLibMethods(side, c, c.libMethods, new HashSet<>());
            for (Mth m : c.methods) m.libOverride = m.virtual() && c.libMethods.contains(m.name + m.desc);
        }
        return side;
    }

    static Cls loadClass(Side side, ClassModel cm) {
        Cls c = new Cls();
        c.side = side;
        c.name = cm.thisClass().asInternalName();
        c.sup = cm.superclass().map(ClassEntry::asInternalName).orElse(null);
        for (ClassEntry i : cm.interfaces()) c.itfs.add(i.asInternalName());
        c.flags = cm.flags().flagsMask();
        for (FieldModel fm : cm.fields()) {
            Fld f = new Fld();
            f.owner = c;
            f.name = fm.fieldName().stringValue();
            f.desc = fm.fieldType().stringValue();
            f.flags = fm.flags().flagsMask();
            f.index = c.fields.size();
            f.id = c.name + "." + f.name + ":" + f.desc;
            fm.findAttribute(Attributes.constantValue()).ifPresent(a -> f.constVal = String.valueOf(a.constant().constantValue()));
            c.fields.add(f);
        }
        for (MethodModel mm : cm.methods()) {
            Mth m = new Mth();
            m.owner = c;
            m.name = mm.methodName().stringValue();
            m.desc = mm.methodType().stringValue();
            m.flags = mm.flags().flagsMask();
            m.index = c.methods.size();
            m.id = c.name + "." + m.name + m.desc;
            mm.code().ifPresent(code -> { m.hasCode = true; scan(m, code); });
            if (m.params == null) {
                mm.findAttribute(Attributes.methodParameters()).ifPresent(a -> {
                    String[] names = new String[a.parameters().size()];
                    for (int i = 0; i < names.length; i++) names[i] = a.parameters().get(i).name().map(n -> n.stringValue()).orElse(null);
                    m.params = names;
                });
            }
            c.methods.add(m);
        }
        return c;
    }

    static void scan(Mth m, CodeModel code) {
        for (CodeElement e : code) {
            switch (e) {
            case InvokeInstruction i -> m.toks.add(new Tok('M', i.owner().asInternalName(), i.name().stringValue(), i.type().stringValue()));
            case FieldInstruction i -> {
                boolean get = i.opcode() == Opcode.GETFIELD || i.opcode() == Opcode.GETSTATIC;
                m.toks.add(new Tok(get ? 'G' : 'P', i.owner().asInternalName(), i.name().stringValue(), i.type().stringValue()));
            }
            case InvokeDynamicInstruction i -> {
                // string concatenation of modern javac: the literals are in the recipe
                for (Object arg : i.bootstrapArgs()) {
                    if (arg instanceof String s) for (String part : s.split("[\u0001\u0002]")) if (!part.isEmpty()) m.toks.add(new Tok('S', null, part, null));
                }
            }
            case ConstantInstruction i -> {
                Object v = i.constantValue();
                if (v instanceof String s) m.toks.add(new Tok('S', null, s, null));
                else if (v instanceof ClassDesc d) m.toks.add(new Tok('T', typeName(d.descriptorString()), null, null));
                else if (v instanceof Number n && !(v instanceof Integer k && k >= -1 && k <= 2)) m.toks.add(new Tok('I', null, String.valueOf(n), null));
            }
            case NewObjectInstruction i -> m.toks.add(new Tok('T', i.className().asInternalName(), null, null));
            case TypeCheckInstruction i -> m.toks.add(new Tok('T', i.type().asInternalName(), null, null));
            case NewReferenceArrayInstruction i -> m.toks.add(new Tok('T', i.componentType().asInternalName(), null, null));
            case NewMultiArrayInstruction i -> m.toks.add(new Tok('T', i.arrayType().asInternalName(), null, null));
            case TableSwitchInstruction i -> { for (SwitchCase sc : i.cases()) m.toks.add(new Tok('C', null, String.valueOf(sc.caseValue()), null)); }
            case LookupSwitchInstruction i -> { for (SwitchCase sc : i.cases()) m.toks.add(new Tok('C', null, String.valueOf(sc.caseValue()), null)); }
            case Instruction i -> {
                switch (i.opcode().kind()) {
                case OPERATOR, ARRAY_LOAD, ARRAY_STORE, THROW_EXCEPTION, MONITOR, CONVERT, NEW_PRIMITIVE_ARRAY ->
                    m.toks.add(new Tok('O', null, i.opcode().name(), null));
                case RETURN -> m.toks.add(new Tok('O', null, "RETURN", null));
                default -> { }
                }
            }
            default -> { }
            }
        }
        // parameter names
        Optional<LocalVariableTableAttribute> lvt = code.findAttribute(Attributes.localVariableTable());
        if (lvt.isPresent()) {
            MethodTypeDesc mt = MethodTypeDesc.ofDescriptor(m.desc);
            String[] names = new String[mt.parameterCount()];
            int slot = m.isStatic() ? 0 : 1;
            boolean any = false;
            for (int i = 0; i < names.length; i++) {
                LocalVariableInfo best = null;
                for (LocalVariableInfo lv : lvt.get().localVariables()) {
                    if (lv.slot() == slot && (best == null || lv.startPc() < best.startPc())) best = lv;
                }
                if (best != null) { names[i] = best.name().stringValue(); any = true; }
                String d = mt.parameterType(i).descriptorString();
                slot += d.equals("J") || d.equals("D") ? 2 : 1;
            }
            if (any) m.params = names;
        }
    }

    /** internal name of the element type, primitives arrays stay as descriptors */
    static String typeName(String desc) {
        String d = desc;
        while (d.startsWith("[")) d = d.substring(1);
        return d.startsWith("L") ? d.substring(1, d.length() - 1) : desc;
    }

    static Mth findMethod(Side side, String owner, String name, String desc, Set<String> seen) {
        Cls c = owner == null ? null : side.classes.get(owner);
        if (c == null || !seen.add(owner)) return null;
        for (Mth m : c.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
        Mth m = findMethod(side, c.sup, name, desc, seen);
        for (String i : c.itfs) if (m == null) m = findMethod(side, i, name, desc, seen);
        return m;
    }

    static Fld findField(Side side, String owner, String name, String desc, Set<String> seen) {
        Cls c = owner == null ? null : side.classes.get(owner);
        if (c == null || !seen.add(owner)) return null;
        for (Fld f : c.fields) if (f.name.equals(name) && f.desc.equals(desc)) return f;
        Fld f = findField(side, c.sup, name, desc, seen);
        for (String i : c.itfs) if (f == null) f = findField(side, i, name, desc, seen);
        return f;
    }

    static final Map<String, Set<String>> libCache = new HashMap<>();

    /** methods a class inherits from the types outside of the jar (found by reflection, the jdk in practice) */
    static void collectLibMethods(Side side, Cls c, Set<String> out, Set<String> seen) {
        if (!seen.add(c.name)) return;
        List<String> supers = new ArrayList<>(c.itfs);
        if (c.sup != null) supers.add(c.sup);
        for (String s : supers) {
            Cls sc = side.classes.get(s);
            if (sc != null) collectLibMethods(side, sc, out, seen);
            else out.addAll(libCache.computeIfAbsent(s, JarMatcher::reflectMethods));
        }
    }

    static Set<String> reflectMethods(String internalName) {
        Set<String> set = new HashSet<>();
        try {
            reflect(Class.forName(internalName.replace('/', '.'), false, ClassLoader.getSystemClassLoader()), set, new HashSet<>());
        } catch (Throwable e) {
            if (!internalName.equals("java/lang/Object")) log.println("W: library class not found: " + internalName);
        }
        return set;
    }

    static void reflect(Class<?> k, Set<String> set, Set<Class<?>> seen) {
        if (k == null || !seen.add(k)) return;
        for (Method m : k.getDeclaredMethods()) {
            if (Modifier.isPrivate(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
            set.add(m.getName() + MethodType.methodType(m.getReturnType(), m.getParameterTypes()).toMethodDescriptorString());
        }
        reflect(k.getSuperclass(), set, seen);
        for (Class<?> i : k.getInterfaces()) reflect(i, set, seen);
        if (k.isInterface()) reflect(Object.class, set, seen);
    }

    // ---- matching -------------------------------------------------------------------------------

    /** matching of two sides: x (left) is matched against y (right) */
    static class Matching {
        final Side x, y;
        Matching(Side x, Side y) { this.x = x; this.y = y; }

        // names are compared in the name space of the right side
        String key(Cls c, boolean left) { return left ? (c.match == null ? null : c.match.name) : (c.back == null ? null : c.name); }
        String key(Mth m, boolean left) { return left ? (m.match == null ? null : m.match.id) : (m.back == null ? null : m.id); }
        String key(Fld f, boolean left) { return left ? (f.match == null ? null : f.match.id) : (f.back == null ? null : f.id); }

        String canonType(String name, boolean left) {
            if (name == null) return "-";
            if (name.startsWith("[")) return canonDesc(name, left);
            Cls c = (left ? x : y).classes.get(name);
            if (c == null) return ALIASES.getOrDefault(name, name);
            String k = key(c, left);
            return k == null ? "?" : "@" + k;
        }

        /** library types a cleaned up reference commonly replaces */
        static final Map<String, String> ALIASES = Map.of("java/lang/StringBuffer", "java/lang/StringBuilder");

        final Map<String, String> descCacheL = new HashMap<>(), descCacheR = new HashMap<>();

        String canonDesc(String desc, boolean left) {
            if (desc.indexOf('L') < 0) return desc;
            Map<String, String> cache = left ? descCacheL : descCacheR;
            String r = cache.get(desc);
            if (r != null) return r;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < desc.length(); i++) {
                char ch = desc.charAt(i);
                if (ch == 'L') {
                    int e = desc.indexOf(';', i);
                    sb.append('L').append(canonType(desc.substring(i + 1, e), left)).append(';');
                    i = e;
                } else {
                    sb.append(ch);
                }
            }
            cache.put(desc, r = sb.toString());
            return r;
        }

        void invalidate() { descCacheL.clear(); descCacheR.clear(); bagCache.clear(); }

        static void add(Map<String, Double> bag, String k, double w) { bag.merge(k, w, Double::sum); }

        static boolean concatNoise(Tok t) {
            return t.owner != null && (t.owner.equals("java/lang/StringBuffer") || t.owner.equals("java/lang/StringBuilder")
                    || (t.owner.equals("java/lang/String") && "valueOf".equals(t.name)));
        }

        final Map<Object, Map<String, Double>> bagCache = new LinkedHashMap<>();

        Map<String, Double> bag(Mth m, boolean left) {
            Map<String, Double> bag = bagCache.get(m);
            if (bag != null) return bag;
            bag = new HashMap<>();
            for (Tok t : m.toks) {
                switch (t.kind) {
                case 'S' -> add(bag, "S:" + t.name, 3);
                case 'I' -> add(bag, "I:" + t.name, 0.5);
                case 'C' -> add(bag, "C:" + t.name, 0.5);
                case 'O' -> add(bag, "O:" + t.name, 0.25);
                case 'T' -> { if (!concatNoise(t)) add(bag, "T:" + canonType(t.owner, left), 1); }
                case 'M' -> {
                    if (t.target instanceof Mth tm) {
                        String k = key(tm, left);
                        if (k != null) add(bag, "M:" + k, 2);
                        else add(bag, "M?:" + (tm.special() || tm.libOverride ? tm.name : "") + canonDesc(tm.desc, left), 0.7);
                    } else if (!concatNoise(t)) {
                        add(bag, "ML:" + canonType(t.owner, left) + "." + t.name + canonDesc(t.desc, left), 1);
                    }
                }
                default -> {
                    if (t.target instanceof Fld tf) {
                        String k = key(tf, left);
                        if (k != null) add(bag, t.kind + ":" + k, 2);
                        else add(bag, t.kind + "?:" + canonDesc(tf.desc, left), 0.7);
                    } else {
                        add(bag, t.kind + "L:" + canonType(t.owner, left) + "." + t.name, 1);
                    }
                }
                }
            }
            bagCache.put(m, bag);
            return bag;
        }

        Map<String, Double> bag(Fld f, boolean left) {
            Map<String, Double> bag = bagCache.get(f);
            if (bag != null) return bag;
            bag = new HashMap<>();
            for (Object[] use : f.uses) {
                Mth m = (Mth) use[0];
                String k = key(m, left);
                if (k != null) add(bag, "U" + use[1] + ":" + k, 2);
                else add(bag, "U" + use[1] + "?" + (m.special() ? m.name : ""), 0.5);
                // the code around the access: constants, operations and library calls
                int at = (Integer) use[2];
                for (int d = -2; d <= 2; d++) {
                    if (d == 0 || at + d < 0 || at + d >= m.toks.size()) continue;
                    Tok t = m.toks.get(at + d);
                    String c = switch (t.kind) {
                    case 'S', 'I', 'C', 'O' -> t.kind + ":" + t.name;
                    case 'M' -> t.target == null && !concatNoise(t) ? "ML:" + t.name : null;
                    default -> null;
                    };
                    if (c != null) add(bag, "X" + use[1] + (d < 0 ? "<" : ">") + c, 1);
                }
            }
            if (f.constVal != null) add(bag, "V:" + f.constVal, 3);
            bagCache.put(f, bag);
            return bag;
        }

        Map<String, Double> bag(Cls c, boolean left) {
            Map<String, Double> bag = bagCache.get(c);
            if (bag != null) return bag;
            bag = new HashMap<>();
            add(bag, "SUPER:" + canonType(c.sup, left), 4);
            for (String i : c.itfs) add(bag, "ITF:" + canonType(i, left), 2);
            add(bag, "KIND:" + (c.flags & (ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT)), 2);
            for (Fld f : c.fields) add(bag, "FD:" + f.isStatic() + canonDesc(f.desc, left), 1);
            for (Mth m : c.methods) {
                if ((m.flags & ClassFile.ACC_SYNTHETIC) != 0) continue;
                add(bag, "MD:" + m.isStatic() + (m.special() || m.libOverride ? m.name : "") + canonDesc(m.desc, left), 1);
                for (Map.Entry<String, Double> e : bag(m, left).entrySet()) add(bag, e.getKey(), e.getValue());
            }
            bagCache.put(c, bag);
            return bag;
        }

        static double sim(Map<String, Double> a, Map<String, Double> b) {
            if (a.isEmpty() && b.isEmpty()) return -1; // unknown
            double min = 0, max = 0;
            for (Map.Entry<String, Double> e : a.entrySet()) {
                Double o = b.get(e.getKey());
                if (o == null) { max += e.getValue(); } else { min += Math.min(e.getValue(), o); max += Math.max(e.getValue(), o); }
            }
            for (Map.Entry<String, Double> e : b.entrySet()) if (!a.containsKey(e.getKey())) max += e.getValue();
            return max == 0 ? 0 : min / max;
        }

        // -- classes

        int classRound(double thr, double margin) {
            List<Cls> xs = x.classes.values().stream().filter(c -> c.match == null).toList();
            List<Cls> ys = y.classes.values().stream().filter(c -> c.back == null).toList();
            if (xs.isEmpty() || ys.isEmpty()) return 0;
            double[][] s = new double[xs.size()][ys.size()];
            for (int i = 0; i < xs.size(); i++) for (int j = 0; j < ys.size(); j++) s[i][j] = Math.max(0, sim(bag(xs.get(i), true), bag(ys.get(j), false)));
            int n = 0;
            for (int[] p : mutualBest(s, thr, margin)) {
                link(xs.get(p[0]), ys.get(p[1]), s[p[0]][p[1]], "sim");
                n++;
            }
            if (n > 0) invalidate();
            return n;
        }

        /** the classes left are too alike to tell apart by a margin (variants of one class): best pairs first */
        int classGreedy(double thr) {
            List<Cls> xs = x.classes.values().stream().filter(c -> c.match == null).toList();
            List<Cls> ys = y.classes.values().stream().filter(c -> c.back == null).toList();
            List<double[]> pairs = new ArrayList<>();
            for (int i = 0; i < xs.size(); i++) for (int j = 0; j < ys.size(); j++) {
                double v = sim(bag(xs.get(i), true), bag(ys.get(j), false));
                if (v >= thr) pairs.add(new double[] {v, i, j});
            }
            pairs.sort((p, q) -> Double.compare(q[0], p[0]));
            int n = 0;
            for (double[] p : pairs) {
                Cls a = xs.get((int) p[1]), b = ys.get((int) p[2]);
                if (a.match != null || b.back != null) continue;
                link(a, b, p[0], "greedy");
                n++;
            }
            if (n > 0) invalidate();
            return n;
        }

        void link(Cls a, Cls b, double score, String how) {
            a.match = b; b.back = a; a.score = score; a.how = how;
            // constructors/initializers of the same shape
            for (String special : List.of("<clinit>")) {
                Mth ma = a.methods.stream().filter(m -> m.name.equals(special)).findFirst().orElse(null);
                Mth mb = b.methods.stream().filter(m -> m.name.equals(special)).findFirst().orElse(null);
                if (ma != null && mb != null) link(ma, mb, 1, "special");
            }
        }

        static void link(Mth a, Mth b, double score, String how) { a.match = b; b.back = a; a.score = score; a.how = how; }
        static void link(Fld a, Fld b, double score, String how) { a.match = b; b.back = a; a.score = score; a.how = how; }

        /** pairs that are the best of each other, over the threshold and clearly better than the alternatives */
        static List<int[]> mutualBest(double[][] s, double thr, double margin) {
            List<int[]> r = new ArrayList<>();
            int n = s.length, m = n == 0 ? 0 : s[0].length;
            for (int i = 0; i < n; i++) {
                int bj = -1;
                double second = -1;
                for (int j = 0; j < m; j++) {
                    if (bj < 0 || s[i][j] > s[i][bj]) { if (bj >= 0) second = Math.max(second, s[i][bj]); bj = j; }
                    else second = Math.max(second, s[i][j]);
                }
                if (bj < 0 || s[i][bj] < thr || s[i][bj] - second < margin) continue;
                boolean ok = true;
                for (int k = 0; k < n && ok; k++) if (k != i && s[k][bj] > s[i][bj] - margin) ok = false;
                if (ok) r.add(new int[] {i, bj});
            }
            return r;
        }

        // -- members

        Map<Integer, Map<String, Double>> familyVotes = new HashMap<>();

        void vote() {
            familyVotes.clear();
            for (Cls c : x.classes.values()) for (Mth m : c.methods) {
                if (m.family >= 0 && m.match != null) familyVotes.computeIfAbsent(m.family, k -> new HashMap<>()).merge(m.match.name, m.score, Double::sum);
            }
        }

        String familyName(Mth m) {
            Map<String, Double> v = m.family < 0 ? null : familyVotes.get(m.family);
            return v == null ? null : Collections.max(v.entrySet(), Map.Entry.comparingByValue()).getKey();
        }

        boolean compatible(Mth a, Mth b, boolean strict) {
            if (a.isStatic() != b.isStatic() || a.special() != b.special()) return false;
            if (a.special() && !a.name.equals(b.name)) return false;
            if ((a.libOverride || b.libOverride) && !a.name.equals(b.name)) return false;
            if (strict) return canonDesc(a.desc, true).equals(canonDesc(b.desc, false));
            return a.arity() == b.arity();
        }

        double sim(Mth a, Mth b) {
            double s = sim(bag(a, true), bag(b, false));
            if (s < 0) s = 0.3;
            if (a.libOverride && a.name.equals(b.name)) s += 1;
            String fn = familyName(a);
            if (fn != null && fn.equals(b.name)) s += 1;
            double ra = (double) a.index / a.owner.methods.size(), rb = (double) b.index / b.owner.methods.size();
            return s + 0.03 * (1 - Math.abs(ra - rb));
        }

        double sim(Fld a, Fld b) {
            double s = sim(bag(a, true), bag(b, false));
            if (s < 0) s = 0.3;
            double ra = (double) a.index / a.owner.fields.size(), rb = (double) b.index / b.owner.fields.size();
            return s + 0.03 * (1 - Math.abs(ra - rb));
        }

        static final double NO = -1e9;

        double[][] methodScores(List<Mth> as, List<Mth> bs, boolean strict) {
            double[][] s = new double[as.size()][bs.size()];
            for (int i = 0; i < as.size(); i++) for (int j = 0; j < bs.size(); j++)
                s[i][j] = compatible(as.get(i), bs.get(j), strict) ? sim(as.get(i), bs.get(j)) : NO;
            return s;
        }

        double[][] fieldScores(List<Fld> as, List<Fld> bs, boolean strict) {
            double[][] s = new double[as.size()][bs.size()];
            for (int i = 0; i < as.size(); i++) for (int j = 0; j < bs.size(); j++) {
                Fld a = as.get(i), b = bs.get(j);
                boolean ok = a.isStatic() == b.isStatic() && (!strict || canonDesc(a.desc, true).equals(canonDesc(b.desc, false)));
                s[i][j] = ok ? sim(a, b) : NO;
            }
            return s;
        }

        static List<Mth> freeMethods(Cls c, boolean left) {
            return c.methods.stream().filter(m -> (left ? m.match : m.back) == null && (m.flags & ClassFile.ACC_SYNTHETIC) == 0).toList();
        }

        static List<Fld> freeFields(Cls c, boolean left) {
            return c.fields.stream().filter(f -> (left ? f.match : f.back) == null && (f.flags & ClassFile.ACC_SYNTHETIC) == 0).toList();
        }

        /** @param mode 0: clear winners, 1: order alignment, 2: clear winners ignoring the descriptors */
        int memberRound(double thr, int mode) {
            int n = 0;
            vote();
            for (Cls a : x.classes.values()) {
                if (a.match == null) continue;
                Cls b = a.match;
                List<Mth> ma = freeMethods(a, true), mb = freeMethods(b, false);
                if (!ma.isEmpty() && !mb.isEmpty()) {
                    double[][] s = methodScores(ma, mb, mode != 2);
                    List<int[]> pairs = mode == 1 ? align(s, thr) : mutualBest(s, thr, 0.04);
                    for (int[] p : pairs) { link(ma.get(p[0]), mb.get(p[1]), s[p[0]][p[1]], mode == 0 ? "sim" : mode == 1 ? "order" : "relaxed"); n++; }
                }
                List<Fld> fa = freeFields(a, true), fb = freeFields(b, false);
                if (!fa.isEmpty() && !fb.isEmpty()) {
                    double[][] s = fieldScores(fa, fb, mode != 2);
                    List<int[]> pairs = mode == 1 ? align(s, thr) : mutualBest(s, thr, 0.04);
                    for (int[] p : pairs) { link(fa.get(p[0]), fb.get(p[1]), s[p[0]][p[1]], mode == 0 ? "sim" : mode == 1 ? "order" : "relaxed"); n++; }
                }
            }
            if (n > 0) invalidate();
            return n;
        }

        /** order preserving alignment maximizing the total score, pairs under the threshold are not aligned */
        static List<int[]> align(double[][] s, double thr) {
            int n = s.length, m = s[0].length;
            double[][] d = new double[n + 1][m + 1];
            for (int i = 1; i <= n; i++) for (int j = 1; j <= m; j++) {
                double v = Math.max(d[i - 1][j], d[i][j - 1]);
                if (s[i - 1][j - 1] >= thr) v = Math.max(v, d[i - 1][j - 1] + 1 + s[i - 1][j - 1]);
                d[i][j] = v;
            }
            List<int[]> r = new ArrayList<>();
            for (int i = n, j = m; i > 0 && j > 0;) {
                if (s[i - 1][j - 1] >= thr && d[i][j] == d[i - 1][j - 1] + 1 + s[i - 1][j - 1]) { r.add(new int[] {i - 1, j - 1}); i--; j--; }
                else if (d[i][j] == d[i - 1][j]) i--;
                else j--;
            }
            return r;
        }

        static double weight(Map<String, Double> bag) { return bag.values().stream().mapToDouble(Double::doubleValue).sum(); }

        /** members that moved to another class (a class was split, merged, a method pulled up ...) */
        int movedRound(double thr, double margin) {
            int n = 0;
            List<Mth> ma = new ArrayList<>(), mb = new ArrayList<>();
            for (Cls c : x.classes.values()) for (Mth m : freeMethods(c, true)) if (!m.special() && !m.libOverride && weight(bag(m, true)) >= 4) ma.add(m);
            for (Cls c : y.classes.values()) for (Mth m : freeMethods(c, false)) if (!m.special() && !m.libOverride && weight(bag(m, false)) >= 4) mb.add(m);
            if (!ma.isEmpty() && !mb.isEmpty()) {
                double[][] s = new double[ma.size()][mb.size()];
                for (int i = 0; i < ma.size(); i++) for (int j = 0; j < mb.size(); j++) {
                    Mth a = ma.get(i), b = mb.get(j);
                    boolean ok = a.isStatic() == b.isStatic() && canonDesc(a.desc, true).equals(canonDesc(b.desc, false));
                    s[i][j] = ok ? sim(bag(a, true), bag(b, false)) : NO;
                }
                for (int[] p : mutualBest(s, thr, margin)) { link(ma.get(p[0]), mb.get(p[1]), s[p[0]][p[1]], "moved"); n++; }
            }
            List<Fld> fa = new ArrayList<>(), fb = new ArrayList<>();
            for (Cls c : x.classes.values()) for (Fld f : freeFields(c, true)) if (weight(bag(f, true)) >= 4) fa.add(f);
            for (Cls c : y.classes.values()) for (Fld f : freeFields(c, false)) if (weight(bag(f, false)) >= 4) fb.add(f);
            if (!fa.isEmpty() && !fb.isEmpty()) {
                double[][] s = new double[fa.size()][fb.size()];
                for (int i = 0; i < fa.size(); i++) for (int j = 0; j < fb.size(); j++) {
                    Fld a = fa.get(i), b = fb.get(j);
                    boolean ok = a.isStatic() == b.isStatic() && canonDesc(a.desc, true).equals(canonDesc(b.desc, false));
                    s[i][j] = ok ? sim(bag(a, true), bag(b, false)) : NO;
                }
                for (int[] p : mutualBest(s, thr, margin)) { link(fa.get(p[0]), fb.get(p[1]), s[p[0]][p[1]], "moved"); n++; }
            }
            if (n > 0) invalidate();
            return n;
        }

        int members(double thr) {
            int total = 0;
            for (int n; (n = memberRound(thr, 0)) > 0;) total += n;
            return total;
        }

        void run() {
            // classes kept by the obfuscator
            for (Cls a : x.classes.values()) {
                Cls b = y.classes.get(a.name);
                if (b != null && a.simple().length() > 3 && sim(bag(a, true), bag(b, false)) > 0.3) link(a, b, 1, "name");
            }
            invalidate();
            for (double thr : new double[] {0.7, 0.55, 0.4, 0.3, 0.22}) {
                for (;;) {
                    int n = classRound(thr, 0.08) + members(0.5);
                    if (n == 0) break;
                }
            }
            for (double thr : new double[] {0.35, 0.2}) {
                for (;;) {
                    int n = members(thr) + classRound(0.22, 0.05);
                    if (n == 0) break;
                }
            }
            for (;;) {
                int n = memberRound(0.3, 1);    // order
                n += members(0.2);
                n += memberRound(0.7, 2);       // changed descriptors
                n += classRound(0.15, 0.03);
                if (n == 0) break;
            }
            if (classGreedy(0.5) > 0) {
                for (;;) {
                    int n = members(0.35) + memberRound(0.3, 1) + memberRound(0.7, 2);
                    if (n == 0) break;
                }
            }
            for (;;) {
                int n = movedRound(0.8, 0.1);
                if (n > 0) n += members(0.2);
                if (n == 0) break;
            }
        }
    }

    // ---- families of overriding methods ---------------------------------------------------------

    static int[] uf;

    static int find(int i) { while (uf[i] != i) i = uf[i] = uf[uf[i]]; return i; }

    static void families(Side side) {
        List<Mth> all = new ArrayList<>();
        for (Cls c : side.classes.values()) for (Mth m : c.methods) if (m.virtual()) { m.family = all.size(); all.add(m); }
        uf = new int[all.size()];
        for (int i = 0; i < uf.length; i++) uf[i] = i;
        for (Cls c : side.classes.values()) {
            Set<Cls> supers = new LinkedHashSet<>();
            ancestors(side, c, supers);
            supers.remove(c);
            for (Mth m : c.methods) {
                if (!m.virtual()) continue;
                for (Cls s : supers) for (Mth sm : s.methods) {
                    if (sm.virtual() && sm.name.equals(m.name) && sm.desc.equals(m.desc) && related(m, sm)) uf[find(m.family)] = find(sm.family);
                }
            }
            // an interface method implemented by a method inherited from another branch
            for (Cls s1 : supers) for (Mth m1 : s1.methods) {
                if (!m1.virtual()) continue;
                for (Cls s2 : supers) if (s1 != s2) for (Mth m2 : s2.methods) {
                    if (m2.virtual() && m2.name.equals(m1.name) && m2.desc.equals(m1.desc) && related(m1, m2)) uf[find(m1.family)] = find(m2.family);
                }
            }
        }
        for (Mth m : all) m.family = find(m.family);
        // a library override anywhere makes the family a library override
        Set<Integer> lib = new HashSet<>();
        for (Mth m : all) if (m.libOverride) lib.add(m.family);
        for (Mth m : all) if (lib.contains(m.family)) m.libOverride = true;
    }

    /**
     * A method that is not public cannot implement an interface method: obfuscators give unrelated
     * methods one name, also where java would not allow it. Those stay two methods with two names.
     */
    static boolean related(Mth a, Mth b) {
        boolean ia = (a.owner.flags & ClassFile.ACC_INTERFACE) != 0, ib = (b.owner.flags & ClassFile.ACC_INTERFACE) != 0;
        if (ia == ib) return true;
        Mth c = ia ? b : a;
        return (c.flags & ClassFile.ACC_PUBLIC) != 0;
    }

    static void ancestors(Side side, Cls c, Set<Cls> out) {
        if (c == null || !out.add(c)) return;
        ancestors(side, side.classes.get(c.sup), out);
        for (String i : c.itfs) ancestors(side, side.classes.get(i), out);
    }

    // ---- output ---------------------------------------------------------------------------------

    static String javaType(String desc, Map<String, String> classNames) {
        int dims = 0;
        while (desc.charAt(dims) == '[') dims++;
        String d = desc.substring(dims), t;
        switch (d.charAt(0)) {
        case 'L' -> { String n = d.substring(1, d.length() - 1); t = classNames.getOrDefault(n, n).replace('/', '.'); }
        case 'I' -> t = "int";
        case 'Z' -> t = "boolean";
        case 'B' -> t = "byte";
        case 'C' -> t = "char";
        case 'S' -> t = "short";
        case 'J' -> t = "long";
        case 'F' -> t = "float";
        case 'D' -> t = "double";
        default -> t = "void";
        }
        return t + "[]".repeat(dims);
    }

    static String javaParams(String desc, Map<String, String> classNames) {
        MethodTypeDesc mt = MethodTypeDesc.ofDescriptor(desc);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mt.parameterCount(); i++) {
            if (i > 0) sb.append(',');
            sb.append(javaType(mt.parameterType(i).descriptorString(), classNames));
        }
        return sb.toString();
    }

    static <T> T last(T t, java.util.function.UnaryOperator<T> next, int hops) {
        for (int i = 0; i < hops && t != null; i++) {
            if (t instanceof Fld f && f.match != null && f.score < minScore) return null;
            if (t instanceof Mth m && m.match != null && !m.special() && m.score < minScore) return null;
            t = next.apply(t);
        }
        return t;
    }

    /** the counterpart ignoring the minimum score, for the report */
    static String suggestion(Object o, int hops) {
        for (int i = 0; i < hops && o != null; i++) o = o instanceof Fld f ? f.match : ((Mth) o).match;
        return o == null ? "-" : "LOW:" + (o instanceof Fld f ? f.name : ((Mth) o).name);
    }

    /** matches under this score are reported only, the member gets a placeholder */
    static double minScore = 0.5;

    static String token(Cls c, String keep) {
        // the obfuscated path without the kept leading packages
        String[] seg = c.name.split("/");
        StringBuilder sb = new StringBuilder();
        for (String s : seg) if (s.length() <= 3 || sb.length() > 0) sb.append(sb.length() > 0 ? "_" : "").append(s);
        return sb.length() == 0 ? c.simple() : sb.toString();
    }

    static void match(List<String> args) throws IOException {
        keep = Pattern.compile("[A-Za-z_$][\\w$]{3,}");
        minScore = 0.5;
        List<Path> inputs = new ArrayList<>();
        Path out = null, params = null, report = null;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
            case "-o" -> out = Path.of(args.get(++i));
            case "-p" -> params = Path.of(args.get(++i));
            case "-r" -> report = Path.of(args.get(++i));
            case "--keep" -> keep = Pattern.compile(args.get(++i));
            case "--min" -> minScore = Double.parseDouble(args.get(++i));
            default -> inputs.add(Path.of(args.get(i)));
            }
        }
        if (inputs.size() < 2 || out == null) usage();
        List<Side> sides = new ArrayList<>();
        for (Path p : inputs) sides.add(load(p));
        Side a = sides.get(0);
        for (Side s : sides) families(s);
        for (int i = 0; i + 1 < sides.size(); i++) {
            new Matching(sides.get(i), sides.get(i + 1)).run();
            Side s = sides.get(i);
            long cm = s.classes.values().stream().filter(c -> c.match != null).count();
            long mm = s.classes.values().stream().flatMap(c -> c.methods.stream()).filter(m -> m.match != null).count();
            long mt = s.classes.values().stream().mapToLong(c -> c.methods.size()).sum();
            long fm = s.classes.values().stream().flatMap(c -> c.fields.stream()).filter(f -> f.match != null).count();
            long ft = s.classes.values().stream().mapToLong(c -> c.fields.size()).sum();
            log.printf("%s -> %s: classes %d/%d, methods %d/%d, fields %d/%d%n", s.label, sides.get(i + 1).label, cm, s.classes.size(), mm, mt, fm, ft);
        }
        int hops = sides.size() - 1;
        StringBuilder rep = new StringBuilder();

        // class names
        Map<String, String> classNames = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        Map<String, Map<String, Integer>> pkgVotes = new HashMap<>();
        for (Cls c : a.classes.values()) {
            Cls t = last(c, k -> k.match, hops);
            if (t == null) continue;
            String n = t.name;
            if (n.contains("$")) { // the reference nests a class that is top level here
                String flat = t.pkg() + "/" + n.substring(n.lastIndexOf('$') + 1);
                n = used.contains(flat) ? n.replace('$', '_') : flat;
            }
            if (!used.add(n)) { rep.append("CLASH class ").append(c.name).append(" -> ").append(n).append('\n'); continue; }
            classNames.put(c.name, n);
            pkgVotes.computeIfAbsent(c.pkg(), k -> new HashMap<>()).merge(n.substring(0, n.lastIndexOf('/')), 1, Integer::sum);
        }
        for (Cls c : a.classes.values()) {
            if (classNames.containsKey(c.name)) continue;
            Map<String, Integer> v = pkgVotes.get(c.pkg());
            String pkg = v == null ? c.pkg() : Collections.max(v.entrySet(), Map.Entry.comparingByValue()).getKey();
            classNames.put(c.name, pkg + "/Obf_" + token(c, null));
            rep.append("NEW class ").append(c.name).append(" -> ").append(classNames.get(c.name)).append('\n');
        }

        // method names, by family
        Map<Integer, Map<String, Double>> votes = new HashMap<>();
        for (Cls c : a.classes.values()) for (Mth m : c.methods) {
            Mth t = last(m, k -> k.match, hops);
            if (m.family >= 0 && t != null && !t.special()) votes.computeIfAbsent(m.family, k -> new HashMap<>()).merge(t.name, 1 + m.score, Double::sum);
        }
        Map<Integer, String> familyNames = new HashMap<>();
        Map<Integer, Mth> familyRoots = new HashMap<>();
        for (Cls c : a.classes.values()) for (Mth m : c.methods) if (m.family >= 0) familyRoots.merge(m.family, m, (p, q) -> depth(a, q.owner) < depth(a, p.owner) ? q : p);
        Map<Mth, String> methodNames = new LinkedHashMap<>();
        for (Cls c : a.classes.values()) {
            for (Mth m : c.methods) {
                if (m.special()) continue;
                String n;
                if (m.libOverride || m.name.equals("main") && m.isStatic()) {
                    n = m.name;
                } else if (m.family >= 0) {
                    n = familyNames.get(m.family);
                    if (n == null) {
                        Map<String, Double> v = votes.get(m.family);
                        if (v != null) {
                            n = Collections.max(v.entrySet(), Map.Entry.comparingByValue()).getKey();
                            if (v.size() > 1) {
                                // methods the reference names differently are one method here: the untouched name wins
                                if (keep.matcher(m.name).matches()) n = m.name;
                                rep.append("AMBIGUOUS family of ").append(familyRoots.get(m.family)).append(": ").append(v).append(" -> ").append(n).append('\n');
                            }
                        } else {
                            n = placeholder(familyRoots.get(m.family));
                        }
                        familyNames.put(m.family, n);
                    }
                } else {
                    Mth t = last(m, k -> k.match, hops);
                    n = t != null ? t.name : placeholder(m);
                }
                methodNames.put(m, n);
            }
        }
        // clashes: the same name and descriptor twice in a hierarchy, the weaker match loses
        for (boolean changed = true; changed;) {
            changed = false;
            for (Cls c : a.classes.values()) {
                Set<Cls> line = new LinkedHashSet<>();
                ancestors(a, c, line);
                Map<String, Mth> seen = new HashMap<>();
                for (Cls s : line) for (Mth m : s.methods) {
                    if (m.special() || s != c && (m.flags & ClassFile.ACC_PRIVATE) != 0) continue;
                    String k = methodNames.get(m) + m.desc.substring(0, m.desc.indexOf(')') + 1);
                    Mth o = seen.get(k);
                    if (o == null) { seen.put(k, m); continue; }
                    if (o.family >= 0 && o.family == m.family) continue;
                    if (s != c && o.owner != c && (m.isStatic() || o.isStatic())) continue;
                    // the reference gives methods that only look related one name: follow it
                    if (!related(m, o) && last(m, k2 -> k2.match, hops) != null && last(o, k2 -> k2.match, hops) != null) continue;
                    Mth loser = m.libOverride ? o : o.libOverride ? m : last(m, k2 -> k2.match, hops) == null ? m : last(o, k2 -> k2.match, hops) == null ? o : m.score < o.score ? m : o;
                    if (loser.libOverride) continue;
                    rep.append("CLASH method ").append(m).append(" / ").append(o).append(" -> ").append(methodNames.get(m)).append(", placeholder for ").append(loser).append('\n');
                    String ph = placeholder(loser.family >= 0 ? familyRoots.get(loser.family) : loser, true);
                    if (loser.family >= 0) { for (Map.Entry<Mth, String> e : methodNames.entrySet()) if (e.getKey().family == loser.family) e.setValue(ph); }
                    else methodNames.put(loser, ph);
                    if (loser == o) seen.put(k, m);
                    changed = true;
                }
            }
        }

        // parameter names: of the counterpart, else of a method of the family (overridden, overriding)
        Map<Mth, String[]> paramNames = new LinkedHashMap<>();
        Map<String, String[]> familyParams = new HashMap<>();
        for (Cls c : a.classes.values()) for (Mth m : c.methods) {
            Mth t = last(m, k -> k.match, hops);
            if (t != null && t.params != null && t.params.length == m.arity() && m.arity() > 0) {
                paramNames.put(m, t.params);
                if (m.family >= 0) familyParams.putIfAbsent(m.family + m.desc, t.params);
            }
        }
        for (Cls c : a.classes.values()) for (Mth m : c.methods) {
            String[] f = m.family < 0 ? null : familyParams.get(m.family + m.desc);
            if (f != null) paramNames.putIfAbsent(m, f);
        }

        // the mapping
        StringBuilder map = new StringBuilder(), par = new StringBuilder();
        int fields = 0, fieldsNamed = 0, methods = 0, methodsNamed = 0;
        for (Cls c : a.classes.values()) {
            String cn = classNames.get(c.name);
            map.append(c.name.replace('/', '.')).append(" -> ").append(cn.replace('/', '.')).append(":\n");
            Cls ct = last(c, k -> k.match, hops);
            rep.append(String.format("class %s -> %s %s %.2f%n", c.name, cn, c.how, c.score));
            Set<String> fieldUsed = new HashSet<>();
            for (Fld f : c.fields) {
                Fld t = last(f, k -> k.match, hops);
                String n = t != null ? t.name : keep.matcher(f.name).matches() ? f.name : "f_" + token(c, null) + "__" + f.name;
                if (!fieldUsed.add(n)) { rep.append("CLASH field ").append(f).append(" -> ").append(n).append('\n'); n = "f_" + token(c, null) + "__" + f.name; }
                fields++;
                if (!n.startsWith("f_")) fieldsNamed++;
                if (t != null && !n.equals(f.name) && keep.matcher(f.name).matches()) rep.append("HINT field ").append(cn).append('.').append(n).append(" was ").append(f.name).append('\n');
                map.append("    ").append(javaType(f.desc, Map.of())).append(' ').append(f.name).append(" -> ").append(n).append('\n');
                rep.append(String.format("  field %s %s -> %s %s%n", f.name, f.desc, n, t == null ? suggestion(f, hops) + " " + chain(f, hops) : chain(f, hops)));
            }
            for (Mth m : c.methods) {
                Mth t = last(m, k -> k.match, hops);
                if (!m.special()) {
                    String n = methodNames.get(m);
                    methods++;
                    if (!n.startsWith("m_")) methodsNamed++;
                    if (!n.equals(m.name) && keep.matcher(m.name).matches()) rep.append("HINT method ").append(cn).append('.').append(n).append(" was ").append(m.name).append('\n');
                    map.append("    ").append(javaType(m.desc.substring(m.desc.indexOf(')') + 1), Map.of())).append(' ').append(m.name)
                            .append('(').append(javaParams(m.desc, Map.of())).append(") -> ").append(n).append('\n');
                    rep.append(String.format("  method %s%s -> %s %s%n", m.name, m.desc, n, t == null ? suggestion(m, hops) + " " + chain(m, hops) : chain(m, hops)));
                }
                if (paramNames.containsKey(m)) {
                    par.append(cn.replace('/', '.')).append('.').append(m.special() ? m.name : methodNames.get(m))
                            .append('(').append(javaParams(m.desc, classNames)).append(')');
                    for (String p : paramNames.get(m)) par.append(' ').append(p == null ? "-" : p);
                    par.append('\n');
                }
            }
        }
        // what the reference has and the obfuscated jar has not (or what was not found)
        Set<Object> reached = new LinkedHashSet<>();
        for (Cls c : a.classes.values()) {
            for (Fld f : c.fields) reached.add(last(f, k -> k.match, hops));
            for (Mth m : c.methods) reached.add(last(m, k -> k.match, hops));
        }
        for (Cls c : a.classes.values()) {
            Cls t = last(c, k -> k.match, hops);
            if (t == null) continue;
            for (Fld f : t.fields) if (!reached.contains(f)) rep.append("UNUSED ").append(f).append('\n');
            for (Mth m : t.methods) if (!reached.contains(m) && (m.flags & ClassFile.ACC_SYNTHETIC) == 0) rep.append("UNUSED ").append(m).append('\n');
        }
        Files.writeString(out, map);
        if (params != null) Files.writeString(params, par);
        if (report != null) Files.writeString(report, rep);
        log.printf("named: classes %d/%d, fields %d/%d, methods %d/%d%n",
                classNames.values().stream().filter(n -> !n.contains("/Obf_")).count(), classNames.size(), fieldsNamed, fields, methodsNamed, methods);
    }

    /** how a member was matched, hop by hop */
    static String chain(Object o, int hops) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hops && o != null; i++) {
            if (o instanceof Fld f) { sb.append(String.format("%s%s:%.2f", i > 0 ? "," : "", f.how, f.score)); o = f.match; }
            else if (o instanceof Mth m) { sb.append(String.format("%s%s:%.2f", i > 0 ? "," : "", m.how, m.score)); o = m.match; }
        }
        return sb.toString();
    }

    static int depth(Side side, Cls c) {
        int d = 0;
        Set<Cls> s = new LinkedHashSet<>();
        ancestors(side, c, s);
        return s.size();
    }

    /** names the obfuscator left alone are kept for the members without a counterpart */
    static Pattern keep = Pattern.compile("[A-Za-z_$][\\w$]{3,}");

    static String placeholder(Mth m) {
        return keep.matcher(m.name).matches() ? m.name : placeholder(m, true);
    }

    static String placeholder(Mth m, boolean force) {
        List<Mth> same = m.owner.methods.stream().filter(o -> o.name.equals(m.name)).sorted(Comparator.comparing(o -> o.desc)).toList();
        return "m_" + token(m.owner, null) + "__" + m.name + (same.size() > 1 ? "_" + same.indexOf(m) : "");
    }

    // ---- proguard mapping files -----------------------------------------------------------------

    /** class -> {"" -> new class name, member signature ("type name" / "type name(args)") -> new name}, in file order */
    static Map<String, Map<String, String>> readMapping(Path p) throws IOException {
        Map<String, Map<String, String>> map = new LinkedHashMap<>();
        Map<String, String> cur = null;
        for (String line : Files.readAllLines(p)) {
            if (line.isBlank() || line.strip().startsWith("#")) continue;
            int i = line.indexOf(" -> ");
            if (i < 0) throw new IllegalArgumentException(line);
            if (!line.startsWith(" ")) {
                cur = new LinkedHashMap<>();
                cur.put("", line.substring(i + 4).replaceAll(":$", "").strip());
                map.put(line.substring(0, i).strip(), cur);
            } else {
                String sig = line.substring(0, i).strip().replaceAll("^\\d+:\\d+:", "");
                cur.put(sig, line.substring(i + 4).strip());
            }
        }
        return map;
    }

    static void writeMapping(Path p, Map<String, Map<String, String>> map) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Map<String, String>> e : map.entrySet()) {
            sb.append(e.getKey()).append(" -> ").append(e.getValue().get("")).append(":\n");
            for (Map.Entry<String, String> m : e.getValue().entrySet()) if (!m.getKey().isEmpty()) sb.append("    ").append(m.getKey()).append(" -> ").append(m.getValue()).append('\n');
        }
        Files.writeString(p, sb);
    }

    static final Pattern TYPE = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+");

    static String mapTypes(String sig, Map<String, Map<String, String>> classes) {
        Matcher m = TYPE.matcher(sig);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Map<String, String> c = classes.get(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(c == null ? m.group() : c.get("")));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static void compose(List<String> args) throws IOException {
        if (args.size() != 4 || !args.get(2).equals("-o")) usage();
        Map<String, Map<String, String>> ab = readMapping(Path.of(args.get(0))), bc = readMapping(Path.of(args.get(1)));
        Map<String, Map<String, String>> ac = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : ab.entrySet()) {
            Map<String, String> b = e.getValue(), c = bc.get(b.get("")), r = new LinkedHashMap<>();
            r.put("", c == null ? b.get("") : c.get(""));
            for (Map.Entry<String, String> m : b.entrySet()) {
                if (m.getKey().isEmpty()) continue;
                // the signature in the name space of b: types mapped, name replaced
                String sig = mapTypes(m.getKey(), ab);
                int sp = sig.indexOf(' '), par = sig.indexOf('(');
                String bsig = sig.substring(0, sp + 1) + m.getValue() + (par < 0 ? "" : sig.substring(par));
                String n = c == null ? null : c.get(bsig);
                r.put(m.getKey(), n == null ? m.getValue() : n);
            }
            ac.put(e.getKey(), r);
        }
        writeMapping(Path.of(args.get(3)), ac);
    }

    static void names(List<String> args) throws IOException {
        if (!(args.size() == 4 || args.size() == 7 && args.get(4).equals("-p")) || !args.get(2).equals("-o")) usage();
        Map<String, String> classRenames = new HashMap<>(), methodRenames = new HashMap<>();
        Map<String, Map<String, String>> map = readMapping(Path.of(args.get(0)));
        Map<String, String> names = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(args.get(1)))) {
            line = line.replaceAll("#.*", "").strip();
            if (line.isEmpty()) continue;
            String[] f = line.split("\\s+");
            if (f.length != 2) throw new IllegalArgumentException(line);
            if (names.put(f[0], f[1]) != null) throw new IllegalArgumentException("twice: " + f[0]);
        }
        Set<String> hit = new HashSet<>();
        for (Map<String, String> c : map.values()) {
            for (Map.Entry<String, String> m : c.entrySet()) {
                String v = m.getValue(), n;
                String owner = c.get("");
                if (m.getKey().isEmpty()) {
                    String simple = v.substring(v.lastIndexOf('.') + 1);
                    n = names.get(simple);
                    if (n != null) { hit.add(simple); m.setValue(n.contains(".") ? n : v.substring(0, v.lastIndexOf('.') + 1) + n); classRenames.put(v, m.getValue()); }
                } else if ((n = names.get(owner + "." + v)) != null) {
                    hit.add(owner + "." + v);
                    m.setValue(n);
                    methodRenames.put(owner + "." + v, n);
                } else if ((n = names.get(v)) != null) {
                    hit.add(v);
                    m.setValue(n);
                    methodRenames.put(owner + "." + v, n);
                }
            }
        }
        for (String k : names.keySet()) if (!hit.contains(k)) log.println("W: no such placeholder: " + k);
        // duplicates within a class
        for (Map.Entry<String, Map<String, String>> c : map.entrySet()) {
            Set<String> seen = new HashSet<>();
            for (Map.Entry<String, String> m : c.getValue().entrySet()) {
                if (m.getKey().isEmpty()) continue;
                int par = m.getKey().indexOf('(');
                String k = m.getValue() + (par < 0 ? "" : m.getKey().substring(par));
                if (!seen.add(k)) log.println("W: duplicate member in " + c.getValue().get("") + ": " + k);
            }
        }
        writeMapping(Path.of(args.get(3)), map);
        if (args.size() == 7) { // the parameter names follow the renames
            StringBuilder sb = new StringBuilder();
            for (String line : Files.readAllLines(Path.of(args.get(5)))) {
                int par = line.indexOf('('), end = line.indexOf(')'), dot = line.lastIndexOf('.', par);
                String owner = line.substring(0, dot), method = line.substring(dot + 1, par);
                method = methodRenames.getOrDefault(owner + "." + method, method);
                StringBuilder types = new StringBuilder();
                for (String t : line.substring(par + 1, end).split(",")) {
                    int arr = t.indexOf('[');
                    String base = arr < 0 ? t : t.substring(0, arr);
                    types.append(types.length() > 0 ? "," : "").append(classRenames.getOrDefault(base, base)).append(arr < 0 ? "" : t.substring(arr));
                }
                sb.append(classRenames.getOrDefault(owner, owner)).append('.').append(method).append('(').append(types).append(')').append(line.substring(end + 1)).append('\n');
            }
            Files.writeString(Path.of(args.get(6)), sb);
        }
    }

    static void todo(List<String> args) throws IOException {
        Map<String, Map<String, String>> map = readMapping(Path.of(args.get(0)));
        Set<String> seen = new HashSet<>();
        for (Map<String, String> c : map.values()) {
            for (Map.Entry<String, String> m : c.entrySet()) {
                String v = m.getValue();
                String simple = v.substring(v.lastIndexOf('.') + 1);
                if (simple.matches("(Obf_|f_|m_).*__?.*") && seen.add(simple)) out.println(simple + "\t" + c.get("") + "\t" + m.getKey());
            }
        }
    }

    // ---- parameter names ------------------------------------------------------------------------

    /** a LocalVariableTable entry spans the method: wrong when the obfuscator reuses the slot of a parameter */
    static final boolean LVT = Boolean.getBoolean("lvt");

    static void inject(List<String> args) throws IOException {
        args = new ArrayList<>(args);
        boolean lvt = args.remove("--lvt") || LVT;
        if (args.size() != 3) usage();
        Map<String, String[]> names = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(args.get(1)))) {
            line = line.replaceAll("#.*", "").strip();
            if (line.isEmpty()) continue;
            String[] f = line.split("\\s+");
            names.put(f[0], Arrays.copyOfRange(f, 1, f.length));
        }
        int[] count = new int[1];
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(Path.of(args.get(2))))) {
            for (Map.Entry<String, byte[]> e : readEntries(Path.of(args.get(0))).entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                byte[] b = e.getValue();
                if (b != null) zos.write(e.getKey().endsWith(".class") ? inject(b, names, count, lvt) : b);
                zos.closeEntry();
            }
        }
        log.println("methods with parameter names: " + count[0] + "/" + names.size());
    }

    static byte[] inject(byte[] bytes, Map<String, String[]> names, int[] count, boolean lvt) {
        ClassFile cf = ClassFile.of(ClassFile.DebugElementsOption.PASS_DEBUG, ClassFile.LineNumbersOption.PASS_LINE_NUMBERS);
        ClassModel cm = cf.parse(bytes);
        String owner = cm.thisClass().asInternalName().replace('/', '.');
        return cf.transformClass(cm, (cb, ce) -> {
            if (!(ce instanceof MethodModel mm)) { cb.with(ce); return; }
            String desc = mm.methodType().stringValue();
            String[] ps = names.get(owner + "." + mm.methodName().stringValue() + "(" + javaParams(desc, Map.of()) + ")");
            MethodTypeDesc mt = MethodTypeDesc.ofDescriptor(desc);
            if (ps == null || ps.length != mt.parameterCount()) { cb.with(ce); return; }
            count[0]++;
            boolean isStatic = (mm.flags().flagsMask() & ClassFile.ACC_STATIC) != 0;
            // "-": the name a decompiler gives (var + slot), a parameter without a name confuses it
            String[] given = ps;
            ps = new String[given.length];
            for (int i = 0, slot = isStatic ? 0 : 1; i < ps.length; i++) {
                ps[i] = given[i].equals("-") ? "var" + slot : given[i];
                String d = mt.parameterType(i).descriptorString();
                slot += d.equals("J") || d.equals("D") ? 2 : 1;
            }
            String[] names0 = ps;
            cb.transformMethod(mm, new MethodTransform() {
                @Override public void accept(MethodBuilder mb, MethodElement me) {
                    if (me instanceof MethodParametersAttribute) return;
                    if (me instanceof CodeModel code && lvt) {
                        mb.transformCode(code, new CodeTransform() {
                            @Override public void atStart(CodeBuilder b) {
                                int slot = isStatic ? 0 : 1;
                                for (int i = 0; i < names0.length; i++) {
                                    ClassDesc t = mt.parameterType(i);
                                    b.localVariable(slot, names0[i], t, b.startLabel(), b.endLabel());
                                    slot += t.descriptorString().equals("J") || t.descriptorString().equals("D") ? 2 : 1;
                                }
                            }
                            @Override public void accept(CodeBuilder b, CodeElement e) {
                                if (e instanceof LocalVariable || e instanceof LocalVariableType) return;
                                b.with(e);
                            }
                        });
                    } else {
                        mb.with(me);
                    }
                }
                @Override public void atEnd(MethodBuilder mb) {
                    List<MethodParameterInfo> infos = new ArrayList<>();
                    for (String p : names0) infos.add(MethodParameterInfo.ofParameter(Optional.of(p), 0));
                    mb.with(MethodParametersAttribute.of(infos));
                }
            });
        });
    }

    // ---- local variable slots ---------------------------------------------------------------

    /**
     * Obfuscators (and old compilers) reuse the slot of a variable that is not live anymore for
     * another variable, also the slots of the parameters and with another type. Decompilers then
     * emit one variable for both (a boolean parameter assigned an int ...). This gives every
     * independent value (a web of definitions and uses) a slot of its own.
     */
    static void unshare(List<String> args) throws IOException {
        boolean all = args.remove("--all");
        if (args.size() != 2) usage();
        int[] count = new int[2];
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(Path.of(args.get(1))))) {
            for (Map.Entry<String, byte[]> e : readEntries(Path.of(args.get(0))).entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                byte[] b = e.getValue();
                if (b != null) zos.write(e.getKey().endsWith(".class") ? unshare(b, all, count) : b);
                zos.closeEntry();
            }
        }
        log.println("methods changed: " + count[0] + ", variables split off: " + count[1]);
    }

    static byte[] unshare(byte[] bytes, boolean all, int[] count) {
        ClassFile cf = ClassFile.of();
        ClassModel cm = cf.parse(bytes);
        return cf.transformClass(cm, (cb, ce) -> {
            if (!(ce instanceof MethodModel mm) || mm.code().isEmpty()) { cb.with(ce); return; }
            int[] slots = newSlots(mm, mm.code().get(), all, count);
            if (slots == null) { cb.with(ce); return; }
            count[0]++;
            cb.transformMethod(mm, (mb, me) -> {
                if (!(me instanceof CodeModel code)) { mb.with(me); return; }
                mb.transformCode(code, new CodeTransform() {
                    int i;
                    @Override public void accept(CodeBuilder b, CodeElement e) {
                        if (e instanceof LocalVariable || e instanceof LocalVariableType) return;
                        if (!(e instanceof Instruction)) { b.with(e); return; }
                        int slot = slots[i++];
                        switch (e) {
                        case LoadInstruction x when slot >= 0 -> b.loadLocal(x.typeKind(), slot);
                        case StoreInstruction x when slot >= 0 -> b.storeLocal(x.typeKind(), slot);
                        case IncrementInstruction x when slot >= 0 -> b.iinc(slot, x.constant());
                        case DiscontinuedInstruction.RetInstruction x when slot >= 0 -> b.with(DiscontinuedInstruction.RetInstruction.of(slot));
                        default -> b.with(e);
                        }
                    }
                });
            });
        });
    }

    /** @return the new slot of each instruction (-1: none), null when nothing changes */
    static int[] newSlots(MethodModel mm, CodeModel code, boolean all, int[] count) {
        List<CodeElement> elements = code.elementList();
        List<Instruction> ins = new ArrayList<>();
        Map<Label, Integer> labels = new HashMap<>();
        for (CodeElement e : elements) {
            if (e instanceof Instruction i) ins.add(i);
            else if (e instanceof LabelTarget lt) labels.put(lt.label(), ins.size());
        }
        int n = ins.size();
        // parameters are the definitions 0..
        MethodTypeDesc mt = mm.methodTypeSymbol();
        List<Integer> defSlot = new ArrayList<>();
        int paramSlots = 0;
        if ((mm.flags().flagsMask() & ClassFile.ACC_STATIC) == 0) defSlot.add(paramSlots++);
        for (ClassDesc p : mt.parameterList()) {
            defSlot.add(paramSlots);
            paramSlots += p.descriptorString().equals("J") || p.descriptorString().equals("D") ? 2 : 1;
        }
        int entryDefs = defSlot.size();
        int[] defOf = new int[n], slotOf = new int[n], sizeOf = new int[n];
        Arrays.fill(defOf, -1);
        Arrays.fill(slotOf, -1);
        int maxSlot = paramSlots;
        List<Integer> jsrNext = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            switch (ins.get(i)) {
            case StoreInstruction x -> { slotOf[i] = x.slot(); sizeOf[i] = x.typeKind().slotSize(); defOf[i] = defSlot.size(); defSlot.add(x.slot()); }
            case IncrementInstruction x -> { slotOf[i] = x.slot(); sizeOf[i] = 1; defOf[i] = defSlot.size(); defSlot.add(x.slot()); }
            case LoadInstruction x -> { slotOf[i] = x.slot(); sizeOf[i] = x.typeKind().slotSize(); }
            case DiscontinuedInstruction.RetInstruction x -> { slotOf[i] = x.slot(); sizeOf[i] = 1; }
            case DiscontinuedInstruction.JsrInstruction x -> jsrNext.add(i + 1);
            default -> { }
            }
            if (slotOf[i] >= 0) maxSlot = Math.max(maxSlot, slotOf[i] + sizeOf[i]);
        }
        int defs = defSlot.size();
        BitSet[] defsOfSlot = new BitSet[maxSlot + 1];
        for (int s = 0; s <= maxSlot; s++) defsOfSlot[s] = new BitSet(defs);
        for (int d = 0; d < defs; d++) defsOfSlot[defSlot.get(d)].set(d);
        // exception handlers
        List<int[]> handlers = new ArrayList<>();
        for (ExceptionCatch ec : code.exceptionHandlers()) {
            Integer s = labels.get(ec.tryStart()), e = labels.get(ec.tryEnd()), h = labels.get(ec.handler());
            if (s == null || e == null || h == null) return null;
            handlers.add(new int[] {s, e, h});
        }
        // reaching definitions
        BitSet[] in = new BitSet[n];
        if (n == 0) return null;
        in[0] = new BitSet(defs);
        in[0].set(0, entryDefs);
        ArrayDeque<Integer> work = new ArrayDeque<>();
        work.add(0);
        while (!work.isEmpty()) {
            int i = work.poll();
            BitSet out = (BitSet) in[i].clone();
            if (defOf[i] >= 0) {
                out.andNot(defsOfSlot[slotOf[i]]);
                if (sizeOf[i] == 2) out.andNot(defsOfSlot[slotOf[i] + 1]);
                out.set(defOf[i]);
            }
            List<Integer> succ = new ArrayList<>();
            Instruction x = ins.get(i);
            switch (x) {
            case BranchInstruction b -> {
                Integer t = labels.get(b.target());
                if (t == null) return null;
                succ.add(t);
                if (b.opcode() != Opcode.GOTO && b.opcode() != Opcode.GOTO_W) succ.add(i + 1);
            }
            case DiscontinuedInstruction.JsrInstruction b -> {
                Integer t = labels.get(b.target());
                if (t == null) return null;
                succ.add(t);
            }
            case DiscontinuedInstruction.RetInstruction b -> succ.addAll(jsrNext);
            case TableSwitchInstruction b -> { succ.add(labels.get(b.defaultTarget())); for (SwitchCase sc : b.cases()) succ.add(labels.get(sc.target())); }
            case LookupSwitchInstruction b -> { succ.add(labels.get(b.defaultTarget())); for (SwitchCase sc : b.cases()) succ.add(labels.get(sc.target())); }
            case ReturnInstruction b -> { }
            case ThrowInstruction b -> { }
            default -> succ.add(i + 1);
            }
            for (int[] h : handlers) {
                if (i >= h[0] && i < h[1]) {
                    if (in[h[2]] == null) { in[h[2]] = (BitSet) in[i].clone(); work.add(h[2]); }
                    else { BitSet old = (BitSet) in[h[2]].clone(); in[h[2]].or(in[i]); if (!old.equals(in[h[2]])) work.add(h[2]); }
                    // a store in the range is visible to the handler as well
                    BitSet old = (BitSet) in[h[2]].clone(); in[h[2]].or(out); if (!old.equals(in[h[2]])) work.add(h[2]);
                }
            }
            for (Integer t : succ) {
                if (t == null || t >= n) continue;
                if (in[t] == null) { in[t] = (BitSet) out.clone(); work.add(t); }
                else { BitSet old = (BitSet) in[t].clone(); in[t].or(out); if (!old.equals(in[t])) work.add(t); }
            }
        }
        // webs
        int[] web = new int[defs];
        for (int d = 0; d < defs; d++) web[d] = d;
        java.util.function.IntUnaryOperator find = new java.util.function.IntUnaryOperator() {
            @Override public int applyAsInt(int d) { while (web[d] != d) d = web[d] = web[web[d]]; return d; }
        };
        int[] useDef = new int[n];
        Arrays.fill(useDef, -1);
        for (int i = 0; i < n; i++) {
            if (slotOf[i] < 0 || in[i] == null) continue;
            Instruction x = ins.get(i);
            boolean use = !(x instanceof StoreInstruction);
            if (use) {
                BitSet r = (BitSet) in[i].clone();
                r.and(defsOfSlot[slotOf[i]]);
                int first = r.nextSetBit(0);
                for (int d = r.nextSetBit(0); d >= 0; d = r.nextSetBit(d + 1)) web[find.applyAsInt(d)] = find.applyAsInt(first);
                if (first >= 0 && defOf[i] >= 0) web[find.applyAsInt(defOf[i])] = find.applyAsInt(first); // iinc
                useDef[i] = first >= 0 ? first : defOf[i];
            } else {
                useDef[i] = defOf[i];
            }
        }
        // the web of a parameter keeps the slot, the others of the slot move
        Map<Integer, Integer> webSlot = new HashMap<>();
        Set<Integer> occupied = new HashSet<>();
        for (int d = 0; d < entryDefs; d++) { webSlot.put(find.applyAsInt(d), defSlot.get(d)); occupied.add(defSlot.get(d)); }
        int next = maxSlot + 1, moved = 0;
        int[] result = new int[n];
        Arrays.fill(result, -1);
        for (int i = 0; i < n; i++) {
            if (slotOf[i] < 0) continue;
            if (useDef[i] < 0) { result[i] = slotOf[i]; continue; }
            int w = find.applyAsInt(useDef[i]);
            Integer s = webSlot.get(w);
            if (s == null) {
                boolean shared = slotOf[i] < paramSlots || all && !occupied.add(slotOf[i]);
                if (shared) { s = next; next += 2; moved++; } else { s = slotOf[i]; }
                webSlot.put(w, s);
            }
            result[i] = s;
        }
        if (moved == 0) return null;
        count[1] += moved;
        return result;
    }

    // ---- local variable names ---------------------------------------------------------------

    /** a local variable: its slot (the new side, one variable per slot after unshare --all), the name (the reference side) */
    record Var(int slot, java.lang.classfile.TypeKind kind, String token, String name, String desc) { }

    /**
     * Names the local variables of the methods that a reference (classes with a
     * LocalVariableTable, e.g. the old version compiled from de-obfuscated sources) has under the
     * same class, name and descriptor. The variables of both are aligned in the order of their
     * first store, by kind and by the instruction that produces the stored value.
     * Run on a renamed jar after unshare --all and inject.
     */
    static void locals(List<String> args) throws IOException {
        List<String> a = new ArrayList<>(args);
        Path report = null, out = null;
        int r = a.indexOf("-r");
        if (r >= 0) { report = Path.of(a.get(r + 1)); a.remove(r + 1); a.remove(r); }
        int o = a.indexOf("-o");
        if (o >= 0) { out = Path.of(a.get(o + 1)); a.remove(o + 1); a.remove(o); }
        if (a.size() != 2 || out == null) usage();
        Map<String, MethodModel> refMethods = new HashMap<>();
        for (Map.Entry<String, byte[]> e : readEntries(Path.of(a.get(1))).entrySet()) {
            if (!e.getKey().endsWith(".class") || e.getValue() == null) continue;
            ClassModel cm = ClassFile.of().parse(e.getValue());
            for (MethodModel mm : cm.methods())
                refMethods.put(cm.thisClass().asInternalName() + "." + mm.methodName().stringValue() + mm.methodType().stringValue(), mm);
        }
        int[] count = new int[4]; // methods matched, methods named, variables named, variables
        List<String> lines = new ArrayList<>(), names = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : readEntries(Path.of(a.get(0))).entrySet())
            if (e.getKey().endsWith(".class") && e.getValue() != null) locals(e.getValue(), refMethods, count, names, lines);
        Collections.sort(names);
        Files.write(out, names);
        log.println("local variables named: " + count[2] + "/" + count[3] + " in " + count[1] + "/" + count[0] + " methods with a counterpart");
        if (report != null) Files.write(report, lines);
    }

    static int paramSlots(MethodModel mm) {
        int slots = (mm.flags().flagsMask() & ClassFile.ACC_STATIC) == 0 ? 1 : 0;
        for (ClassDesc p : mm.methodTypeSymbol().parameterList()) slots += p.descriptorString().equals("J") || p.descriptorString().equals("D") ? 2 : 1;
        return slots;
    }

    static void locals(byte[] bytes, Map<String, MethodModel> refMethods, int[] count, List<String> names, List<String> lines) {
        ClassModel cm = ClassFile.of().parse(bytes);
        String owner = cm.thisClass().asInternalName();
        for (MethodModel mm : cm.methods()) {
            if ((mm.flags().flagsMask() & ClassFile.ACC_SYNTHETIC) != 0) continue; // lambdas: inside of other methods in the source
            String desc = mm.methodType().stringValue();
            MethodModel ref = refMethods.get(owner + "." + mm.methodName().stringValue() + desc);
            if (mm.code().isEmpty() || ref == null || ref.code().isEmpty()) continue;
            List<Var> vs = variables(mm, mm.code().get(), false), rs = variables(ref, ref.code().get(), true);
            if (vs.isEmpty()) continue;
            count[0]++;
            count[3] += vs.size();
            int[] m = align(vs, rs);
            int matched = 0, same = 0;
            for (int i = 0; i < m.length; i++) if (m[i] >= 0) { matched++; if (vs.get(i).token().equals(rs.get(m[i]).token())) same++; }
            boolean sure = same * 2 >= matched; // else only the ones produced alike
            StringBuilder sb = new StringBuilder();
            int n = 0;
            boolean instance = (mm.flags().flagsMask() & ClassFile.ACC_STATIC) == 0;
            for (int i = 0; i < m.length; i++) {
                Var v = vs.get(i), w = m[i] < 0 ? null : rs.get(m[i]);
                String name = w == null || w.name() == null || !sure && !v.token().equals(w.token()) ? null : w.name();
                if (name == null && instance && v.token().equals("this")) name = "self"; // a copy of this (an obfuscator's)
                if (name == null) continue;
                sb.append(' ').append(v.slot()).append(':').append(name);
                n++;
            }
            if (n == 0) continue;
            count[1]++;
            count[2] += n;
            String sig = owner.replace('/', '.') + "." + mm.methodName().stringValue() + "(" + javaParams(desc, Map.of()) + ")";
            names.add(sig + sb);
            lines.add(sig + " " + n + "/" + vs.size() + (sure ? "" : " (alike only)"));
        }
    }

    /** the local variables (not the parameters, but the reference's stores to them) in the order of their first store */
    static List<Var> variables(MethodModel mm, CodeModel code, boolean withNames) {
        int ps = paramSlots(mm);
        List<Instruction> ins = new ArrayList<>();
        Map<Label, Integer> at = new HashMap<>();
        List<LocalVariable> lvs = new ArrayList<>();
        for (CodeElement e : code.elementList()) {
            if (e instanceof Instruction i) ins.add(i);
            else if (e instanceof LabelTarget lt) at.put(lt.label(), ins.size());
            else if (e instanceof LocalVariable lv) lvs.add(lv);
        }
        Set<Integer> handlers = new HashSet<>();
        for (ExceptionCatch ec : code.exceptionHandlers()) handlers.add(at.get(ec.handler()));
        Map<Object, Var> seen = new LinkedHashMap<>();
        for (int i = 0; i < ins.size(); i++) {
            // the reference's stores to a parameter: a variable of its own in the jar after unshare
            if (!(ins.get(i) instanceof StoreInstruction st) || st.slot() < ps && !withNames) continue;
            Object key = st.slot();
            String name = null, desc = null;
            if (withNames) {
                LocalVariable best = null;
                int bestStart = -1;
                for (LocalVariable lv : lvs) {
                    if (lv.slot() != st.slot()) continue;
                    Integer s = at.get(lv.startScope()), e = at.get(lv.endScope());
                    if (s == null || e == null || s > i + 1 || i >= e) continue;
                    if (s > bestStart) { best = lv; bestStart = s; }
                }
                if (best != null) { key = best; name = best.name().stringValue(); desc = best.typeSymbol().descriptorString(); }
                else key = "tmp" + i; // a variable of the compiler (for-each, switch on strings ...)
            }
            if (!seen.containsKey(key)) seen.put(key, new Var(st.slot(), st.typeKind(), token(ins, i, handlers), name, desc));
        }
        return new ArrayList<>(seen.values());
    }

    /** what produces the value of the store at i */
    static String token(List<Instruction> ins, int i, Set<Integer> handlers) {
        if (handlers.contains(i)) return "catch";
        if (i == 0) return "-";
        return switch (ins.get(i - 1)) {
            case InvokeInstruction x -> "inv " + x.name().stringValue() + (x.name().equalsString("<init>") ? " " + x.owner().asInternalName() : "");
            case FieldInstruction x -> "fld " + x.name().stringValue();
            case TypeCheckInstruction x -> "cast " + x.type().asInternalName();
            case ConstantInstruction x -> "const";
            case LoadInstruction x -> x.slot() == 0 && x.typeKind() == java.lang.classfile.TypeKind.REFERENCE ? "this" : "load";
            case InvokeDynamicInstruction x -> "indy " + x.name().stringValue();
            case NewReferenceArrayInstruction x -> "newarray " + x.componentType().asInternalName();
            case Instruction x -> x.opcode().name();
        };
    }

    /** weighted longest common subsequence: the same kind, more when produced alike; @return the match of each a, -1: none */
    static int[] align(List<Var> a, List<Var> b) {
        int n = a.size(), m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--)
            for (int j = m - 1; j >= 0; j--) {
                int best = Math.max(dp[i + 1][j], dp[i][j + 1]);
                int s = score(a.get(i), b.get(j));
                if (s > 0) best = Math.max(best, dp[i + 1][j + 1] + s);
                dp[i][j] = best;
            }
        int[] r = new int[n];
        Arrays.fill(r, -1);
        for (int i = 0, j = 0; i < n && j < m; ) {
            int s = score(a.get(i), b.get(j));
            if (s > 0 && dp[i][j] == dp[i + 1][j + 1] + s) { r[i++] = j++; }
            else if (dp[i][j] == dp[i + 1][j]) i++;
            else j++;
        }
        return r;
    }

    static int score(Var a, Var b) {
        if (a.kind() != b.kind()) return 0;
        return a.token().equals(b.token()) ? 3 : 1;
    }

    // ---- renaming ---------------------------------------------------------------------------

    /**
     * Applies a mapping to a jar. Unlike proguard -applymapping, methods that have one name and
     * descriptor in a hierarchy without overriding each other (see {@link #related}) can get
     * different names: every reference is resolved to the method it means first.
     */
    static void rename(List<String> args) throws IOException {
        if (args.size() != 3) usage();
        Path in = Path.of(args.get(0));
        Side side = load(in);
        Map<String, Map<String, String>> mapping = readMapping(Path.of(args.get(1)));
        Map<ClassDesc, ClassDesc> classMap = new HashMap<>();
        Map<String, String> classNames = new HashMap<>();
        Map<Object, String> memberNames = new LinkedHashMap<>();
        int missing = 0;
        for (Cls c : side.classes.values()) {
            Map<String, String> m = mapping.get(c.name.replace('/', '.'));
            if (m == null) continue;
            String n = m.get("").replace('.', '/');
            classNames.put(c.name, n);
            if (!n.equals(c.name)) classMap.put(ClassDesc.ofInternalName(c.name), ClassDesc.ofInternalName(n));
            for (Fld f : c.fields) {
                String v = m.get(javaType(f.desc, Map.of()) + " " + f.name);
                if (v != null) memberNames.put(f, v); else missing++;
            }
            for (Mth me : c.methods) {
                if (me.special()) continue;
                String v = m.get(javaType(me.desc.substring(me.desc.indexOf(')') + 1), Map.of()) + " " + me.name + "(" + javaParams(me.desc, Map.of()) + ")");
                if (v != null) memberNames.put(me, v); else missing++;
            }
        }
        // a rename must not make methods override each other, nor hide a field
        for (Cls c : side.classes.values()) {
            Set<Cls> line = new LinkedHashSet<>();
            ancestors(side, c, line);
            Map<String, Mth> seen = new HashMap<>();
            for (Cls s : line) for (Mth m : s.methods) {
                if (m.special() || s != c && (m.flags & ClassFile.ACC_PRIVATE) != 0) continue;
                Mth o = seen.putIfAbsent(memberNames.getOrDefault(m, m.name) + m.desc, m);
                if (o != null && !(o.name.equals(m.name)) && (s == c || o.owner == c)) log.println("W: " + m + " and " + o + " get one name: " + memberNames.getOrDefault(m, m.name));
            }
        }
        // obfuscators weaken the access of overriding methods, java does not compile that
        Map<Mth, Integer> access = new LinkedHashMap<>();
        for (boolean changed = true; changed;) {
            changed = false;
            for (Cls c : side.classes.values()) {
                Set<Cls> line = new LinkedHashSet<>();
                ancestors(side, c, line);
                line.remove(c);
                // an inherited method implementing an interface of this class
                Set<Cls> all = new LinkedHashSet<>(line);
                all.add(c);
                for (Cls i : all) {
                    if ((i.flags & ClassFile.ACC_INTERFACE) == 0) continue;
                    for (Mth im : i.methods) for (Cls s : all) {
                        if ((s.flags & ClassFile.ACC_INTERFACE) != 0) continue;
                        for (Mth o : s.methods) {
                            if (!o.virtual() || !o.desc.equals(im.desc) || !memberNames.getOrDefault(o, o.name).equals(memberNames.getOrDefault(im, im.name))) continue;
                            int mine = access.getOrDefault(o, o.flags);
                            if (rank(mine) < 3) {
                                access.put(o, mine & ~(ClassFile.ACC_PROTECTED | ClassFile.ACC_PRIVATE) | ClassFile.ACC_PUBLIC);
                                changed = true;
                            }
                        }
                    }
                }
                for (Mth m : c.methods) {
                    if (!m.virtual()) continue;
                    int mine = access.getOrDefault(m, m.flags);
                    for (Cls s : line) for (Mth o : s.methods) {
                        if (!o.virtual() || !o.desc.equals(m.desc) || !memberNames.getOrDefault(o, o.name).equals(memberNames.getOrDefault(m, m.name))) continue;
                        int theirs = (s.flags & ClassFile.ACC_INTERFACE) != 0 ? ClassFile.ACC_PUBLIC : access.getOrDefault(o, o.flags);
                        if (rank(theirs) > rank(mine)) {
                            mine = mine & ~(ClassFile.ACC_PUBLIC | ClassFile.ACC_PROTECTED | ClassFile.ACC_PRIVATE) | theirs & (ClassFile.ACC_PUBLIC | ClassFile.ACC_PROTECTED);
                            access.put(m, mine);
                            changed = true;
                        }
                    }
                }
            }
        }
        if (!access.isEmpty()) log.println("methods with access raised to what they override: " + access.size());
        // obfuscators make fields final that are not assigned in the constructors (or only elsewhere)
        Set<Fld> notFinal = new LinkedHashSet<>();
        for (Cls c : side.classes.values()) for (Fld f : c.fields) {
            if ((f.flags & ClassFile.ACC_FINAL) == 0 || f.constVal != null) continue;
            boolean assigned = false, elsewhere = false;
            for (Object[] use : f.uses) {
                if (!use[1].equals("P")) continue;
                Mth m = (Mth) use[0];
                if (m.owner == c && m.name.equals(f.isStatic() ? "<clinit>" : "<init>")) assigned = true; else elsewhere = true;
            }
            if (!assigned || elsewhere) notFinal.add(f);
        }
        if (!notFinal.isEmpty()) log.println("fields that cannot be final: " + notFinal.size());
        ClassFile cf = ClassFile.of();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(Path.of(args.get(2))))) {
            for (Map.Entry<String, byte[]> e : readEntries(in).entrySet()) {
                byte[] b = e.getValue();
                String name = e.getKey();
                if (b != null && name.endsWith(".class") && side.classes.containsKey(name.substring(0, name.length() - 6))) {
                    Cls c = side.classes.get(name.substring(0, name.length() - 6));
                    b = renameMembers(cf, side, c, b, memberNames, classMap, access, notFinal);
                    name = classNames.getOrDefault(c.name, c.name) + ".class";
                } else if (b == null && name.endsWith("/")) {
                    boolean obf = false;
                    for (String k : classNames.keySet()) if (k.startsWith(name) && !classNames.get(k).startsWith(name)) obf = true;
                    boolean used = false;
                    for (String v : classNames.values()) if (v.startsWith(name)) used = true;
                    if (obf && !used) continue; // a package that is gone
                }
                zos.putNextEntry(new ZipEntry(name));
                if (b != null) zos.write(b);
                zos.closeEntry();
            }
        }
        log.println("renamed: classes " + classMap.size() + ", members " + memberNames.size() + (missing > 0 ? ", not in the mapping " + missing : ""));
    }

    static int rank(int flags) {
        return (flags & ClassFile.ACC_PUBLIC) != 0 ? 3 : (flags & ClassFile.ACC_PROTECTED) != 0 ? 2 : (flags & ClassFile.ACC_PRIVATE) != 0 ? 0 : 1;
    }

    static ClassDesc map(ClassDesc d, Map<ClassDesc, ClassDesc> classMap) {
        if (d.isArray()) return map(d.componentType(), classMap).arrayType();
        return classMap.getOrDefault(d, d);
    }

    static MethodTypeDesc map(MethodTypeDesc d, Map<ClassDesc, ClassDesc> classMap) {
        return MethodTypeDesc.of(map(d.returnType(), classMap), d.parameterList().stream().map(p -> map(p, classMap)).toList());
    }

    /** builds the class again with the new names of the classes and the members */
    static byte[] renameMembers(ClassFile cf, Side side, Cls c, byte[] bytes, Map<Object, String> names, Map<ClassDesc, ClassDesc> cls, Map<Mth, Integer> access, Set<Fld> notFinal) {
        ClassModel cm = cf.parse(bytes);
        int[] fi = new int[1], mi = new int[1];
        return cf.build(map(cm.thisClass().asSymbol(), cls), cb -> {
            for (java.lang.classfile.ClassElement ce : cm) {
                switch (ce) {
                case java.lang.classfile.Superclass sc -> cb.withSuperclass(map(sc.superclassEntry().asSymbol(), cls));
                case java.lang.classfile.Interfaces is -> cb.withInterfaceSymbols(is.interfaces().stream().map(i -> map(i.asSymbol(), cls)).toList());
                case FieldModel fm -> {
                    Fld fld = c.fields.get(fi[0]++);
                    String n = names.get(fld);
                    cb.withField(n == null ? fm.fieldName().stringValue() : n, map(fm.fieldTypeSymbol(), cls), fb -> {
                        for (java.lang.classfile.FieldElement fe : fm) {
                            if (fe instanceof java.lang.classfile.AccessFlags af && notFinal.contains(fld)) fb.withFlags(af.flagsMask() & ~ClassFile.ACC_FINAL);
                            else fb.with(fe);
                        }
                    });
                }
                case MethodModel mm -> {
                    Mth mth = c.methods.get(mi[0]++);
                    String n = names.get(mth);
                    cb.withMethod(n == null ? mm.methodName().stringValue() : n, map(mm.methodTypeSymbol(), cls), access.getOrDefault(mth, mm.flags().flagsMask()), mb -> {
                        for (MethodElement me : mm) {
                            switch (me) {
                            case java.lang.classfile.AccessFlags af -> { }
                            case java.lang.classfile.attribute.ExceptionsAttribute ea ->
                                mb.with(java.lang.classfile.attribute.ExceptionsAttribute.ofSymbols(ea.exceptions().stream().map(x -> map(x.asSymbol(), cls)).toList()));
                            case CodeModel code -> mb.transformCode(code, (b, e) -> {
                                switch (e) {
                                case FieldInstruction i -> {
                                    Fld f = findField(side, i.owner().asInternalName(), i.name().stringValue(), i.type().stringValue(), new HashSet<>());
                                    String fn = f == null ? null : names.get(f);
                                    b.fieldAccess(i.opcode(), map(i.owner().asSymbol(), cls), fn == null ? i.name().stringValue() : fn, map(i.typeSymbol(), cls));
                                }
                                case InvokeInstruction i -> {
                                    Mth m = i.owner().asInternalName().startsWith("[") ? null
                                            : findMethod(side, i.owner().asInternalName(), i.name().stringValue(), i.type().stringValue(), new HashSet<>());
                                    String mn = m == null ? null : names.get(m);
                                    b.invoke(i.opcode(), map(i.owner().asSymbol(), cls), mn == null ? i.name().stringValue() : mn, map(i.typeSymbol(), cls), i.isInterface());
                                }
                                case NewObjectInstruction i -> b.new_(map(i.className().asSymbol(), cls));
                                case NewReferenceArrayInstruction i -> b.anewarray(map(i.componentType().asSymbol(), cls));
                                case NewMultiArrayInstruction i -> b.multianewarray(map(i.arrayType().asSymbol(), cls), i.dimensions());
                                case TypeCheckInstruction i -> {
                                    if (i.opcode() == Opcode.CHECKCAST) b.checkcast(map(i.type().asSymbol(), cls)); else b.instanceOf(map(i.type().asSymbol(), cls));
                                }
                                case ConstantInstruction i when i.constantValue() instanceof ClassDesc d -> b.loadConstant(map(d, cls));
                                case ExceptionCatch ec -> {
                                    if (ec.catchType().isPresent()) b.exceptionCatch(ec.tryStart(), ec.tryEnd(), ec.handler(), map(ec.catchType().get().asSymbol(), cls));
                                    else b.exceptionCatchAll(ec.tryStart(), ec.tryEnd(), ec.handler());
                                }
                                case LocalVariable lv -> { }
                                case LocalVariableType lv -> { }
                                default -> b.with(e);
                                }
                            });
                            default -> mb.with(me);
                            }
                        }
                    });
                }
                default -> cb.with(ce);
                }
            }
        });
    }

    // ---- throws clauses ---------------------------------------------------------------------

    static final Map<String, String> superCache = new HashMap<>();

    /** the super class of a class of the jar or of the library (reflection), null: unknown or Object */
    static String superOf(Side side, String name) {
        Cls c = side.classes.get(name);
        if (c != null) return c.sup;
        return superCache.computeIfAbsent(name, n -> {
            try {
                Class<?> k = Class.forName(n.replace('/', '.'), false, ClassLoader.getSystemClassLoader()).getSuperclass();
                return k == null ? "" : k.getName().replace('.', '/');
            } catch (Throwable e) {
                return "";
            }
        });
    }

    static boolean isA(Side side, String name, String ancestor) {
        for (String n = name; n != null && !n.isEmpty(); n = superOf(side, n)) if (n.equals(ancestor)) return true;
        return false;
    }

    static boolean checked(Side side, String name) {
        return isA(side, name, "java/lang/Throwable") && !isA(side, name, "java/lang/RuntimeException") && !isA(side, name, "java/lang/Error");
    }

    static final Map<String, Set<String>> libThrows = new HashMap<>();

    /** the exceptions a library method declares */
    static Set<String> libraryThrows(String owner, String name, String desc) {
        return libThrows.computeIfAbsent(owner + "." + name + desc, key -> {
            Set<String> r = new HashSet<>();
            try {
                Class<?> c = Class.forName(owner.replace('/', '.'), false, ClassLoader.getSystemClassLoader());
                if (name.equals("<init>")) {
                    for (java.lang.reflect.Constructor<?> m : c.getDeclaredConstructors()) {
                        if (MethodType.methodType(void.class, m.getParameterTypes()).toMethodDescriptorString().equals(desc)) for (Class<?> e : m.getExceptionTypes()) r.add(e.getName().replace('.', '/'));
                    }
                } else {
                    Set<Class<?>> seen = new HashSet<>();
                    ArrayDeque<Class<?>> todo = new ArrayDeque<>(List.of(c));
                    boolean found = false;
                    while (!todo.isEmpty() && !found) {
                        Class<?> k = todo.poll();
                        if (k == null || !seen.add(k)) continue;
                        for (Method m : k.getDeclaredMethods()) {
                            if (m.getName().equals(name) && MethodType.methodType(m.getReturnType(), m.getParameterTypes()).toMethodDescriptorString().equals(desc)) {
                                for (Class<?> e : m.getExceptionTypes()) r.add(e.getName().replace('.', '/'));
                                found = true;
                                break;
                            }
                        }
                        if (k.getSuperclass() != null) todo.add(k.getSuperclass());
                        todo.addAll(List.of(k.getInterfaces()));
                        if (k.isInterface()) todo.add(Object.class);
                    }
                }
            } catch (Throwable e) {
                // unknown library: nothing declared
            }
            return r;
        });
    }

    /**
     * Obfuscators drop the Exceptions attribute, the decompiled methods then have no throws
     * clause and do not compile. This derives the checked exceptions of each method from the
     * code: what is thrown (throw new X) and what the called methods declare, less what the
     * method catches. An overridden method declares what its overriding methods throw.
     */
    static void throwsClauses(List<String> args) throws IOException {
        if (args.size() != 2) usage();
        superCache.clear(); // per jar
        Path in = Path.of(args.get(0));
        Side side = load(in);
        Map<String, byte[]> entries = readEntries(in);
        ClassFile cf = ClassFile.of();
        // per method: the exceptions of each instruction that are not caught, as (app method | exception name)
        Map<Mth, List<Object>> raw = new LinkedHashMap<>();
        Map<Mth, List<Object[]>> handlersOf = new LinkedHashMap<>();
        for (Cls c : side.classes.values()) {
            ClassModel cm = cf.parse(entries.get(c.name + ".class"));
            int mi = 0;
            for (MethodModel mm : cm.methods()) {
                Mth m = c.methods.get(mi++);
                if (mm.code().isEmpty()) continue;
                CodeModel code = mm.code().get();
                List<Instruction> ins = new ArrayList<>();
                Map<Label, Integer> labels = new HashMap<>();
                for (CodeElement e : code.elementList()) {
                    if (e instanceof Instruction i) ins.add(i);
                    else if (e instanceof LabelTarget lt) labels.put(lt.label(), ins.size());
                }
                List<Object[]> handlers = new ArrayList<>();
                for (ExceptionCatch ec : code.exceptionHandlers()) {
                    handlers.add(new Object[] {labels.get(ec.tryStart()), labels.get(ec.tryEnd()), ec.catchType().map(ClassEntry::asInternalName).orElse(null), labels.get(ec.handler())});
                }
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < ins.size(); i++) {
                    List<Object> thrown = new ArrayList<>();
                    if (ins.get(i) instanceof InvokeInstruction inv) {
                        String owner = inv.owner().asInternalName();
                        Mth t = owner.startsWith("[") ? null : findMethod(side, owner, inv.name().stringValue(), inv.type().stringValue(), new HashSet<>());
                        if (t != null) thrown.add(t);
                        else if (!owner.startsWith("[")) thrown.addAll(libraryThrows(side.classes.containsKey(owner) ? libraryOwner(side, owner) : owner, inv.name().stringValue(), inv.type().stringValue()));
                    } else if (ins.get(i) instanceof ThrowInstruction && i > 0 && ins.get(i - 1) instanceof InvokeInstruction inv && inv.name().equalsString("<init>")) {
                        thrown.add(inv.owner().asInternalName()); // throw new X(...)
                    } else if (ins.get(i) instanceof ThrowInstruction && handlerRethrow(ins, handlers, i) != null) {
                        thrown.add(handlerRethrow(ins, handlers, i)); // a handler rethrowing what is on the stack: [dup astore] athrow
                    } else if (ins.get(i) instanceof ThrowInstruction && i > 0 && ins.get(i - 1) instanceof LoadInstruction ld) {
                        // catch (X e) { ...; throw e; }
                        // the handler that stored the variable last before the throw (a finally block: no type)
                        int best = -1;
                        for (Object[] h : handlers) {
                            if (h[3] != null && (Integer) h[3] < i && (Integer) h[3] > best && handlerSlot(ins, (Integer) h[3]) == ld.slot()) best = (Integer) h[3];
                        }
                        for (Object[] h : handlers) {
                            if (h[3] != null && (Integer) h[3] == best && h[2] != null) thrown.add(h[2]);
                        }
                        if (best < 0) { // throw of a parameter
                            MethodTypeDesc mt = MethodTypeDesc.ofDescriptor(m.desc);
                            for (int p = 0, slot = m.isStatic() ? 0 : 1; p < mt.parameterCount(); p++) {
                                String d = mt.parameterType(p).descriptorString();
                                if (slot == ld.slot() && d.startsWith("L")) thrown.add(d.substring(1, d.length() - 1));
                                slot += d.equals("J") || d.equals("D") ? 2 : 1;
                            }
                        }
                    }
                    for (Object t : thrown) list.add(new Object[] {t, i, handlers});
                }
                raw.put(m, list);
                handlersOf.put(m, handlers);
            }
        }
        Set<String> warned = new HashSet<>();
        Map<Mth, Set<String>> result = new LinkedHashMap<>();
        for (Cls c : side.classes.values()) for (Mth m : c.methods) result.put(m, new java.util.TreeSet<>());
        for (boolean changed = true; changed;) {
            changed = false;
            for (Map.Entry<Mth, List<Object>> e : raw.entrySet()) {
                Set<String> mine = result.get(e.getKey());
                for (Object o : e.getValue()) {
                    Object[] t = (Object[]) o;
                    Set<String> thrown = t[0] instanceof Mth tm ? result.get(tm) : Set.of((String) t[0]);
                    int at = (Integer) t[1];
                    @SuppressWarnings("unchecked") List<Object[]> handlers = (List<Object[]>) t[2];
                    for (String x : List.copyOf(thrown)) {
                        if (!checked(side, x) || mine.contains(x)) continue;
                        boolean caught = false;
                        for (Object[] h : handlers) {
                            if (h[0] != null && h[1] != null && at >= (Integer) h[0] && at < (Integer) h[1] && h[2] != null && isA(side, x, (String) h[2])) caught = true;
                        }
                        if (!caught) { mine.add(x); changed = true; }
                    }
                }
            }
            // a catch of an exception nothing in the try block throws: a called method declared it
            // without throwing it. the one that deals with such exceptions itself is taken
            if (!changed) {
                for (Map.Entry<Mth, List<Object>> e : raw.entrySet()) {
                    for (Object[] h : handlersOf.get(e.getKey())) {
                        String x = (String) h[2];
                        if (x == null || h[0] == null || h[1] == null || !checked(side, x) || x.equals("java/lang/Exception") || x.equals("java/lang/Throwable")) continue;
                        boolean thrown = false;
                        List<Mth> called = new ArrayList<>();
                        for (Object o : e.getValue()) {
                            Object[] t = (Object[]) o;
                            int at = (Integer) t[1];
                            if (at < (Integer) h[0] || at >= (Integer) h[1]) continue;
                            Set<String> names = t[0] instanceof Mth tm ? result.get(tm) : Set.of((String) t[0]);
                            for (String n : names) if (isA(side, n, x) || isA(side, x, n)) thrown = true;
                            if (t[0] instanceof Mth tm) called.add(tm);
                        }
                        if (thrown) continue;
                        List<Mth> candidates = new ArrayList<>();
                        for (Mth tm : called) {
                            boolean deals = false;
                            for (Object o : raw.getOrDefault(tm, List.of())) {
                                Object[] t = (Object[]) o;
                                Set<String> names = t[0] instanceof Mth t2 ? result.get(t2) : Set.of((String) t[0]);
                                for (String n : names) if (isA(side, n, x)) deals = true;
                            }
                            if (deals && !candidates.contains(tm)) candidates.add(tm);
                        }
                        List<Mth> distinct = called.stream().distinct().toList();
                        if (candidates.isEmpty() && distinct.size() == 1) candidates = distinct; // the only one it can be
                        // it declared the exception only if every call of it is in a try block catching that
                        List<Mth> ok = new ArrayList<>();
                        for (Mth tm : candidates) {
                            boolean all = true;
                            for (Map.Entry<Mth, List<Object>> e2 : raw.entrySet()) {
                                for (Object o : e2.getValue()) {
                                    Object[] t = (Object[]) o;
                                    if (t[0] != tm) continue;
                                    boolean covered = false;
                                    for (Object[] h2 : handlersOf.get(e2.getKey())) {
                                        if (h2[0] != null && h2[1] != null && h2[2] != null && (Integer) t[1] >= (Integer) h2[0] && (Integer) t[1] < (Integer) h2[1] && isA(side, x, (String) h2[2])) covered = true;
                                    }
                                    if (!covered) all = false;
                                }
                            }
                            if (all) ok.add(tm);
                        }
                        candidates = ok;
                        if (candidates.isEmpty()) {
                            if (warned.add(e.getKey() + x)) log.println("W: " + e.getKey() + " catches " + x + " that nothing throws");
                        } else {
                            for (Mth tm : candidates) if (result.get(tm).add(x)) changed = true;
                        }
                    }
                }
            }
            // an overridden method declares what the overriding ones throw
            for (Cls c : side.classes.values()) {
                Set<Cls> line = new LinkedHashSet<>();
                ancestors(side, c, line);
                line.remove(c);
                for (Mth m : c.methods) {
                    if (!m.virtual() || result.get(m).isEmpty()) continue;
                    for (Cls s : line) for (Mth o : s.methods) {
                        if (o.virtual() && o.name.equals(m.name) && o.desc.equals(m.desc) && result.get(o).addAll(result.get(m))) changed = true;
                    }
                }
            }
        }
        int count = 0;
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(Path.of(args.get(1))))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                byte[] b = e.getValue();
                Cls c = e.getKey().endsWith(".class") ? side.classes.get(e.getKey().substring(0, e.getKey().length() - 6)) : null;
                if (c != null) {
                    int[] mi = new int[1];
                    b = cf.transformClass(cf.parse(b), (cb, ce) -> {
                        if (!(ce instanceof MethodModel mm)) { cb.with(ce); return; }
                        Set<String> x = result.get(c.methods.get(mi[0]++));
                        if (x.isEmpty()) { cb.with(ce); return; }
                        MethodTransform drop = (mb, me) -> { if (!(me instanceof java.lang.classfile.attribute.ExceptionsAttribute)) mb.with(me); };
                        cb.transformMethod(mm, drop.andThen(MethodTransform.endHandler(
                                mb -> mb.with(java.lang.classfile.attribute.ExceptionsAttribute.ofSymbols(x.stream().map(ClassDesc::ofInternalName).toList())))));
                    });
                    for (Mth m : c.methods) {
                        if (result.get(m).isEmpty()) continue;
                        count++;
                        if (m.libOverride && !libraryThrows(libraryOwner(side, c.name), m.name, m.desc).containsAll(result.get(m))) {
                            log.println("W: " + m + " overrides a library method and throws " + result.get(m));
                        }
                    }
                }
                if (b != null) zos.write(b);
                zos.closeEntry();
            }
        }
        log.println("methods with a throws clause: " + count);
    }

    /** the type a typed handler rethrows without leaving it: "athrow" or "dup astore athrow" at its start */
    static String handlerRethrow(List<Instruction> ins, List<Object[]> handlers, int at) {
        for (Object[] h : handlers) {
            if (h[2] == null || h[3] == null) continue;
            int start = (Integer) h[3];
            if (start == at) return (String) h[2];
            if (start == at - 2 && ins.get(start).opcode() == Opcode.DUP && ins.get(start + 1) instanceof StoreInstruction) return (String) h[2];
        }
        return null;
    }

    /** the variable a handler stores the exception to (astore, or dup astore), -1: none */
    static int handlerSlot(List<Instruction> ins, int at) {
        if (ins.get(at) instanceof StoreInstruction st) return st.slot();
        if (ins.get(at).opcode() == Opcode.DUP && at + 1 < ins.size() && ins.get(at + 1) instanceof StoreInstruction st) return st.slot();
        return -1;
    }

    /** the first library class above a class of the jar */
    static String libraryOwner(Side side, String name) {
        String n = name;
        while (side.classes.containsKey(n)) n = side.classes.get(n).sup;
        return n == null ? "java/lang/Object" : n;
    }

    // ---- merged interfaces ------------------------------------------------------------------

    /**
     * Obfuscators merge interfaces into one, the classes then implement a part of it only and the
     * decompiled sources do not compile. This makes the methods of such an interface default
     * methods that do nothing.
     */
    static void defaults(List<String> args) throws IOException {
        if (args.size() < 3) usage();
        Set<String> interfaces = new HashSet<>();
        for (String a : args.subList(2, args.size())) interfaces.add(a.replace('.', '/'));
        int[] count = new int[1];
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(Path.of(args.get(1))))) {
            for (Map.Entry<String, byte[]> e : readEntries(Path.of(args.get(0))).entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                byte[] b = e.getValue();
                if (b != null) zos.write(e.getKey().endsWith(".class") && interfaces.contains(e.getKey().replaceAll("\\.class$", "")) ? defaults(b, count) : b);
                zos.closeEntry();
            }
        }
        log.println("default methods: " + count[0]);
    }

    static byte[] defaults(byte[] bytes, int[] count) {
        ClassFile cf = ClassFile.of();
        ClassModel cm = cf.parse(bytes);
        return cf.transformClass(cm, (cb, ce) -> {
            if (ce instanceof java.lang.classfile.ClassFileVersion) { cb.withVersion(52, 0); return; }
            if (!(ce instanceof MethodModel mm) || (mm.flags().flagsMask() & ClassFile.ACC_ABSTRACT) == 0) { cb.with(ce); return; }
            count[0]++;
            cb.withMethod(mm.methodName(), mm.methodType(), mm.flags().flagsMask() & ~ClassFile.ACC_ABSTRACT, mb -> {
                for (MethodElement me : mm) if (!(me instanceof java.lang.classfile.AccessFlags)) mb.with(me);
                mb.withCode(code -> {
                    java.lang.classfile.TypeKind tk = java.lang.classfile.TypeKind.from(mm.methodTypeSymbol().returnType());
                    switch (tk) {
                    case VOID -> { }
                    case LONG -> code.lconst_0();
                    case FLOAT -> code.fconst_0();
                    case DOUBLE -> code.dconst_0();
                    case REFERENCE -> code.aconst_null();
                    default -> code.iconst_0();
                    }
                    code.return_(tk);
                });
            });
        });
    }

    // ---- main -----------------------------------------------------------------------------------

    static void usage() {
        throw new IllegalArgumentException("""
                usage: JarMatcher match <obf> <ref> [<ref2> ...] -o mapping.txt [-p params.txt] [-r report.txt]
                       JarMatcher names <mapping.txt> <names.txt> -o <out.txt> [-p <params.txt> <params.out.txt>]
                       JarMatcher compose <a2b.txt> <b2c.txt> -o <a2c.txt>
                       JarMatcher inject <in.jar> <params.txt> <out.jar> [--lvt]
                       JarMatcher todo <mapping.txt>
                       JarMatcher rename <in.jar> <mapping.txt> <out.jar>
                       JarMatcher throws <in.jar> <out.jar>
                       JarMatcher unshare <in.jar> <out.jar> [--all]
                       JarMatcher defaults <in.jar> <out.jar> <interface> ...
                       JarMatcher locals <in.jar> <ref> -o <locals.txt> [-r report.txt]""");
    }

    public static void main(String[] args) throws IOException {
        try {
            run(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    /**
     * runs a command and returns what it writes (progress, warnings, results), for a server
     * that owns stdout. Not reentrant: the commands share static state.
     */
    public static synchronized String capture(String... args) throws IOException {
        PrintStream o = out, l = log;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, java.nio.charset.StandardCharsets.UTF_8);
        out = ps;
        log = ps;
        try {
            run(args);
        } finally {
            out = o;
            log = l;
        }
        return baos.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** runs a command, see the class comment */
    public static void run(String... args) throws IOException {
        if (args.length < 2) usage();
        List<String> rest = Arrays.asList(args).subList(1, args.length);
        switch (args[0]) {
        case "match" -> match(rest);
        case "names" -> names(rest);
        case "compose" -> compose(rest);
        case "inject" -> inject(rest);
        case "todo" -> todo(rest);
        case "unshare" -> unshare(new ArrayList<>(rest));
        case "defaults" -> defaults(rest);
        case "rename" -> rename(rest);
        case "throws" -> throwsClauses(rest);
        case "locals" -> locals(rest);
        default -> usage();
        }
    }
}
