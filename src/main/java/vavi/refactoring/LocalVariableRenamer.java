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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.Type;
import vavi.refactoring.JavaProject.Method;
import vavi.refactoring.JavaProject.Unit;
import vavi.refactoring.ParameterRenamer.Candidate;
import vavi.refactoring.ParameterRenamer.Variable;

import static java.lang.System.getLogger;


/**
 * Gives decompiled local variables meaningful names
 * (JD-GUI/JAD style {@code localQDRect}, {@code localObject1}, {@code arrayOfInt2}, {@code str}, {@code bool}, {@code localIOException}).
 * <p>
 * The sources of a name, in priority order:
 * <ol>
 *  <li>a catch parameter is "e"</li>
 *  <li>the element of a for-each over {@code listeners} is "listener"</li>
 *  <li>a field it is assigned to, {@code this.foo = x}</li>
 *  <li>the parameter of the method/constructor it is passed to, the same way as {@link ParameterRenamer}
 *      ({@code int[] arrayOfInt = new int[1]; GetMovieBox(movie, arrayOfInt)} → "box")</li>
 *  <li>a getter it is initialized with, {@code x = movie.getTrack(1)} → "track"</li>
 *  <li>the getter it is returned from, {@code return x;} in {@code getTrack()} → "track"</li>
 *  <li>its type, {@code Movie} → "movie"</li>
 * </ol>
 * References are resolved by java's scoping rules (blocks, for, catch, try resources, pattern variables,
 * shadowing in nested classes), a local whose references can't be resolved for sure is left alone.
 * A new name never clashes with any name declared or referenced in the outermost method, so the code keeps its meaning.
 * <p>
 * Parameters are renamed first with a {@link ParameterRenamer}, the locals then use those names,
 * and {@link #write()} writes both.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-10-30 nsano initial version <br>
 */
public class LocalVariableRenamer {

    private static final Logger logger = getLogger(LocalVariableRenamer.class.getName());

    /** JD-GUI/JAD local names, checked against the declared type unless a pattern is given */
    public static final String DEFAULT_PATTERN = "local[A-Z]\\w*|arrayOf[A-Z]\\w*|str\\d*|bool\\d*";

    /** a local variable */
    public static class Local {
        /** the innermost method, for propagation */
        final Method method;
        /** the outermost method, the scope of names */
        final Method outer;
        final Node declaration;
        final SimpleName nameNode;
        final Type type;
        /** the region the variable is visible in */
        final Node scope;
        final String originalName;
        final List<NameExpr> refs = new ArrayList<>();
        String name;
        String reason;
        /** true when some references could belong to another variable */
        boolean ambiguous;

        Local(Method method, Method outer, Node declaration, SimpleName nameNode, Type type, Node scope) {
            this.method = method;
            this.outer = outer;
            this.declaration = declaration;
            this.nameNode = nameNode;
            this.type = type;
            this.scope = scope;
            this.originalName = nameNode.getIdentifier();
            this.name = originalName;
        }

        public Method method() {
            return method;
        }

        public String originalName() {
            return originalName;
        }

        public String name() {
            return name;
        }

        public String reason() {
            return reason;
        }

        public String type() {
            return type.asString();
        }

        public int line() {
            return nameNode.getBegin().map(p -> p.line).orElse(0);
        }

        @Override
        public String toString() {
            return method.ownerName + "." + method.name + " " + type.asString() + " " + originalName + (name.equals(originalName) ? "" : " -> " + name + " [" + reason + "]");
        }
    }

    private final ParameterRenamer parameters;
    private final JavaProject project;
    private Pattern pattern = Pattern.compile(DEFAULT_PATTERN);
    private boolean typeSpelled = true;
    private final List<Local> locals = new ArrayList<>();
    /** a parameter of a method or a lambda, it only takes part in scoping */
    private record Param(Method method, int index, Parameter param, Node scope) {
        /** the current name, parameters may be renamed */
        String name() {
            return method != null ? method.names[index] : param.getNameAsString();
        }
    }

    /** outermost method → all locals in it, obfuscated or not */
    private final Map<Method, List<Local>> allLocals = new IdentityHashMap<>();
    /** outermost method → all parameters in it */
    private final Map<Method, List<Param>> allParams = new IdentityHashMap<>();
    /** declaration → method */
    private final Map<Node, Method> methods = new IdentityHashMap<>();


    /**
     * @param parameters its current names are used for propagation, run it first
     */
    public LocalVariableRenamer(ParameterRenamer parameters) {
        this.parameters = parameters;
        this.project = parameters.project();
    }

    JavaProject project() {
        return project;
    }

    /** sets the names to rename, this turns off the declared type check */
    public LocalVariableRenamer pattern(String regex) {
        this.pattern = Pattern.compile(regex);
        this.typeSpelled = false;
        return this;
    }

    /** whether a local is obfuscated: JD spells the type, "localObject" is used for any type */
    boolean isTarget(String name, Type type) {
        if (!pattern.matcher(name).matches()) return false;
        if (!typeSpelled) return true;
        boolean array = false;
        Type t = type;
        while (t.isArrayType()) {
            array = true;
            t = t.asArrayType().getComponentType();
        }
        String simple = ParameterRenamer.simpleName(t);
        String spelled = Pattern.quote(Character.toUpperCase(simple.charAt(0)) + simple.substring(1));
        if (array) return name.matches("arrayOf(ArrayOf)*" + spelled + "\\d*|localObject\\d*");
        return name.matches("local(" + spelled + "|Object)\\d*") ||
                simple.equals("String") && name.matches("str\\d*") ||
                simple.equals("boolean") && name.matches("bool\\d*");
    }

    // ---- collecting ----

    private boolean collected;

    /** finds the locals and resolves their references */
    void collect() {
        collected = true;
        project.methods.forEach(m -> methods.put(m.decl, m));
        for (Method m : project.methods) {
            if (isNested(m.decl)) continue;
            List<Local> all = new ArrayList<>();
            m.decl.walk(n -> {
                Local l = declare(m, n);
                if (l != null) all.add(l);
            });
            // parameters (of the method, nested methods and lambdas) only shadow
            List<Param> params = new ArrayList<>();
            m.decl.walk(Parameter.class, p -> {
                Node owner = p.getParentNode().orElse(m.decl);
                if (owner instanceof CatchClause) return;
                Method pm = methods.get(owner);
                params.add(new Param(pm, pm != null ? pm.params.indexOf(p) : -1, p, owner));
            });
            for (NameExpr ne : m.decl.findAll(NameExpr.class)) {
                String n = ne.getNameAsString();
                List<Local> visible = new ArrayList<>();
                int depth = -1;
                Local best = null;
                for (Local l : all) {
                    if (!l.originalName.equals(n) || !contains(l.scope, ne) || !before(l.nameNode, ne)) continue;
                    visible.add(l);
                    int d = depth(l.scope);
                    if (d > depth) {
                        depth = d;
                        best = l;
                    } else if (d == depth) {
                        best = null; // two candidates at the same level, can't tell
                    }
                }
                // a parameter declared deeper than the local wins
                for (Param b : params) {
                    if (b.param.getNameAsString().equals(n) && contains(b.scope, ne) && depth(b.scope) > depth) {
                        best = null;
                        depth = Integer.MAX_VALUE;
                        visible.clear();
                    }
                }
                if (best != null) {
                    best.refs.add(ne);
                } else if (!visible.isEmpty()) {
                    visible.forEach(l -> l.ambiguous = true);
                }
            }
            allLocals.put(m, all);
            allParams.put(m, params);
            for (Local l : all) if (isTarget(l.originalName, l.type)) locals.add(l);
        }
        logger.log(Level.DEBUG, "locals: " + locals.size());
    }

    /** whether the callable is inside another callable or lambda (an anonymous class method) */
    private static boolean isNested(Node d) {
        for (Node x = d.getParentNode().orElse(null); x != null; x = x.getParentNode().orElse(null))
            if (x instanceof CallableDeclaration<?> || x instanceof LambdaExpr) return true;
        return false;
    }

    /** a local declared by the node, null when it declares none */
    private Local declare(Method m, Node n) {
        if (n instanceof VariableDeclarator v && v.getParentNode().orElse(null) instanceof VariableDeclarationExpr vde) {
            Node holder = vde.getParentNode().orElse(null);
            Node scope;
            if (holder instanceof ForStmt || holder instanceof ForEachStmt || holder instanceof TryStmt) {
                scope = holder;
            } else if (holder != null && holder.getParentNode().orElse(null) instanceof SwitchEntry se) {
                scope = se.getParentNode().orElse(se); // a switch block is one scope
            } else if (holder != null) {
                scope = holder.getParentNode().orElse(holder);
            } else {
                return null;
            }
            return new Local(innermost(m, v), m, v, v.getName(), declaredType(v), scope);
        }
        if (n instanceof Parameter p && p.getParentNode().orElse(null) instanceof CatchClause cc) {
            return new Local(innermost(m, p), m, p, p.getName(), p.getType(), cc);
        }
        if (n instanceof TypePatternExpr tp) {
            // flow scoping, take the block around the statement, that is a superset
            Node s = tp;
            while (s != null && !(s instanceof Statement && s.getParentNode().orElse(null) instanceof BlockStmt)) s = s.getParentNode().orElse(null);
            Node scope = s != null ? s.getParentNode().get() : tp.getParentNode().orElse(tp);
            return new Local(innermost(m, tp), m, tp, tp.getName(), tp.getType(), scope);
        }
        return null;
    }

    /** the type of the variable, the one of a cast or a new initializer for "var" */
    private static Type declaredType(VariableDeclarator v) {
        if (!v.getType().isVarType() || v.getInitializer().isEmpty()) return v.getType();
        Expression e = v.getInitializer().get();
        while (e instanceof EnclosedExpr ee) e = ee.getInner();
        if (e instanceof CastExpr c) return c.getType();
        if (e instanceof ObjectCreationExpr oc && oc.getAnonymousClassBody().isEmpty()) return oc.getType();
        return v.getType();
    }

    /** the innermost method containing the node, for propagation */
    private Method innermost(Method outer, Node n) {
        for (Node x = n.getParentNode().orElse(null); x != null; x = x.getParentNode().orElse(null)) {
            if (x instanceof CallableDeclaration<?> cd) return methods.getOrDefault(cd, outer);
        }
        return outer;
    }

    private static boolean contains(Node scope, Node n) {
        for (Node x = n; x != null; x = x.getParentNode().orElse(null)) if (x == scope) return true;
        return false;
    }

    private static boolean before(Node a, Node b) {
        Position pa = a.getBegin().orElse(null), pb = b.getBegin().orElse(null);
        return pa != null && pb != null && pa.isBefore(pb);
    }

    private static int depth(Node n) {
        int d = 0;
        for (Node x = n; x != null; x = x.getParentNode().orElse(null)) d++;
        return d;
    }

    // ---- name map ----

    private final List<String> warnings = new ArrayList<>();

    /** problems found while applying a name map */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * applies local names given by hand, see {@link NameMap}.
     * @return number of renamed locals
     */
    public int apply(NameMap map) {
        if (map.localKeys().isEmpty()) return 0;
        Set<String> used = new HashSet<>();
        Map<String, Integer> occurrences = new HashMap<>();
        int count = 0;
        List<Local> all = new ArrayList<>();
        if (!collected) collect();
        allLocals.values().forEach(all::addAll);
        all.sort((a, b) -> a.nameNode.getBegin().get().compareTo(b.nameNode.getBegin().get()));
        for (Local l : all) {
            int n = occurrences.merge(System.identityHashCode(l.method) + ":" + l.originalName, 1, Integer::sum);
            String[] found = map.lookupLocal(l.method, l.originalName, n);
            if (found == null) continue;
            used.add(found[0]);
            String name = found[1];
            if (!ParameterRenamer.isValidName(name) || clashes(l, name)) {
                warnings.add("invalid or clashing name " + name + " for " + l);
            } else if (l.ambiguous) {
                warnings.add("references can't be resolved for sure, not renamed: " + l);
            } else {
                l.name = name;
                l.reason = "manual";
                if (!locals.contains(l)) locals.add(l); // a local that is not obfuscated
                count++;
            }
        }
        for (String key : map.localKeys()) if (!used.contains(key)) warnings.add("no local for " + key);
        return count;
    }

    /**
     * gives locals names found elsewhere (e.g. {@link LocalNameTransfer}): a clashing name gets a
     * number (name2, name3 ...), the scopes of a decompiled method do not need to be those of the
     * method the names come from.
     * @param names the name of a local, null for none
     * @param reason why, for {@link Local#reason()}
     * @return number of renamed locals
     */
    public int assign(Function<Local, String> names, String reason) {
        if (!collected) collect();
        List<Local> all = new ArrayList<>();
        allLocals.values().forEach(all::addAll);
        all.sort((a, b) -> a.nameNode.getBegin().get().compareTo(b.nameNode.getBegin().get()));
        Set<Local> listed = Collections.newSetFromMap(new IdentityHashMap<>());
        listed.addAll(locals);
        int count = 0;
        for (Local l : all) {
            String base = names.apply(l);
            if (base == null) continue;
            if (!ParameterRenamer.isValidName(base)) {
                warnings.add("invalid name " + base + " for " + l);
                continue;
            }
            if (l.ambiguous) {
                warnings.add("references can't be resolved for sure, not renamed: " + l);
                continue;
            }
            String name = base;
            for (int n = 2; clashes(l, name); n++) name = base + n;
            l.name = name;
            l.reason = reason;
            if (listed.add(l)) locals.add(l); // a local that is not obfuscated
            count++;
        }
        return count;
    }

    // ---- inference ----

    /**
     * infers names for all obfuscated locals.
     * @return number of renamed locals
     */
    public int infer() {
        if (!collected) collect();
        int n = 0;
        for (Local l : locals) {
            if (l.ambiguous || !l.name.equals(l.originalName)) continue;
            for (Candidate c : candidates(l)) {
                if (!ParameterRenamer.isValidName(c.name()) || parameters.isBad(c.name()) || isTarget(c.name(), l.type) || clashes(l, c.name())) continue;
                l.name = c.name();
                l.reason = c.reason();
                n++;
                break;
            }
        }
        return n;
    }

    /**
     * whether the name clashes: with a variable whose scope overlaps (one would shadow the other,
     * an error in a method), or with an unqualified reference in the scope (it would be captured)
     */
    private boolean clashes(Local l, String name) {
        for (Local v : allLocals.get(l.outer)) {
            if (v != l && v.name.equals(name) && (contains(v.scope, l.declaration) || contains(l.scope, v.declaration))) return true;
        }
        for (Param p : allParams.get(l.outer)) {
            if (p.name().equals(name) && (contains(p.scope, l.declaration) || contains(l.scope, p.param))) return true;
        }
        for (NameExpr ne : l.scope.findAll(NameExpr.class)) {
            if (ne.getNameAsString().equals(name)) return true;
        }
        return false;
    }

    /** get, create ... followed by a name that is not a relation like getIntAt, getStringFromPointer */
    private static final Pattern GETTER = Pattern.compile("(get|create|new|make|find|load|read|open)([A-Z]\\w*)");
    private static final Pattern RELATION = Pattern.compile(".*(From|To|At|In|Of|For|With|By)([A-Z].*)?");

    static String getterName(String method) {
        Matcher m = GETTER.matcher(method);
        if (!m.matches() || RELATION.matcher(m.group(2)).matches()) return null;
        return ParameterRenamer.decapitalize(m.group(2));
    }

    /** listeners → listener, entries → entry, boxes → box */
    static String singular(String s) {
        if (s.endsWith("ies") && s.length() > 3) return s.substring(0, s.length() - 3) + "y";
        if (s.matches(".*(ss|x|z|ch|sh)es")) return s.substring(0, s.length() - 2);
        if (s.endsWith("s") && !s.endsWith("ss") && s.length() > 1) return s.substring(0, s.length() - 1);
        return null;
    }

    private List<Candidate> candidates(Local l) {
        List<Candidate> out = new ArrayList<>();
        // identities, javaparser nodes are equal when they look the same
        Set<Node> refs = Collections.newSetFromMap(new IdentityHashMap<>());
        refs.addAll(l.refs);

        if (l.declaration instanceof Parameter) {
            out.add(new Candidate("e", "catch"));
            out.add(new Candidate("ex", "catch"));
        }

        // for (Foo x : foos)
        if (l.scope instanceof ForEachStmt fe) {
            Expression it = fe.getIterable();
            String plural = it instanceof NameExpr ne ? ne.getNameAsString() :
                    it instanceof FieldAccessExpr fa ? fa.getNameAsString() :
                    it instanceof MethodCallExpr mc && mc.getArguments().isEmpty() ? getterName(mc.getNameAsString()) : null;
            String one = plural != null ? singular(plural) : null;
            if (one != null) out.add(new Candidate(one, "for-each"));
        }

        Node body = l.scope;
        // assigned to a field
        for (AssignExpr a : body.findAll(AssignExpr.class)) {
            if (a.getOperator() != AssignExpr.Operator.ASSIGN || !refs.contains(ParameterRenamer.unwrap(a.getValue()))) continue;
            if (a.getTarget() instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr) out.add(new Candidate(ParameterRenamer.cleanFieldName(fa.getNameAsString()), "field"));
            else if (a.getTarget() instanceof NameExpr ne && l.method.isField(ne.getNameAsString())) out.add(new Candidate(ParameterRenamer.cleanFieldName(ne.getNameAsString()), "field"));
        }

        // passed to a method or a constructor
        out.addAll(parameters.fromCallees(l.method, new Variable() {
            @Override public Type type() { return l.type; }
            @Override public boolean isRef(Expression e) { return refs.contains(e); }
            @Override public boolean isLocal() { return true; }
        }, body));

        // initialized or assigned with a getter
        List<Expression> values = new ArrayList<>();
        if (l.declaration instanceof VariableDeclarator v) v.getInitializer().ifPresent(values::add);
        for (AssignExpr a : body.findAll(AssignExpr.class))
            if (a.getOperator() == AssignExpr.Operator.ASSIGN && a.getTarget() instanceof NameExpr t && refs.contains(t)) values.add(a.getValue());
        for (Expression v : values) {
            if (ParameterRenamer.unwrap(v) instanceof MethodCallExpr mc) {
                String g = getterName(mc.getNameAsString());
                if (g != null) out.add(new Candidate(g, "getter"));
            }
        }

        // returned from a getter
        if (l.method.decl.isMethodDeclaration()) {
            String g = getterName(l.method.name);
            if (g != null && body.findAll(ReturnStmt.class).stream().anyMatch(r -> r.getExpression().map(e -> refs.contains(ParameterRenamer.unwrap(e))).orElse(false)))
                out.add(new Candidate(g, "return"));
        }

        // its type
        String base = parameters.typeBasedName(l.type, false);
        if (base != null) {
            // the digits of localObject2 only tell it from other locals, try without them first
            String digits = l.originalName.replaceAll("^.*?(\\d*)$", "$1");
            out.add(new Candidate(base, "type"));
            out.add(new Candidate(base + digits, "type"));
            out.add(new Candidate(base + (digits.isEmpty() ? "2" : digits + "_"), "type"));
        }
        return out;
    }

    // ---- results ----

    /** all obfuscated locals */
    public List<Local> locals() {
        if (!collected) collect();
        return locals;
    }

    /** renamed locals */
    public List<Local> renames() {
        return locals().stream().filter(l -> !l.name.equals(l.originalName)).toList();
    }

    /** obfuscated locals without a name */
    public List<Local> unresolved() {
        return locals().stream().filter(l -> l.name.equals(l.originalName)).toList();
    }

    /** edits for {@link SourceEditor}: the declaration and the resolved references */
    void edits(Unit u, SourceEditor.EditSink sink) {
        for (Local l : renames()) {
            if (l.method.unit != u) continue;
            sink.rename(l.nameNode, l.name);
            for (NameExpr r : l.refs) sink.rename(r.getName(), l.name);
        }
    }

    /**
     * writes the parameter and local renames into the source files.
     * @return the changed files
     */
    public List<Path> write() throws IOException {
        return new SourceEditor(project, this).write();
    }
}
