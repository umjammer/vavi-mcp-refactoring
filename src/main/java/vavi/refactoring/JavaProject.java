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
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import static java.lang.System.getLogger;


/**
 * A set of java sources parsed with javaparser, indexed by method and type hierarchy.
 * <p>
 * Types are resolved by simple name only (no symbol solving), that is enough for
 * propagating names between methods and keeps loading a large tree fast.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-10-30 nsano initial version <br>
 */
public class JavaProject {

    private static final Logger logger = getLogger(JavaProject.class.getName());

    /** a parsed source file */
    public static class Unit {
        final Path path;
        final String source;
        final CompilationUnit cu;
        /** the source differs from the file, it has to be written */
        final boolean modified;

        Unit(Path path, String source, CompilationUnit cu, boolean modified) {
            this.path = path;
            this.source = source;
            this.cu = cu;
            this.modified = modified;
        }

        public Path path() {
            return path;
        }

        public String source() {
            return source;
        }
    }

    /** a method or a constructor */
    public static class Method {
        final Unit unit;
        final CallableDeclaration<?> decl;
        final TypeDeclaration<?> owner;
        /** method name or "&lt;init&gt;" */
        final String name;
        final String ownerName;
        final List<Parameter> params;
        /** current (proposed) names */
        final String[] names;
        /** names in the source */
        final String[] originalNames;

        Method(Unit unit, CallableDeclaration<?> decl) {
            this.unit = unit;
            this.decl = decl;
            Node p = decl.getParentNode().orElse(null);
            this.owner = p instanceof TypeDeclaration<?> td ? td : null;
            this.ownerName = owner != null ? owner.getNameAsString() : "?";
            this.name = decl instanceof ConstructorDeclaration ? "<init>" : decl.getNameAsString();
            this.params = decl.getParameters();
            this.names = params.stream().map(Parameter::getNameAsString).toArray(String[]::new);
            this.originalNames = names.clone();
        }

        /** "name/arity", constructors are "&lt;init&gt;:Owner/arity" */
        String key() {
            return (isConstructor() ? "<init>:" + ownerName : name) + "/" + params.size();
        }

        public boolean isConstructor() {
            return name.equals("<init>");
        }

        public Unit unit() {
            return unit;
        }

        public String ownerName() {
            return ownerName;
        }

        public String name() {
            return name;
        }

        public int line() {
            return decl.getBegin().map(b -> b.line).orElse(0);
        }

        public int arity() {
            return params.size();
        }

        public String parameterType(int i) {
            return params.get(i).getType().asString() + (params.get(i).isVarArgs() ? "..." : "");
        }

        /** comma separated parameter types */
        public String signature() {
            return params.stream().map(p -> p.getType().asString()).collect(Collectors.joining(","));
        }

        public String name(int i) {
            return names[i];
        }

        public String originalName(int i) {
            return originalNames[i];
        }

        /** the declaration as written in the source, without its javadoc */
        public String source() {
            if (decl.getBegin().isEmpty() || decl.getEnd().isEmpty()) return decl.toString();
            String[] lines = unit.source.split("\n", -1);
            int bl = decl.getBegin().get().line, el = decl.getEnd().get().line;
            StringBuilder sb = new StringBuilder();
            for (int l = bl; l <= el && l <= lines.length; l++) {
                String s = lines[l - 1];
                if (l == el) s = s.substring(0, Math.min(s.length(), decl.getEnd().get().column));
                if (l == bl) s = s.substring(Math.min(s.length(), decl.getBegin().get().column - 1));
                sb.append(s).append('\n');
            }
            return sb.toString().stripTrailing();
        }

        boolean isField(String n) {
            if (owner == null) return false;
            for (FieldDeclaration f : owner.getFields())
                if (f.getVariables().stream().anyMatch(v -> v.getNameAsString().equals(n))) return true;
            return false;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(ownerName).append('.').append(name).append('(');
            for (int i = 0; i < names.length; i++) sb.append(i > 0 ? ", " : "").append(parameterType(i)).append(' ').append(names[i]);
            return sb.append(')').toString();
        }
    }

    final List<Path> roots;
    final List<Unit> units = new ArrayList<>();
    final List<Method> methods = new ArrayList<>();
    /** see {@link Method#key()} */
    final Map<String, List<Method>> byKey = new HashMap<>();
    /** simple type name -> direct super types */
    final Map<String, Set<String>> supers = new HashMap<>();
    final Map<String, Set<String>> subs = new HashMap<>();
    /** simple type name -> declarations */
    final Map<String, List<TypeDeclaration<?>>> types = new HashMap<>();
    final List<String> problems = new ArrayList<>();

    /**
     * @param roots source directories or single java files
     */
    public JavaProject(List<Path> roots) throws IOException {
        this(roots, Map.of());
    }

    /**
     * @param edited sources to use instead of the files, they are marked as modified
     */
    JavaProject(List<Path> roots, Map<Path, String> edited) throws IOException {
        this.roots = roots;
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25));
        for (Path root : roots) {
            List<Path> files;
            try (Stream<Path> s = Files.walk(root)) {
                files = s.filter(x -> x.toString().endsWith(".java") && Files.isRegularFile(x)).sorted().toList();
            }
            for (Path p : files) {
                boolean modified = edited.containsKey(p);
                String source = modified ? edited.get(p) : Files.readString(p);
                ParseResult<CompilationUnit> r = parser.parse(source);
                if (!r.isSuccessful() || r.getResult().isEmpty()) {
                    problems.add(p + ": " + r.getProblems());
                    logger.log(Level.WARNING, "parse failed: " + p + " " + r.getProblems());
                    continue;
                }
                units.add(new Unit(p, source, r.getResult().get(), modified));
            }
        }
        for (Unit u : units) {
            for (TypeDeclaration<?> t : u.cu.findAll(TypeDeclaration.class)) {
                String tn = t.getNameAsString();
                types.computeIfAbsent(tn, k -> new ArrayList<>()).add(t);
                Set<String> ss = supers.computeIfAbsent(tn, k -> new HashSet<>());
                if (t instanceof ClassOrInterfaceDeclaration c) {
                    c.getExtendedTypes().forEach(x -> ss.add(x.getNameAsString()));
                    c.getImplementedTypes().forEach(x -> ss.add(x.getNameAsString()));
                } else if (t instanceof EnumDeclaration e) {
                    e.getImplementedTypes().forEach(x -> ss.add(x.getNameAsString()));
                }
                for (String s : ss) subs.computeIfAbsent(s, k -> new HashSet<>()).add(tn);
            }
            for (CallableDeclaration<?> d : u.cu.findAll(CallableDeclaration.class)) {
                Method m = new Method(u, d);
                methods.add(m);
                byKey.computeIfAbsent(m.key(), k -> new ArrayList<>()).add(m);
            }
        }
        logger.log(Level.DEBUG, "units: " + units.size() + ", methods: " + methods.size());
    }

    /** a project with some sources edited (in memory) */
    JavaProject reparse(Map<Path, String> edited) throws IOException {
        Map<Path, String> all = new HashMap<>();
        for (Unit u : units) if (u.modified) all.put(u.path, u.source);
        all.putAll(edited);
        return new JavaProject(roots, all);
    }

    public List<Unit> units() {
        return Collections.unmodifiableList(units);
    }

    public List<Method> methods() {
        return Collections.unmodifiableList(methods);
    }

    /** parse errors */
    public List<String> problems() {
        return Collections.unmodifiableList(problems);
    }

    List<Method> byKey(String key) {
        return byKey.getOrDefault(key, List.of());
    }

    Set<String> supers(String type) {
        return supers.getOrDefault(type, Set.of());
    }

    /**
     * the declared type of a field of the type or its super types in the project.
     * @return null when not found, or when types of the name disagree
     */
    String fieldType(String type, String field) {
        Set<String> seen = new HashSet<>();
        Deque<String> q = new ArrayDeque<>(List.of(type));
        while (!q.isEmpty()) {
            String t = q.pop();
            if (!seen.add(t)) continue;
            Set<String> found = new HashSet<>();
            for (TypeDeclaration<?> td : types.getOrDefault(t, List.of()))
                td.getFieldByName(field).ifPresent(f -> f.getVariables().stream().filter(v -> v.getNameAsString().equals(field)).forEach(v -> found.add(v.getType().asString())));
            if (found.size() == 1) return found.iterator().next();
            if (found.size() > 1) return null;
            q.addAll(supers(t));
        }
        return null;
    }

    // ---- types by the symbol solver ----

    private boolean solverReady;

    /** sets the symbol solver up for all units: the jdk by reflection and the source roots of the units */
    private void prepareSolver() {
        solverReady = true;
        ParserConfiguration conf = new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25);
        CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver());
        Set<Path> sourceRoots = new LinkedHashSet<>();
        for (Unit u : units) {
            Path dir = u.path.toAbsolutePath().normalize().getParent();
            String pkg = u.cu.getPackageDeclaration().map(d -> d.getNameAsString()).orElse("");
            Path root = dir;
            boolean ok = true;
            if (!pkg.isEmpty()) {
                String[] parts = pkg.split("\\.");
                for (int i = parts.length - 1; i >= 0 && ok; i--) {
                    if (root == null || !root.getFileName().toString().equals(parts[i])) ok = false;
                    else root = root.getParent();
                }
            }
            if (ok && root != null) sourceRoots.add(root);
        }
        sourceRoots.forEach(r -> solver.add(new JavaParserTypeSolver(r, conf)));
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(solver);
        units.forEach(u -> symbolSolver.inject(u.cu));
        logger.log(Level.DEBUG, "symbol solver: " + sourceRoots);
    }

    /**
     * the static type of an expression as it can be written in its file.
     * @return null when it can't be resolved or written (a type variable, a wildcard, an anonymous class)
     */
    String typeText(Expression e) {
        if (!solverReady) prepareSolver();
        CompilationUnit cu = e.findCompilationUnit().orElse(null);
        if (cu == null) return null;
        try {
            return typeText(e.calculateResolvedType(), cu);
        } catch (Throwable t) { // unsolved symbols, unsupported syntax ...
            logger.log(Level.TRACE, "unresolved: " + e + ": " + t);
            return null;
        }
    }

    private static String typeText(ResolvedType t, CompilationUnit cu) {
        if (t.isPrimitive()) return t.describe();
        if (t.isArray()) {
            String c = typeText(t.asArrayType().getComponentType(), cu);
            return c != null ? c + "[]" : null;
        }
        if (!t.isReferenceType()) return null;
        ResolvedReferenceType r = t.asReferenceType();
        var decl = r.getTypeDeclaration().orElse(null);
        if (decl == null || decl.isAnonymousClass()) return null;
        String qualified = r.getQualifiedName();
        String pkg = decl.getPackageName();
        String inPackage = pkg.isEmpty() ? qualified : qualified.substring(pkg.length() + 1);
        String top = inPackage.contains(".") ? inPackage.substring(0, inPackage.indexOf('.')) : inPackage;
        String name = qualified;
        // a single type import of another type with the name hides the package and java.lang
        boolean hidden = cu.getImports().stream().anyMatch(i -> !i.isStatic() && !i.isAsterisk() &&
                i.getName().getIdentifier().equals(top) && !i.getNameAsString().equals(pkg.isEmpty() ? top : pkg + "." + top));
        boolean imported = cu.getImports().stream().anyMatch(i -> !i.isStatic() &&
                (!i.isAsterisk() && i.getNameAsString().equals(pkg.isEmpty() ? top : pkg + "." + top) || i.isAsterisk() && i.getNameAsString().equals(pkg)));
        String cuPackage = cu.getPackageDeclaration().map(d -> d.getNameAsString()).orElse("");
        if (imported || !hidden && (pkg.equals(cuPackage) || pkg.equals("java.lang"))) name = inPackage;
        if (r.isRawType()) return name;
        List<ResolvedType> args = r.typeParametersValues();
        if (args.isEmpty()) return name;
        StringBuilder sb = new StringBuilder(name).append('<');
        for (int i = 0; i < args.size(); i++) {
            String a = typeText(args.get(i), cu);
            if (a == null) return null;
            sb.append(i > 0 ? ", " : "").append(a);
        }
        return sb.append('>').toString();
    }

    /** all super and sub types of the type, excluding itself */
    Set<String> related(String type) {
        Set<String> out = new HashSet<>();
        Deque<String> q = new ArrayDeque<>(List.of(type));
        while (!q.isEmpty()) {
            for (String s : supers.getOrDefault(q.pop(), Set.of())) if (out.add(s)) q.add(s);
        }
        q.add(type);
        while (!q.isEmpty()) {
            for (String s : subs.getOrDefault(q.pop(), Set.of())) if (out.add(s)) q.add(s);
        }
        out.remove(type);
        return out;
    }

    /** a path relative to the first root that contains it, for display */
    public String display(Path p) {
        for (Path r : roots) {
            if (p.startsWith(r) && !p.equals(r)) return r.relativize(p).toString();
        }
        return p.toString();
    }
}
