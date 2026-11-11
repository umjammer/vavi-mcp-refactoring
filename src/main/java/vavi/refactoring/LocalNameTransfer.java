/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.refactoring;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.LambdaExpr;
import vavi.refactoring.JavaProject.Method;
import vavi.refactoring.LocalVariableRenamer.Local;


/**
 * Local variable names found in the bytecode, applied to decompiled sources.
 * <p>
 * The names come from {@link vavi.refactoring.bytecode.JarMatcher} {@code locals}: for each method,
 * the slot of a local variable and the name the same method of an older version (compiled with
 * a LocalVariableTable) gives it. Decompilers name a local without a LocalVariableTable after its
 * slot ({@code var12}, Fernflower and Vineflower), the slot finds the variable in the source then.
 * Renaming the source instead of writing a LocalVariableTable into the jar keeps the types the
 * decompiler infers, it would take the types of the table (those of the older version) otherwise.
 * <p>
 * The file has one method per line, '#' starts a comment:
 * <pre>
 * pkg.Owner$Inner.method(int,byte[]) 3:count 5:buffer
 * </pre>
 * The types are erased, constructors are {@code <init>}, their parameters may include ones the
 * source does not show (the outer instance, the name and ordinal of an enum).
 * Locals in lambdas and in methods of anonymous or local classes are left alone, their slots
 * are those of other methods.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-11-11 nsano initial version <br>
 */
public class LocalNameTransfer {

    /** how a decompiler names a local after its slot */
    public static final String DEFAULT_SLOT_PATTERN = "var(\\d+)";

    /** "pkg.Owner.Inner.method" → (erased simple parameter types → slot → name) */
    private final Map<String, Map<List<String>, Map<Integer, String>>> methods = new HashMap<>();

    private Pattern slotPattern = Pattern.compile(DEFAULT_SLOT_PATTERN);

    /** per source method, null when it has no entry */
    private final Map<Method, Map<Integer, String>> cache = new IdentityHashMap<>();

    /** reads the file of {@code JarMatcher locals} */
    public static LocalNameTransfer read(Path file) throws IOException {
        return parse(Files.readString(file));
    }

    /** parses the text, see the class comment */
    public static LocalNameTransfer parse(String text) {
        LocalNameTransfer t = new LocalNameTransfer();
        int n = 0;
        for (String line : text.split("\\R")) {
            n++;
            int c = line.indexOf('#');
            if (c >= 0) line = line.substring(0, c);
            line = line.strip();
            if (line.isEmpty()) continue;
            String[] f = line.split("\\s+");
            int p = f[0].indexOf('('), e = f[0].lastIndexOf(')');
            if (p < 0 || e < p) throw new IllegalArgumentException("line " + n + ": no method: " + line);
            String head = f[0].substring(0, p).replace('$', '.');
            List<String> params = new ArrayList<>();
            String ps = f[0].substring(p + 1, e);
            if (!ps.isEmpty()) for (String type : ps.split(",")) params.add(simpleType(type));
            Map<Integer, String> slots = new HashMap<>();
            for (int i = 1; i < f.length; i++) {
                int colon = f[i].indexOf(':');
                if (colon < 0) throw new IllegalArgumentException("line " + n + ": not slot:name: " + f[i]);
                slots.put(Integer.parseInt(f[i].substring(0, colon)), f[i].substring(colon + 1));
            }
            t.methods.computeIfAbsent(head, k -> new HashMap<>()).put(params, slots);
        }
        return t;
    }

    /** @param regex how the decompiler names a local after its slot, group 1 is the slot */
    public LocalNameTransfer slotPattern(String regex) {
        this.slotPattern = Pattern.compile(regex);
        return this;
    }

    /** methods in the file */
    public int size() {
        return methods.values().stream().mapToInt(Map::size).sum();
    }

    /** a.b.C$D[] → D[], List&lt;String&gt; → List */
    static String simpleType(String t) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (char c : t.strip().toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (depth == 0 && !Character.isWhitespace(c)) sb.append(c);
        }
        String s = sb.toString().replace("...", "[]");
        int dims = s.indexOf('[');
        String base = dims < 0 ? s : s.substring(0, dims), suffix = dims < 0 ? "" : s.substring(dims);
        base = base.substring(Math.max(base.lastIndexOf('.'), base.lastIndexOf('$')) + 1);
        return base + suffix;
    }

    /** @return the name for the local, null when there is none */
    public String nameOf(Local l) {
        Matcher m = slotPattern.matcher(l.originalName());
        if (!m.matches()) return null;
        Map<Integer, String> slots = cache.computeIfAbsent(l.method, this::find);
        if (slots == null) return null;
        String name = slots.get(Integer.parseInt(m.group(1)));
        if (name == null || insideLambda(l.declaration, l.method.decl)) return null;
        return name;
    }

    private static boolean insideLambda(Node n, Node method) {
        for (Node p = n.getParentNode().orElse(null); p != null && p != method; p = p.getParentNode().orElse(null)) {
            if (p instanceof LambdaExpr) return true;
        }
        return false;
    }

    /** the slots of a method of the sources, null when there are none */
    private Map<Integer, String> find(Method m) {
        if (m.owner == null) return null; // anonymous class
        String owner = m.owner.getFullyQualifiedName().orElse(null);
        if (owner == null) return null; // local class
        Map<List<String>, Map<Integer, String>> overloads = methods.get(owner + "." + m.name);
        if (overloads == null) return null;
        List<String> params = new ArrayList<>();
        for (Parameter p : m.params) params.add(simpleType(p.getType().asString()) + (p.isVarArgs() ? "[]" : ""));
        Map<Integer, String> slots = overloads.get(params);
        if (slots != null || !m.isConstructor()) return slots;
        // parameters javac adds to a constructor: the outer instance, the name and ordinal of an enum ...
        Map<Integer, String> found = null;
        for (Map.Entry<List<String>, Map<Integer, String>> e : overloads.entrySet()) {
            List<String> k = e.getKey();
            if (k.size() > params.size() && k.subList(k.size() - params.size(), k.size()).equals(params)) {
                if (found != null) return null; // ambiguous
                found = e.getValue();
            }
        }
        return found;
    }
}
