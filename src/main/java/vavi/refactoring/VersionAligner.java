/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.refactoring;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.javaparser.JavaParser;
import com.github.javaparser.JavaToken;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.LocalClassDeclarationStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.TryStmt;

import static java.lang.System.getLogger;


/**
 * Makes the decompiled sources of two versions of a program identical where the code is the same,
 * so that a diff between them shows what changed and nothing else.
 * <p>
 * Two versions de-obfuscated one by one get different names for the same variable (one is named
 * by the bytecode of a reference, the other is {@code var12} or named after a slot that holds
 * something else) and their members come in different orders. Two passes:
 * <dl>
 *  <dt>names</dt>
 *  <dd>the tokens of each pair of files are aligned (like python's difflib), every local variable,
 *      parameter and label is resolved to its declaration on both sides by java's scoping rules.
 *      A pair of declarations that (nearly) always correspond and are declared with the same type
 *      gets one name: a real name wins over {@code var12} and over a numbered one ({@code offset2},
 *      {@code self}), on a tie the name of the preferred side wins. "nearly" lets a method whose
 *      parameters were reordered get the right names, its signature disagrees with every use.
 *      A rename that clashes (another variable visible with the name, an unqualified reference
 *      to a field ...) is tried the other way round, never to give a real name up for {@code var12}.
 *      Both sides are renamed.</dd>
 *  <dt>members</dt>
 *  <dd>the members of side A are ordered like those of side B, where it does not change what runs:
 *      methods, constructors, nested types, fields without an initializer and constants
 *      ({@code static final} with a literal, no code in {@code <clinit>}). Fields with an initializer
 *      and initializer blocks stay where they are. Members named differently on purpose are paired
 *      by an alias file: {@code pkg/Class.java nameInA nameInB} per line.</dd>
 * </dl>
 * {@link #run()} does names, members, names (the tokens are aligned better then).
 * Line numbers of side B never change, formatting and comments are kept.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-11-12 nsano initial version <br>
 */
public class VersionAligner {

    private static final Logger logger = getLogger(VersionAligner.class.getName());

    /** a side of the comparison */
    public enum Side { A, B }

    /** a renamed variable */
    public record Rename(Side side, String file, int line, String from, String to) {
        @Override public String toString() {
            return side + " " + file + ":" + line + " " + from + " -> " + to;
        }
    }

    /** reordered members of a file of side A */
    public record Move(String file, int members) {
        @Override public String toString() {
            return file + ": " + members + " members";
        }
    }

    /** how a decompiler names a variable it knows nothing about */
    public static final String DEFAULT_UNNAMED = "var\\d+";

    private final Path rootA, rootB;
    /** relative path → source, the files as they are now (edited in memory) */
    private final Map<String, String> sourcesA = new TreeMap<>(), sourcesB = new TreeMap<>();
    private final Set<String> changedA = new HashSet<>(), changedB = new HashSet<>();
    private Side prefer = Side.A;
    private Pattern unnamed = Pattern.compile(DEFAULT_UNNAMED);
    /** relative path → (name in A → name in B) */
    private final Map<String, Map<String, String>> aliases = new HashMap<>();
    private final JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25));

    private final List<Rename> renames = new ArrayList<>();
    private final List<Move> moves = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    /** renames dropped for clashes in the last names pass */
    private int dropped;

    /**
     * @param a source root of one version (its members are reordered)
     * @param b source root of the other version (its members stay)
     */
    public VersionAligner(Path a, Path b) throws IOException {
        this.rootA = a;
        this.rootB = b;
        read(a, sourcesA);
        read(b, sourcesB);
    }

    private static void read(Path root, Map<String, String> sources) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java") && Files.isRegularFile(x)).sorted().toList()) {
                sources.put(root.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        }
    }

    /** whose name wins when both are real names (default: A) */
    public VersionAligner prefer(Side side) {
        this.prefer = side;
        return this;
    }

    /** how a decompiler names a variable it knows nothing about (default: {@value DEFAULT_UNNAMED}) */
    public VersionAligner unnamed(String regex) {
        this.unnamed = Pattern.compile(regex);
        return this;
    }

    /** members named differently on purpose: "pkg/Class.java nameInA nameInB" per line, '#' starts a comment */
    public VersionAligner aliases(Path file) throws IOException {
        for (String line : Files.readAllLines(file)) {
            int c = line.indexOf('#');
            if (c >= 0) line = line.substring(0, c);
            String[] f = line.strip().split("\\s+");
            if (f.length == 3) aliases.computeIfAbsent(f[0], k -> new HashMap<>()).put(f[1], f[2]);
        }
        return this;
    }

    public List<Rename> renames() {
        return Collections.unmodifiableList(renames);
    }

    public List<Move> moves() {
        return Collections.unmodifiableList(moves);
    }

    public List<String> problems() {
        return Collections.unmodifiableList(problems);
    }

    /** files of both sides that differ now */
    public int differingFiles() {
        int n = 0;
        for (Map.Entry<String, String> e : sourcesA.entrySet()) {
            String b = sourcesB.get(e.getKey());
            if (b != null && !b.equals(e.getValue())) n++;
        }
        return n;
    }

    /** names, members, names */
    public VersionAligner run() {
        alignNames();
        alignMembers();
        alignNames();
        return this;
    }

    /** writes the changed files */
    public List<Path> write() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String f : changedA) out.add(Files.writeString(rootA.resolve(f), sourcesA.get(f)));
        for (String f : changedB) out.add(Files.writeString(rootB.resolve(f), sourcesB.get(f)));
        return out;
    }

    // ---- names ----

    /**
     * renames until nothing changes (a rename can make another one possible).
     * @return renamed variables
     */
    public int alignNames() {
        int total = 0;
        for (int pass = 0; pass < 10; pass++) {
            int n = 0;
            dropped = 0;
            for (String f : sourcesA.keySet()) {
                if (!sourcesB.containsKey(f)) continue;
                n += alignNames(f);
            }
            logger.log(Level.DEBUG, "names pass " + pass + ": " + n + " renamed, " + dropped + " clash");
            total += n;
            if (n == 0) break;
        }
        return total;
    }

    /** a local variable, parameter or label */
    static final class Var {
        final SimpleName name;
        final String type;
        /** the region it is visible in */
        final Node scope;
        final boolean label;
        final List<SimpleName> refs = new ArrayList<>();
        boolean ambiguous;

        Var(SimpleName name, String type, Node scope, boolean label) {
            this.name = name;
            this.type = type;
            this.scope = scope;
            this.label = label;
        }

        String id() {
            return name.getIdentifier();
        }
    }

    /** a parsed source with its variables */
    final class Source {
        final String file;
        final String text;
        final int[] lineStarts;
        final CompilationUnit cu;
        final List<Var> vars = new ArrayList<>();
        /** offset of a name token → its variable */
        final Map<Integer, Var> at = new HashMap<>();
        /** unresolved unqualified names (fields, types ...) */
        final List<NameExpr> unresolved = new ArrayList<>();
        final List<JavaToken> tokens = new ArrayList<>();

        Source(String file, String text) {
            this.file = file;
            this.text = text;
            this.lineStarts = lineStarts(text);
            ParseResult<CompilationUnit> r = parser.parse(text);
            if (!r.isSuccessful() || r.getResult().isEmpty()) {
                problems.add(file + ": " + r.getProblems());
                this.cu = null;
                return;
            }
            this.cu = r.getResult().get();
            cu.getTokenRange().ifPresent(tr -> tr.forEach(t -> {
                if (!t.getCategory().isWhitespaceOrComment() && t.getRange().isPresent()) tokens.add(t);
            }));
            collect();
        }

        private void collect() {
            cu.walk(n -> {
                Var v = declare(n);
                if (v != null) vars.add(v);
            });
            Map<String, List<Var>> byName = new HashMap<>();
            for (Var v : vars) if (!v.label) byName.computeIfAbsent(v.id(), k -> new ArrayList<>()).add(v);
            for (NameExpr ne : cu.findAll(NameExpr.class)) {
                String n = ne.getNameAsString();
                Var best = null;
                int depth = -1;
                List<Var> visible = new ArrayList<>();
                for (Var v : byName.getOrDefault(n, List.of())) {
                    boolean parameter = v.name.getParentNode().orElse(null) instanceof Parameter;
                    if (!contains(v.scope, ne) || !parameter && !before(v.name, ne)) continue;
                    visible.add(v);
                    int d = depth(v.scope);
                    if (d > depth) {
                        depth = d;
                        best = v;
                    } else if (d == depth) {
                        best = null; // can't tell
                    }
                }
                if (best != null && shadowedByMember(ne, best.scope, n)) best = null;
                if (best != null) {
                    best.refs.add(ne.getName());
                } else if (!visible.isEmpty()) {
                    visible.forEach(v -> v.ambiguous = true);
                } else {
                    unresolved.add(ne);
                }
            }
            for (Var v : vars) {
                if (!v.label) continue;
                Node stmt = v.scope;
                for (BreakStmt b : stmt.findAll(BreakStmt.class)) b.getLabel().filter(l -> l.getIdentifier().equals(v.id()) && innermostLabel(b, v.id()) == stmt).ifPresent(v.refs::add);
                for (ContinueStmt c : stmt.findAll(ContinueStmt.class)) c.getLabel().filter(l -> l.getIdentifier().equals(v.id()) && innermostLabel(c, v.id()) == stmt).ifPresent(v.refs::add);
            }
            for (Var v : vars) {
                at.put(offset(v.name), v);
                for (SimpleName r : v.refs) at.put(offset(r), v);
            }
        }

        /** a variable declared by the node, null when it declares none */
        private Var declare(Node n) {
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
                return new Var(v.getName(), v.getType().asString(), scope, false);
            }
            if (n instanceof Parameter p) {
                Node owner = p.getParentNode().orElse(null);
                if (owner instanceof CatchClause || owner instanceof CallableDeclaration<?> || owner instanceof LambdaExpr) {
                    return new Var(p.getName(), p.getType().asString() + (p.isVarArgs() ? "..." : ""), owner, false);
                }
                return null;
            }
            if (n instanceof TypePatternExpr tp) {
                // flow scoping, the block around the statement is a superset
                Node s = tp;
                while (s != null && !(s instanceof Statement && s.getParentNode().orElse(null) instanceof BlockStmt)) s = s.getParentNode().orElse(null);
                Node scope = s != null ? s.getParentNode().get() : tp.getParentNode().orElse(tp);
                return new Var(tp.getName(), tp.getType().asString(), scope, false);
            }
            if (n instanceof LabeledStmt ls) {
                return new Var(ls.getLabel(), ":label", ls, true);
            }
            return null;
        }

        int offset(Node n) {
            return VersionAligner.offset(lineStarts, n.getBegin().get());
        }

        int line(Node n) {
            return n.getBegin().map(p -> p.line).orElse(0);
        }
    }

    /** the labeled statement a break/continue with the label belongs to */
    private static Node innermostLabel(Node n, String label) {
        for (Node x = n.getParentNode().orElse(null); x != null; x = x.getParentNode().orElse(null)) {
            if (x instanceof LabeledStmt ls && ls.getLabel().getIdentifier().equals(label)) return ls;
        }
        return null;
    }

    /** whether a field of an anonymous or local class between the reference and the scope has the name */
    private static boolean shadowedByMember(Node ne, Node scope, String name) {
        for (Node x = ne.getParentNode().orElse(null); x != null && x != scope; x = x.getParentNode().orElse(null)) {
            List<BodyDeclaration<?>> members = null;
            if (x instanceof ObjectCreationExpr oc && oc.getAnonymousClassBody().isPresent()) members = oc.getAnonymousClassBody().get();
            else if (x instanceof TypeDeclaration<?> td) members = td.getMembers();
            else if (x instanceof LocalClassDeclarationStmt lc) members = lc.getClassDeclaration().getMembers();
            if (members == null) continue;
            for (BodyDeclaration<?> m : members) {
                if (m instanceof FieldDeclaration f && f.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(name))) return true;
            }
        }
        return false;
    }

    private static final Pattern NUMBERED = Pattern.compile(".*[A-Za-z_]\\d+|.*_\\d+|self");

    /** 0: unnamed, 1: numbered, 2: a real name */
    int score(String name) {
        if (unnamed.matcher(name).matches()) return 0;
        if (NUMBERED.matcher(name).matches()) return 1;
        return 2;
    }

    /** a token that refers to no variable */
    private static final Var NONE = new Var(new SimpleName("_"), "", null, false);

    private record Pair(Var a, Var b) {}

    /** @return renamed variables */
    private int alignNames(String file) {
        Source a = new Source(file, sourcesA.get(file)), b = new Source(file, sourcesB.get(file));
        if (a.cu == null || b.cu == null) return 0;
        // correspondences of the aligned tokens
        Map<Var, Map<Var, Integer>> corr = new IdentityHashMap<>(), rcorr = new IdentityHashMap<>();
        List<String> ta = a.tokens.stream().map(JavaToken::getText).toList(), tb = b.tokens.stream().map(JavaToken::getText).toList();
        for (int[] op : new SequenceMatcher(ta, tb).opcodes()) {
            boolean equal = op[0] == 0;
            if (!equal && !(op[0] == 1 && op[2] - op[1] == op[4] - op[3])) continue;
            for (int i = op[1], j = op[3]; i < op[2]; i++, j++) {
                JavaToken x = a.tokens.get(i), y = b.tokens.get(j);
                if (!equal && !(x.getCategory().isIdentifier() && y.getCategory().isIdentifier())) continue;
                Var va = a.at.getOrDefault(VersionAligner.offset(a.lineStarts, x.getRange().get().begin), NONE);
                Var vb = b.at.getOrDefault(VersionAligner.offset(b.lineStarts, y.getRange().get().begin), NONE);
                if (va == NONE && vb == NONE) continue;
                corr.computeIfAbsent(va, k -> new IdentityHashMap<>()).merge(vb, 1, Integer::sum);
                rcorr.computeIfAbsent(vb, k -> new IdentityHashMap<>()).merge(va, 1, Integer::sum);
            }
        }
        // pairs and which side keeps its name
        Map<Pair, Side> state = new LinkedHashMap<>();
        for (Map.Entry<Var, Map<Var, Integer>> e : corr.entrySet()) {
            Var va = e.getKey();
            if (va == NONE || va.ambiguous) continue;
            Var vb = dominant(e.getValue());
            if (vb == null || vb == NONE || vb.ambiguous || dominant(rcorr.get(vb)) != va) continue;
            if (!va.type.equals(vb.type) || va.id().equals(vb.id())) continue;
            int sa = score(va.id()), sb = score(vb.id());
            state.put(new Pair(va, vb), sa > sb || sa == sb && prefer == Side.A ? Side.A : Side.B);
        }
        // renames that do not clash, the other way round for those that do
        Set<Pair> tried = new HashSet<>();
        Map<Var, String> ra, rb;
        while (true) {
            ra = new IdentityHashMap<>();
            rb = new IdentityHashMap<>();
            for (Map.Entry<Pair, Side> e : state.entrySet()) {
                if (e.getValue() == Side.B) ra.put(e.getKey().a, e.getKey().b.id());
                else rb.put(e.getKey().b, e.getKey().a.id());
            }
            ra = check(a, ra);
            rb = check(b, rb);
            List<Pair> failed = new ArrayList<>();
            for (Map.Entry<Pair, Side> e : state.entrySet()) {
                if (e.getValue() == Side.B ? !ra.containsKey(e.getKey().a) : !rb.containsKey(e.getKey().b)) failed.add(e.getKey());
            }
            if (failed.stream().allMatch(tried::contains)) break;
            for (Pair p : failed) {
                String other = state.get(p) == Side.B ? p.a.id() : p.b.id();
                if (tried.contains(p) || score(other) == 0) {
                    state.remove(p); // no other way, or a real name would become var12
                } else {
                    state.put(p, state.get(p) == Side.B ? Side.A : Side.B);
                    tried.add(p);
                }
            }
        }
        dropped += state.size() - ra.size() - rb.size();
        for (Map.Entry<Var, String> e : ra.entrySet()) renames.add(new Rename(Side.A, file, a.line(e.getKey().name), e.getKey().id(), e.getValue()));
        for (Map.Entry<Var, String> e : rb.entrySet()) renames.add(new Rename(Side.B, file, b.line(e.getKey().name), e.getKey().id(), e.getValue()));
        if (!ra.isEmpty()) {
            sourcesA.put(file, edit(a, ra));
            changedA.add(file);
        }
        if (!rb.isEmpty()) {
            sourcesB.put(file, edit(b, rb));
            changedB.add(file);
        }
        return ra.size() + rb.size();
    }

    /** the variable all aligned occurrences correspond to, or nearly all (parameters reordered: the signature disagrees) */
    private static Var dominant(Map<Var, Integer> c) {
        if (c == null || c.isEmpty()) return null;
        Var top = null;
        int n = 0, sum = 0;
        for (Map.Entry<Var, Integer> e : c.entrySet()) {
            sum += e.getValue();
            if (e.getValue() > n) {
                n = e.getValue();
                top = e.getKey();
            }
        }
        int rest = sum - n;
        return rest == 0 && n >= 2 || n >= 3 && n >= 4 * rest ? top : null;
    }

    /** the renames that do not clash */
    private static Map<Var, String> check(Source s, Map<Var, String> renames) {
        Map<Var, String> r = new IdentityHashMap<>(renames);
        while (true) {
            Set<Var> bad = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Map.Entry<Var, String> e : r.entrySet()) {
                Var v = e.getKey();
                String to = e.getValue();
                // another variable with the same name visible in an overlapping region
                for (Var o : s.vars) {
                    if (o == v || o.label != v.label || !r.getOrDefault(o, o.id()).equals(to)) continue;
                    if (contains(v.scope, o.scope) || contains(o.scope, v.scope)) {
                        bad.add(v);
                        if (r.containsKey(o)) bad.add(o);
                    }
                }
                // an unqualified name that is no variable (a field ...) would be captured
                if (!v.label) {
                    for (NameExpr ne : s.unresolved) {
                        if (ne.getNameAsString().equals(to) && contains(v.scope, ne)) {
                            bad.add(v);
                            break;
                        }
                    }
                }
            }
            if (bad.isEmpty()) return r;
            bad.forEach(r::remove);
        }
    }

    private static String edit(Source s, Map<Var, String> renames) {
        record Edit(int begin, int end, String text) {}
        List<Edit> edits = new ArrayList<>();
        for (Map.Entry<Var, String> e : renames.entrySet()) {
            Var v = e.getKey();
            for (SimpleName n : concat(v.name, v.refs)) {
                int b = s.offset(n);
                edits.add(new Edit(b, b + n.getIdentifier().length(), e.getValue()));
            }
        }
        edits.sort(Comparator.comparingInt(Edit::begin).reversed());
        StringBuilder sb = new StringBuilder(s.text);
        for (Edit e : edits) sb.replace(e.begin, e.end, e.text);
        return sb.toString();
    }

    private static List<SimpleName> concat(SimpleName n, List<SimpleName> l) {
        List<SimpleName> out = new ArrayList<>(l);
        out.add(n);
        return out;
    }

    // ---- members ----

    /** a member on lines of its own */
    private record Member(String key, boolean movable, int begin, int end) {}

    /**
     * orders the members of the top level types of side A like those of side B.
     * @return moved members
     */
    public int alignMembers() {
        int total = 0;
        for (String f : sourcesA.keySet()) {
            if (!sourcesB.containsKey(f)) continue;
            String sa = sourcesA.get(f), sb = sourcesB.get(f);
            ParseResult<CompilationUnit> pa = parser.parse(sa), pb = parser.parse(sb);
            if (pa.getResult().isEmpty() || pb.getResult().isEmpty() || !pa.isSuccessful() || !pb.isSuccessful()) {
                problems.add(f + ": parse error");
                continue;
            }
            Map<String, String> alias = aliases.getOrDefault(f, Map.of());
            String text = sa;
            int moved = 0;
            // the last type first, the offsets of the others stay
            List<TypeDeclaration<?>> types = new ArrayList<>(pa.getResult().get().getTypes());
            Collections.reverse(types);
            for (TypeDeclaration<?> ta : types) {
                TypeDeclaration<?> tb = pb.getResult().get().getTypes().stream().filter(t -> t.getNameAsString().equals(ta.getNameAsString())).findFirst().orElse(null);
                if (tb == null) continue;
                int[] lsa = lineStarts(sa), lsb = lineStarts(sb);
                List<Member> ma = members(ta, sa, lsa, alias), mb = members(tb, sb, lsb, Map.of());
                Map<String, Integer> order = new HashMap<>();
                for (int k = 0; k < mb.size(); k++) order.putIfAbsent(mb.get(k).key, k);
                List<Member> slots = ma.stream().filter(m -> m.movable && order.containsKey(m.key)).toList();
                if (slots.size() < 2 || slots.stream().map(Member::key).distinct().count() != slots.size()) continue;
                List<Member> want = new ArrayList<>(slots);
                want.sort(Comparator.comparingInt(m -> order.get(m.key)));
                if (want.equals(slots)) continue;
                StringBuilder out = new StringBuilder();
                int pos = 0;
                for (int k = 0; k < slots.size(); k++) {
                    out.append(text, pos, slots.get(k).begin).append(text, want.get(k).begin, want.get(k).end);
                    pos = slots.get(k).end;
                    if (slots.get(k) != want.get(k)) moved++;
                }
                out.append(text.substring(pos));
                text = out.toString();
            }
            if (moved > 0) {
                sourcesA.put(f, text);
                changedA.add(f);
                moves.add(new Move(f, moved));
                total += moved;
            }
        }
        return total;
    }

    private static List<Member> members(TypeDeclaration<?> t, String text, int[] lineStarts, Map<String, String> alias) {
        List<Member> out = new ArrayList<>();
        for (BodyDeclaration<?> m : t.getMembers()) {
            String key;
            boolean movable = true;
            if (m instanceof MethodDeclaration md) {
                key = alias.getOrDefault(md.getNameAsString(), md.getNameAsString()) + params(md);
            } else if (m instanceof ConstructorDeclaration cd) {
                key = "<init>" + params(cd);
            } else if (m instanceof TypeDeclaration<?> td) {
                key = "class " + td.getNameAsString();
            } else if (m instanceof FieldDeclaration fd && fd.getVariables().size() == 1) {
                VariableDeclarator v = fd.getVariable(0);
                key = alias.getOrDefault(v.getNameAsString(), v.getNameAsString());
                movable = v.getInitializer().isEmpty() ||
                        fd.hasModifier(Modifier.Keyword.STATIC) && fd.hasModifier(Modifier.Keyword.FINAL) && isLiteral(v.getInitializer().get());
            } else {
                continue;
            }
            Position b = m.getComment().map(c -> c.getBegin().get()).orElse(m.getBegin().orElse(null));
            Position e = m.getEnd().orElse(null);
            if (b == null || e == null) continue;
            int bo = offset(lineStarts, b), eo = offset(lineStarts, e) + 1;
            int ls = lineStarts[b.line - 1];
            int le = text.indexOf('\n', eo);
            le = le < 0 ? text.length() : le + 1;
            if (!text.substring(ls, bo).isBlank() || !text.substring(eo, le).isBlank()) continue; // not on lines of its own
            out.add(new Member(key, movable, ls, le));
        }
        return out;
    }

    private static String params(CallableDeclaration<?> c) {
        return c.getParameters().stream().map(p -> p.getType().asString() + (p.isVarArgs() ? "..." : "")).collect(Collectors.joining(",", "(", ")"));
    }

    private static boolean isLiteral(Expression e) {
        if (e instanceof UnaryExpr u && u.getOperator() == UnaryExpr.Operator.MINUS) e = u.getExpression();
        return e instanceof LiteralExpr;
    }

    // ---- utilities ----

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

    static int[] lineStarts(String s) {
        List<Integer> l = new ArrayList<>();
        l.add(0);
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') l.add(i + 1);
        return l.stream().mapToInt(Integer::intValue).toArray();
    }

    /** javaparser counts a tab as one column */
    static int offset(int[] lineStarts, Position p) {
        return lineStarts[p.line - 1] + p.column - 1;
    }

    /**
     * the matching blocks of two sequences like python's difflib.SequenceMatcher (no junk):
     * the longest matching block first, then the same on both sides of it.
     */
    static final class SequenceMatcher {
        private final int[] a, b;
        /** token of b → its indices in b, ascending */
        private final int[][] b2j;

        SequenceMatcher(List<String> as, List<String> bs) {
            Map<String, Integer> ids = new HashMap<>();
            this.a = as.stream().mapToInt(s -> ids.computeIfAbsent(s, k -> ids.size())).toArray();
            this.b = bs.stream().mapToInt(s -> ids.computeIfAbsent(s, k -> ids.size())).toArray();
            int[] counts = new int[ids.size()];
            for (int x : b) counts[x]++;
            b2j = new int[ids.size()][];
            for (int k = 0; k < counts.length; k++) b2j[k] = new int[counts[k]];
            int[] fill = new int[ids.size()];
            for (int j = 0; j < b.length; j++) b2j[b[j]][fill[b[j]]++] = j;
        }

        /** {i, j, size} */
        private int[] longest(int alo, int ahi, int blo, int bhi, int[] j2len, int[] newj2len, int[] touched, int[] newTouched) {
            int besti = alo, bestj = blo, best = 0;
            int nt = 0;
            for (int i = alo; i < ahi; i++) {
                int nnt = 0;
                for (int j : b2j[a[i]]) {
                    if (j < blo) continue;
                    if (j >= bhi) break;
                    int k = j2len[j] + 1; // j2len[j]: the length of the match ending at j - 1
                    newj2len[j + 1] = k;
                    newTouched[nnt++] = j + 1;
                    if (k > best) {
                        besti = i - k + 1;
                        bestj = j - k + 1;
                        best = k;
                    }
                }
                for (int t = 0; t < nt; t++) j2len[touched[t]] = 0;
                // swap
                int[] x = j2len; j2len = newj2len; newj2len = x;
                x = touched; touched = newTouched; newTouched = x;
                nt = nnt;
            }
            for (int t = 0; t < nt; t++) j2len[touched[t]] = 0;
            return new int[] {besti, bestj, best};
        }

        /** {i, j, size}, ascending, ending with {len(a), len(b), 0} */
        List<int[]> matchingBlocks() {
            int[] j2len = new int[b.length + 1], newj2len = new int[b.length + 1];
            int[] touched = new int[b.length + 1], newTouched = new int[b.length + 1];
            List<int[]> blocks = new ArrayList<>();
            Deque<int[]> queue = new ArrayDeque<>();
            queue.push(new int[] {0, a.length, 0, b.length});
            while (!queue.isEmpty()) {
                int[] q = queue.pop();
                int[] m = longest(q[0], q[1], q[2], q[3], j2len, newj2len, touched, newTouched);
                if (m[2] == 0) continue;
                blocks.add(m);
                if (q[0] < m[0] && q[2] < m[1]) queue.push(new int[] {q[0], m[0], q[2], m[1]});
                if (m[0] + m[2] < q[1] && m[1] + m[2] < q[3]) queue.push(new int[] {m[0] + m[2], q[1], m[1] + m[2], q[3]});
            }
            blocks.sort(Comparator.<int[]>comparingInt(x -> x[0]).thenComparingInt(x -> x[1]));
            List<int[]> merged = new ArrayList<>();
            for (int[] x : blocks) {
                int[] last = merged.isEmpty() ? null : merged.getLast();
                if (last != null && last[0] + last[2] == x[0] && last[1] + last[2] == x[1]) last[2] += x[2];
                else merged.add(x.clone());
            }
            merged.add(new int[] {a.length, b.length, 0});
            return merged;
        }

        /** {tag, i1, i2, j1, j2}, tag 0: equal, 1: replace, 2: delete, 3: insert */
        List<int[]> opcodes() {
            List<int[]> out = new ArrayList<>();
            int i = 0, j = 0;
            for (int[] m : matchingBlocks()) {
                int tag = i < m[0] && j < m[1] ? 1 : i < m[0] ? 2 : j < m[1] ? 3 : -1;
                if (tag >= 0) out.add(new int[] {tag, i, m[0], j, m[1]});
                i = m[0] + m[2];
                j = m[1] + m[2];
                if (m[2] > 0) out.add(new int[] {0, m[0], i, m[1], j});
            }
            return out;
        }
    }

    /**
     * usage: VersionAligner [--dry-run] [--prefer a|b] [--aliases file] [--names-only|--members-only] dirA dirB
     */
    public static void main(String[] args) throws IOException {
        boolean dry = false, names = true, members = true;
        Side prefer = Side.A;
        Path aliases = null;
        List<String> dirs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dry-run" -> dry = true;
                case "--prefer" -> prefer = Side.valueOf(args[++i].toUpperCase());
                case "--aliases" -> aliases = Path.of(args[++i]);
                case "--names-only" -> members = false;
                case "--members-only" -> names = false;
                default -> dirs.add(args[i]);
            }
        }
        if (dirs.size() != 2) {
            System.err.println("usage: VersionAligner [--dry-run] [--prefer a|b] [--aliases file] [--names-only|--members-only] dirA dirB");
            System.exit(1);
        }
        VersionAligner va = new VersionAligner(Path.of(dirs.get(0)), Path.of(dirs.get(1))).prefer(prefer);
        if (aliases != null) va.aliases(aliases);
        if (names) va.alignNames();
        if (members) va.alignMembers();
        if (names && members) va.alignNames();
        va.renames().forEach(System.out::println);
        va.moves().forEach(System.out::println);
        va.problems().forEach(p -> System.err.println("problem: " + p));
        System.err.println("renamed: " + va.renames().size() + ", moved: " + va.moves().stream().mapToInt(Move::members).sum());
        if (!dry) System.err.println("wrote " + va.write().size() + " files");
    }
}
