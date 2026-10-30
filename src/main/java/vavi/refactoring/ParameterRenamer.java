/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.refactoring;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.Type;
import vavi.refactoring.JavaProject.Method;

import static java.lang.System.getLogger;


/**
 * Gives obfuscated parameters meaningful names.
 * <p>
 * The sources of a name, in priority order:
 * <ol>
 *  <li>a {@link NameMap} given by hand</li>
 *  <li>the same method in a super or sub type</li>
 *  <li>a setter, {@code setFoo(x)} names {@code x} "foo"</li>
 *  <li>a field it is assigned to, {@code this.foo = x}</li>
 *  <li>the parameter of the method/constructor it is passed to (the overload is chosen by argument types,
 *      and all overloads have to agree)</li>
 *  <li>optionally its type, {@code Movie} → "movie"</li>
 * </ol>
 * Names propagate until nothing changes, so a name found at a low level reaches all the wrappers.
 * A name is never taken when it would clash with a parameter, local or any unqualified reference in the method,
 * so the renaming never changes what an identifier refers to.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-10-30 nsano initial version <br>
 */
public class ParameterRenamer {

    private static final Logger logger = getLogger(ParameterRenamer.class.getName());

    static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short",
            "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null", "var", "record", "yield", "sealed", "permits", "_");

    /** types that tell nothing about the meaning */
    static final Set<String> WEAK_TYPES = Set.of("String", "Object", "Boolean", "Integer", "Short", "Byte", "Long", "Float", "Double",
            "Character", "Number", "CharSequence");

    /** names of helper parameters that tell nothing about the argument */
    static final Set<String> GENERIC_NAMES = Set.of("array", "arr", "obj", "object", "param", "arg", "args", "value", "val", "ptr", "p", "src", "dest", "dst");

    /** a decided name */
    public record Rename(Method method, int index, String from, String to, String reason) {

        @Override
        public String toString() {
            return method.ownerName + "." + method.name + " " + method.parameterType(index) + " " + from + " -> " + to + " [" + reason + "]";
        }
    }

    private final JavaProject project;
    private final RenameOptions options;
    private final Map<Method, String[]> reasons = new IdentityHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public ParameterRenamer(JavaProject project, RenameOptions options) {
        this.project = project;
        this.options = options;
    }

    public JavaProject project() {
        return project;
    }

    /** problems found while applying a name map */
    public List<String> warnings() {
        return warnings;
    }

    boolean isObfuscated(String name) {
        return options.isObfuscated(name);
    }

    /** whether the parameter is obfuscated in the source */
    private boolean isTarget(Method m, int i) {
        return options.isObfuscated(m.originalNames[i], m.params.get(i).getType(), m.params.get(i).isVarArgs());
    }

    /** whether the parameter is obfuscated and has no name yet */
    private boolean isPending(Method m, int i) {
        return m.names[i].equals(m.originalNames[i]) && isTarget(m, i);
    }

    private boolean hasTarget(Method m) {
        for (int i = 0; i < m.arity(); i++) if (isTarget(m, i)) return true;
        return false;
    }

    private boolean hasPending(Method m) {
        for (int i = 0; i < m.arity(); i++) if (isPending(m, i)) return true;
        return false;
    }

    /** a name not worth copying */
    boolean isBad(String name) {
        return isObfuscated(name) || options.poisonNames.contains(name);
    }

    private void decide(Method m, int i, String name, String reason) {
        m.names[i] = name;
        reasons.computeIfAbsent(m, k -> new String[m.arity()])[i] = reason;
    }

    // ---- name map ----

    /**
     * applies names given by hand, all names of a method at once.
     * @return number of renamed parameters
     */
    public int apply(NameMap map) {
        if (map.isEmpty()) return 0;
        Set<String> used = new HashSet<>();
        int count = 0;
        for (Method m : project.methods) {
            String[] given = map.lookup(m);
            if (given == null) continue;
            used.add(NameMap.typedKey(m));
            used.add(NameMap.arityKey(m));
            if (given.length != m.arity()) {
                warnings.add("wrong number of names for " + m + ": " + String.join(" ", given));
                continue;
            }
            String[] next = m.names.clone();
            for (int i = 0; i < given.length; i++) {
                if (given[i].equals("-")) continue;
                if (given[i].startsWith("=")) next[i] = given[i].substring(1);
                else if (isPending(m, i)) next[i] = given[i];
            }
            Set<String> locals = localNames(m);
            Set<String> seen = new HashSet<>();
            String clash = null;
            for (int i = 0; i < next.length; i++) {
                if (!seen.add(next[i])) clash = "duplicated name " + next[i];
                else if (!next[i].equals(m.names[i]) && (locals.contains(next[i]) || !isValidName(next[i]))) clash = "invalid or clashing name " + next[i];
            }
            if (clash != null) {
                warnings.add(clash + " for " + m);
                continue;
            }
            for (int i = 0; i < next.length; i++) {
                if (!next[i].equals(m.names[i])) {
                    decide(m, i, next[i], "manual");
                    count++;
                }
            }
        }
        // report keys that hit nothing, and arity keys that hit overloads of different types
        for (String key : map.keys()) {
            if (!used.contains(key)) {
                warnings.add("no method for " + key);
            } else if (key.contains("/")) {
                Set<String> signatures = new TreeSet<>();
                for (Method m : project.methods)
                    if (NameMap.arityKey(m).equals(key) && hasTarget(m))
                        signatures.add(m.signature());
                if (signatures.size() > 1) warnings.add("ambiguous " + key + " matches " + signatures + ", use a typed key if the names differ");
            }
        }
        return count;
    }

    // ---- inference ----

    /**
     * infers names until nothing changes, then optionally names by type and infers again.
     * @return number of renamed parameters
     */
    public int infer() {
        int total = propagate(false);
        if (options.typeFallback) {
            int n = 0;
            for (Method m : project.methods) n += infer(m, true);
            logger.log(Level.DEBUG, "type fallback: " + n);
            total += n + propagate(false);
        }
        return total;
    }

    private int propagate(boolean fallback) {
        int total = 0;
        for (int pass = 1; ; pass++) {
            int n = 0;
            for (Method m : project.methods) n += infer(m, fallback);
            logger.log(Level.DEBUG, "pass " + pass + ": " + n);
            if (n == 0) return total;
            total += n;
        }
    }

    private int infer(Method m, boolean fallback) {
        int changed = 0;
        for (int i = 0; i < m.arity(); i++) {
            if (!isPending(m, i)) continue;
            Set<String> used = usedNames(m);
            for (Candidate c : candidates(m, i, fallback)) {
                if (!isValidName(c.name) || isObfuscated(c.name) || used.contains(c.name)) continue;
                decide(m, i, c.name, c.reason);
                changed++;
                break;
            }
        }
        return changed;
    }

    static boolean isValidName(String n) {
        if (n == null || n.isEmpty() || KEYWORDS.contains(n) || !Character.isJavaIdentifierStart(n.charAt(0))) return false;
        return n.chars().allMatch(Character::isJavaIdentifierPart);
    }

    /** names a parameter could clash with: parameters, locals and unqualified references */
    private static Set<String> usedNames(Method m) {
        Set<String> s = new HashSet<>(Arrays.asList(m.names));
        m.decl.findAll(NameExpr.class).forEach(n -> s.add(n.getNameAsString()));
        m.decl.findAll(Parameter.class).forEach(n -> s.add(n.getNameAsString()));
        s.addAll(localNames(m));
        return s;
    }

    private static Set<String> localNames(Method m) {
        Set<String> s = new HashSet<>();
        m.decl.findAll(VariableDeclarator.class).forEach(v -> s.add(v.getNameAsString()));
        m.decl.findAll(LambdaExpr.class).forEach(l -> l.getParameters().forEach(q -> s.add(q.getNameAsString())));
        return s;
    }

    record Candidate(String name, String reason) {}

    /** a variable whose name is inferred: a parameter or a local */
    interface Variable {
        Type type();
        /** whether the expression refers to this variable */
        boolean isRef(Expression e);
        /** a local takes no generic names of helpers, a parameter of a helper wrapper has them rightly */
        default boolean isLocal() {
            return false;
        }
    }

    private List<Candidate> candidates(Method m, int i, boolean fallback) {
        List<Candidate> out = new ArrayList<>();
        Parameter p = m.params.get(i);
        String pn = m.originalNames[i];

        // the same method in related types
        if (!m.isConstructor()) {
            Set<String> related = project.related(m.ownerName);
            for (Method o : project.byKey(m.key())) {
                if (o == m || !related.contains(o.ownerName) || !sameTypes(m, o)) continue;
                if (!isBad(o.names[i])) out.add(new Candidate(o.names[i], "override " + o.ownerName));
            }
        }

        // setter
        if (m.arity() == 1 && m.name.length() > 3 && m.name.startsWith("set") && Character.isUpperCase(m.name.charAt(3))) {
            out.add(new Candidate(decapitalize(m.name.substring(3)), "setter"));
        }

        Node body = body(m.decl);
        if (body != null) {
            // assigned to a field
            Set<String> fields = new LinkedHashSet<>();
            for (AssignExpr a : body.findAll(AssignExpr.class)) {
                if (a.getOperator() != AssignExpr.Operator.ASSIGN || !isParamRef(unwrap(a.getValue()), pn, m.decl)) continue;
                if (a.getTarget() instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr) fields.add(cleanFieldName(fa.getNameAsString()));
                else if (a.getTarget() instanceof NameExpr ne && m.isField(ne.getNameAsString())) fields.add(cleanFieldName(ne.getNameAsString()));
            }
            fields.forEach(f -> out.add(new Candidate(f, "field")));

            // passed to a method or a constructor
            out.addAll(fromCallees(m, new Variable() {
                @Override public Type type() { return p.getType(); }
                @Override public boolean isRef(Expression e) { return isParamRef(e, pn, m.decl); }
            }, body));
        }

        if (fallback) {
            String base = typeBasedName(p.getType(), p.isVarArgs());
            if (base != null) {
                String digits = pn.replaceAll("^.*?(\\d*)$", "$1");
                out.add(new Candidate(base + digits, "type"));
                out.add(new Candidate(base + (digits.isEmpty() ? "2" : digits + "_"), "type"));
            }
        }
        return out;
    }

    static Node body(CallableDeclaration<?> d) {
        if (d instanceof MethodDeclaration md) return md.getBody().orElse(null);
        if (d instanceof ConstructorDeclaration cd) return cd.getBody();
        return null;
    }

    /** names of the parameters the variable is passed to in the body of m */
    List<Candidate> fromCallees(Method m, Variable v, Node body) {
        Map<String, Integer> hits = new LinkedHashMap<>();
        Map<String, String> why = new HashMap<>();
        for (Node call : body.findAll(Node.class, n -> n instanceof MethodCallExpr || n instanceof ObjectCreationExpr || n instanceof ExplicitConstructorInvocationStmt)) {
            List<Expression> args;
            List<String> keys = new ArrayList<>();
            Set<String> owners = null; // null: callee type unknown
            if (call instanceof MethodCallExpr mc) {
                args = mc.getArguments();
                keys.add(mc.getNameAsString() + "/" + args.size());
                Expression scope = mc.getScope().orElse(null);
                if (scope == null || scope instanceof ThisExpr || scope instanceof SuperExpr) {
                    owners = new HashSet<>();
                    for (Node x = m.decl.getParentNode().orElse(null); x != null; x = x.getParentNode().orElse(null)) {
                        if (x instanceof TypeDeclaration<?> td) {
                            owners.add(td.getNameAsString());
                            owners.addAll(project.related(td.getNameAsString()));
                        }
                    }
                } else if (scope instanceof NameExpr sn && Character.isUpperCase(sn.getNameAsString().charAt(0))) {
                    // static call by a type name
                    owners = new HashSet<>(project.related(sn.getNameAsString()));
                    owners.add(sn.getNameAsString());
                }
            } else if (call instanceof ObjectCreationExpr oc) {
                args = oc.getArguments();
                keys.add("<init>:" + oc.getType().getNameAsString() + "/" + args.size());
            } else {
                ExplicitConstructorInvocationStmt e = (ExplicitConstructorInvocationStmt) call;
                args = e.getArguments();
                if (e.isThis()) keys.add("<init>:" + m.ownerName + "/" + args.size());
                else for (String s : project.supers(m.ownerName)) keys.add("<init>:" + s + "/" + args.size());
            }
            if (keys.isEmpty()) continue;
            String calleeName = keys.getFirst().replaceAll("^<init>:|/.*$", "");
            boolean apiLike = Character.isUpperCase(calleeName.charAt(0));

            List<Method> callees = new ArrayList<>();
            for (String k : keys) for (Method o : project.byKey(k)) if (o != m && (owners == null || owners.contains(o.ownerName))) callees.add(o);

            for (int j = 0; j < args.size(); j++) {
                Expression unwrapped = unwrap(args.get(j));
                Expression a = unwrapCall(unwrapped);
                if (!v.isRef(a)) continue;
                boolean viaCall = a != unwrapped;
                // a wrapper's helper methods (getIntFromPointer(ID(x)) ...) name pointers, not the object
                if (viaCall && !apiLike) continue;

                // overloads that fit all argument types
                List<Method> fits = new ArrayList<>();
                for (Method o : callees) if (fits(o, args, m)) fits.add(o);
                final int jj = j;
                if (fits.stream().anyMatch(o -> isPending(o, jj))) continue; // wait until the overloads have names

                Type type = v.type();
                boolean refType = !viaCall && type.isClassOrInterfaceType() && !WEAK_TYPES.contains(simpleName(type));
                boolean anyTypeMatch = callees.stream().anyMatch(o -> sameType(o.params.get(jj).getType(), type));
                Map<String, Integer> local = new HashMap<>();
                for (Method o : callees) {
                    if (!fits.isEmpty() && !fits.contains(o)) continue;
                    if ((anyTypeMatch || refType) && !sameType(o.params.get(j).getType(), type)) continue;
                    String n = o.names[j];
                    // single letters of generic helpers (b, i, o, s ...) mean nothing, those of C APIs (h, v, r) do
                    if (n.length() == 1 && !apiLike && "xyzwhe".indexOf(n.charAt(0)) < 0) continue;
                    // so do generic names of helpers for a local (setIntInArray(array, offset, value) ...)
                    if (v.isLocal() && !apiLike && GENERIC_NAMES.contains(n)) continue;
                    if (!isBad(n)) local.merge(n, 1, Integer::sum);
                }
                if (local.size() == 1) {
                    String n = local.keySet().iterator().next();
                    hits.merge(n, 1, Integer::sum);
                    why.putIfAbsent(n, "arg " + keys.getFirst());
                } else if (local.size() > 1 && owners != null) {
                    // the overloads disagree, take the one whose parameter type is the same as ours
                    for (Method o : callees) {
                        if (isBad(o.names[j]) || !sameType(o.params.get(j).getType(), type)) continue;
                        hits.merge(o.names[j], 1, Integer::sum);
                        why.putIfAbsent(o.names[j], "arg* " + o.key());
                    }
                }
            }
        }
        List<Candidate> out = new ArrayList<>();
        hits.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue())
                .forEach(e -> out.add(new Candidate(e.getKey(), why.get(e.getKey()))));
        return out;
    }

    /** whether the arguments fit the overload, judged by literals and by our own parameters passed directly */
    private static boolean fits(Method o, List<Expression> args, Method caller) {
        for (int q = 0; q < args.size(); q++) {
            Type ot = o.params.get(q).getType();
            Expression a = args.get(q);
            if (a instanceof NullLiteralExpr && ot.isPrimitiveType()) return false;
            if (a instanceof BooleanLiteralExpr && !ot.asString().equals("boolean")) return false;
            if (a instanceof IntegerLiteralExpr && !ot.isPrimitiveType()) return false;
            if (a instanceof NameExpr an) {
                for (int r = 0; r < caller.arity(); r++)
                    if (caller.originalNames[r].equals(an.getNameAsString()) && !sameType(o.params.get(q), caller.params.get(r))) return false;
            }
        }
        return true;
    }

    // ---- helpers ----

    static Expression unwrap(Expression e) {
        while (true) {
            if (e instanceof CastExpr ce) e = ce.getExpression();
            else if (e instanceof EnclosedExpr ee) e = ee.getInner();
            else return e;
        }
    }

    private Expression unwrapCall(Expression e) {
        if (e instanceof MethodCallExpr mc && mc.getArguments().size() == 1 && options.unwrapCalls.contains(mc.getNameAsString()))
            return unwrap(mc.getArgument(0));
        return e;
    }

    private static boolean isParamRef(Expression e, String name, CallableDeclaration<?> owner) {
        return e instanceof NameExpr ne && ne.getNameAsString().equals(name) && !SourceEditor.isShadowed(ne, name, owner);
    }

    private static boolean sameType(Parameter a, Parameter b) {
        return sameType(a.getType(), b.getType());
    }

    private static boolean sameType(Type a, Type b) {
        return a.asString().equals(b.asString());
    }

    private static boolean sameTypes(Method a, Method b) {
        return a.signature().equals(b.signature());
    }

    static String simpleName(Type t) {
        return t.isClassOrInterfaceType() ? t.asClassOrInterfaceType().getNameAsString() : t.asString();
    }

    static final String EVENT = "(Mouse|MouseWheel|Key|Window|WindowFocus|Focus|Component|Container|Hierarchy|Action|Item|Adjustment|Input|Text|Paint|Invocation|PropertyChange|Change)Event";

    /** a name made from the declared type, null for primitives and types that tell nothing */
    String typeBasedName(Type type, boolean varArgs) {
        Type t = type;
        int dims = varArgs ? 1 : 0;
        while (t.isArrayType()) {
            t = t.asArrayType().getComponentType();
            dims++;
        }
        if (!t.isClassOrInterfaceType()) return null;
        String simple = simpleName(t);
        if (WEAK_TYPES.contains(simple)) return null;
        String base;
        if (options.typeNames.containsKey(simple)) base = options.typeNames.get(simple);
        else if (dims == 0 && simple.matches(EVENT)) base = "e";
        else if (simple.equals("ObjectInputStream")) base = "in";
        else if (simple.equals("ObjectOutputStream")) base = "out";
        else base = decapitalize(simple);
        return dims > 0 ? plural(base) : base;
    }

    /** point → points, clazz → classes, box → boxes, entry → entries */
    static String plural(String s) {
        if (s.equals("clazz")) return "classes";
        if (s.matches(".*(s|x|z|ch|sh)")) return s + "es";
        if (s.matches(".*[^aeiou]y")) return s.substring(0, s.length() - 1) + "ies";
        return s + "s";
    }

    /** QDRect → qdRect, QTFile → qtFile, URL → url, MouseEvent → mouseEvent */
    static String decapitalize(String s) {
        int k = 0;
        while (k < s.length() && Character.isUpperCase(s.charAt(k))) k++;
        if (k == 0) return s;
        if (k == 1) return Character.toLowerCase(s.charAt(0)) + s.substring(1);
        if (k == s.length()) return s.toLowerCase();
        return s.substring(0, k - 1).toLowerCase() + s.substring(k - 1);
    }

    /** _foo_ → foo, mFoo → foo */
    static String cleanFieldName(String f) {
        String s = f.replaceAll("^_+|_+$", "");
        if (s.length() > 1 && s.charAt(0) == 'm' && Character.isUpperCase(s.charAt(1))) s = decapitalize(s.substring(1));
        return s;
    }

    // ---- single rename ----

    /**
     * renames one parameter by hand.
     * @throws IllegalArgumentException when the name is invalid or clashes
     */
    public void rename(Method m, int index, String to) {
        if (!isValidName(to)) throw new IllegalArgumentException("invalid name: " + to);
        if (to.equals(m.names[index])) return;
        Set<String> used = usedNames(m);
        used.remove(m.names[index]);
        used.remove(m.originalNames[index]);
        if (used.contains(to)) throw new IllegalArgumentException("'" + to + "' is already used in " + m);
        decide(m, index, to, "manual");
    }

    /** finds a method by file and line (any line of its declaration header or body) and optionally name */
    public Method find(Path file, int line, String name) {
        Method best = null;
        for (Method m : project.methods) {
            if (!m.unit.path.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) continue;
            if (name != null && !name.equals(m.name) && !(name.equals(m.ownerName) && m.isConstructor())) continue;
            int b = m.decl.getBegin().map(x -> x.line).orElse(0), e = m.decl.getEnd().map(x -> x.line).orElse(0);
            if (line < b || line > e) continue;
            // the innermost one
            if (best == null || b >= best.decl.getBegin().map(x -> x.line).orElse(0)) best = m;
        }
        return best;
    }

    // ---- results ----

    /** all decided renames */
    public List<Rename> renames() {
        List<Rename> out = new ArrayList<>();
        for (Method m : project.methods) {
            for (int i = 0; i < m.arity(); i++) {
                if (m.names[i].equals(m.originalNames[i])) continue;
                String[] r = reasons.get(m);
                out.add(new Rename(m, i, m.originalNames[i], m.names[i], r != null && r[i] != null ? r[i] : ""));
            }
        }
        return out;
    }

    /** methods that still have obfuscated parameters */
    public List<Method> unresolved() {
        return project.methods.stream().filter(this::hasPending).toList();
    }

    /**
     * @param original true: obfuscated parameters in the sources, false: those still without a name
     */
    public int countObfuscated(boolean original) {
        int n = 0;
        for (Method m : project.methods) for (int i = 0; i < m.arity(); i++) if (original ? isTarget(m, i) : isPending(m, i)) n++;
        return n;
    }

    /**
     * writes the renames into the source files.
     * @return the changed files
     */
    public List<Path> write() throws IOException {
        return new SourceEditor(project).write();
    }
}
