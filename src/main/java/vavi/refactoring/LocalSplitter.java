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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import vavi.refactoring.JavaProject.Unit;
import vavi.refactoring.LocalVariableRenamer.Local;

import static java.lang.System.getLogger;


/**
 * Splits a decompiled local that is reused for different values ({@code Object localObject} holding a target first and a node later)
 * into one variable per value, and gives an {@code Object} piece the type of its values.
 * <pre>
 * Object localObject1;                              Object localObject1;
 * localObject1 = getTarget();                       localObject1 = getTarget();
 * ...                                         →     ...
 * localObject1 = this.list;                         TickleNode localObject3 = this.list;     // a TickleNode field
 * ((TickleNode) localObject1).next = null;          localObject3.next = null;
 * </pre>
 * A variable is split at an assignment statement {@code v = expr;} directly in the block that declares it
 * (and whose expression doesn't read v): nothing assigned before it can be read after it, so the pieces are independent.
 * The case groups of a switch statement count as one block: a jump to a later case leaves the variable unassigned.
 * A variable declared in a for loop is not split (the condition and the update carry its value around), it can be retyped.
 * <p>
 * A piece declared {@code Object} gets the type {@code Foo} only when all its values are {@code (Foo) x} casts, {@code new Foo(...)},
 * variables or fields declared {@code Foo}, or calls of methods returning {@code Foo} (by the symbol solver),
 * and it is used only cast to Foo, compared, tested by instanceof, assigned or returned, so no method overload changes.
 * The casts become identities and are removed.
 * <p>
 * Only obfuscated locals ({@link LocalVariableRenamer#isTarget}) are touched, the pieces get names like the original
 * so that {@link LocalVariableRenamer} names them afterwards.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-10-30 nsano initial version <br>
 */
public class LocalSplitter {

    private static final Logger logger = getLogger(LocalSplitter.class.getName());

    /** a change for the report */
    public record Split(Path file, int line, String method, String variable, List<String> pieces) {

        @Override
        public String toString() {
            return method + " " + variable + (pieces.size() == 1 ? " joined with its assignment -> " : " -> ") + String.join(", ", pieces);
        }
    }

    private record Edit(int begin, int end, String text) {}

    /** a piece of a variable: from its assignment (or the declaration) to the next */
    private static class Piece {
        /** the assignment statement starting the piece, null for the declaration */
        ExpressionStmt start;
        final List<NameExpr> refs = new ArrayList<>();
        String name;
        /** the type to declare, null to keep the declared one (the first piece) */
        String type;
    }

    private final JavaProject project;
    private final LocalVariableRenamer locals;
    /** a retyped declaration gets "var" when its type is longer than this, 0 for never */
    private int varOver;
    private final List<Split> splits = new ArrayList<>();
    private int retyped;

    /**
     * @param locals finds the locals and their references
     */
    public LocalSplitter(LocalVariableRenamer locals) {
        this.locals = locals;
        this.project = locals.project();
    }

    /**
     * declares retyped pieces with "var" when the type is longer than the length and the initializer has exactly that type
     * @param length 0 for never
     */
    public LocalSplitter varOver(int length) {
        this.varOver = length;
        return this;
    }

    public List<Split> splits() {
        return splits;
    }

    /** number of pieces that got a narrower type */
    public int retyped() {
        return retyped;
    }

    /**
     * splits the locals.
     * @return the project parsed again from the split sources, nothing is written yet
     */
    public JavaProject split() throws IOException {
        Map<Unit, List<Edit>> edits = new IdentityHashMap<>();
        Map<Object, Set<String>> taken = new IdentityHashMap<>(); // outermost method -> names allocated
        for (Local l : locals.locals()) {
            List<Edit> e = split(l, taken.computeIfAbsent(l.outer, k -> new HashSet<>()));
            if (e != null) edits.computeIfAbsent(l.method.unit, k -> new ArrayList<>()).addAll(e);
        }
        Map<Path, String> sources = new HashMap<>();
        edits.forEach((u, list) -> sources.put(u.path, apply(u, list)));
        logger.log(Level.DEBUG, "split " + splits.size() + " locals in " + sources.size() + " files");
        return sources.isEmpty() ? project : project.reparse(sources);
    }

    /** @return edits, null when the local is not split or retyped */
    private List<Edit> split(Local l, Set<String> taken) {
        if (l.ambiguous || !(l.declaration instanceof VariableDeclarator v) ||
                !(v.getParentNode().orElse(null) instanceof VariableDeclarationExpr vde) || vde.getVariables().size() != 1) return null;
        Node holder = vde.getParentNode().orElse(null);
        // the statements the variable lives in, in the order they run when nothing jumps
        List<Statement> statements;
        ExpressionStmt declStmt = null;
        if (holder instanceof ExpressionStmt es && l.scope instanceof BlockStmt block) {
            statements = block.getStatements();
            declStmt = es;
        } else if (holder instanceof ExpressionStmt es && l.scope instanceof SwitchStmt sw &&
                sw.getEntries().stream().allMatch(e -> e.getType() == SwitchEntry.Type.STATEMENT_GROUP)) {
            // a jump to a later case leaves the variable unassigned, so a piece can't be read past its start there either
            statements = new ArrayList<>();
            sw.getEntries().forEach(e -> statements.addAll(e.getStatements()));
            declStmt = es;
        } else if (holder instanceof ForStmt fs && fs.getInitialization().stream().anyMatch(i -> i == vde)) {
            // the condition and the update carry the value around, it can only get another type
            statements = null;
        } else {
            return null;
        }
        int declIndex = -1;
        if (statements != null) {
            for (int i = 0; i < statements.size(); i++) if (statements.get(i) == declStmt) declIndex = i;
            if (declIndex < 0) return null;
        }
        // identities, javaparser nodes are equal when they look the same
        Set<Node> refs = Collections.newSetFromMap(new IdentityHashMap<>());
        refs.addAll(l.refs);

        // pieces start at "v = expr;" statements in the block
        List<Piece> pieces = new ArrayList<>();
        Piece first = new Piece();
        first.name = l.originalName;
        pieces.add(first);
        Map<Statement, Piece> starts = new IdentityHashMap<>();
        for (int i = declIndex + 1; statements != null && i < statements.size(); i++) {
            if (statements.get(i) instanceof ExpressionStmt es && es.getExpression() instanceof AssignExpr a &&
                    a.getOperator() == AssignExpr.Operator.ASSIGN && refs.contains(a.getTarget()) &&
                    a.getValue().findAll(NameExpr.class).stream().noneMatch(refs::contains)) {
                Piece p = new Piece();
                p.start = es;
                starts.put(es, p);
                pieces.add(p);
            }
        }
        // references go to the piece of their statement in the block
        Piece current = first;
        Map<NameExpr, Piece> owner = new IdentityHashMap<>();
        if (statements == null) {
            first.refs.addAll(l.refs);
            l.refs.forEach(r -> owner.put(r, first));
        }
        for (int i = declIndex + 1; statements != null && i < statements.size(); i++) {
            Piece p = starts.get(statements.get(i));
            if (p != null) current = p;
            for (NameExpr r : statements.get(i).findAll(NameExpr.class)) {
                if (refs.contains(r)) {
                    current.refs.add(r);
                    owner.put(r, current);
                }
            }
        }
        if (owner.size() != l.refs.size()) return null; // a reference outside the statements, e.g. in the declaration

        boolean objectType = l.type.asString().equals("Object");
        // the first piece is useless when nothing reads it
        boolean dropDeclaration = pieces.size() > 1 && first.refs.isEmpty() &&
                v.getInitializer().map(i -> i instanceof LiteralExpr || i instanceof NameExpr).orElse(true);
        for (Piece p : pieces) {
            if (objectType) p.type = narrowType(l, p, v);
            if (p == first && p.type == null) continue;
            if (p != first) {
                // a name like the original one, so that it is renamed later
                if (p == pieces.get(1) && dropDeclaration) {
                    p.name = l.originalName;
                } else {
                    String base = l.originalName.replaceAll("\\d+$", "");
                    for (int n = 2; ; n++) {
                        if (!taken.contains(base + n) && !usedIn(l, base + n)) {
                            p.name = base + n;
                            break;
                        }
                    }
                }
                taken.add(p.name);
                if (p.type == null) p.type = l.type.asString();
            }
        }
        if (pieces.size() == 1 && first.type == null) return null;

        int[] lineStarts = lineStarts(l.method.unit.source);
        List<Edit> edits = new ArrayList<>();
        for (Piece p : pieces) {
            boolean retype = objectType && p.type != null && !p.type.equals("Object");
            if (p == first) {
                if (dropDeclaration) {
                    edits.add(deleteStatement(l.method.unit.source, lineStarts, declStmt));
                } else if (retype) {
                    edits.add(replace(lineStarts, v.getType(), declaredType(l, p.type, v.getInitializer().orElse(null))));
                }
            } else {
                AssignExpr a = (AssignExpr) p.start.getExpression();
                Position b = a.getTarget().getBegin().get();
                String type = retype ? declaredType(l, p.type, a.getValue()) : p.type;
                edits.add(new Edit(offset(lineStarts, b), offset(lineStarts, a.getTarget().getEnd().get()) + 1, type + " " + p.name));
            }
            for (NameExpr r : p.refs) {
                if (p.start != null && r == ((AssignExpr) p.start.getExpression()).getTarget()) continue;
                Node outer = r;
                if (retype && r.getParentNode().orElse(null) instanceof CastExpr c && c.getType().asString().equals(p.type)) {
                    outer = c.getParentNode().orElse(null) instanceof EnclosedExpr e ? e : c; // an identity cast now
                }
                if (outer != r || !p.name.equals(l.originalName)) edits.add(replace(lineStarts, outer, p.name));
            }
            if (retype) retyped++;
        }
        List<String> names = new ArrayList<>();
        for (Piece p : pieces) {
            if (p == first && dropDeclaration) continue;
            names.add((p.type != null ? p.type : l.type.asString()) + " " + p.name);
        }
        splits.add(new Split(l.method.unit.path, l.line(), l.method.ownerName + "." + l.method.name, l.type.asString() + " " + l.originalName, names));
        return edits;
    }

    private static boolean usedIn(Local l, String name) {
        return l.outer.decl.findAll(SimpleName.class).stream().anyMatch(s -> s.getIdentifier().equals(name));
    }

    /**
     * the type all values of the piece have, when every use keeps its meaning with that type.
     * @return null when it stays Object
     */
    private String narrowType(Local l, Piece p, VariableDeclarator v) {
        List<Expression> values = new ArrayList<>();
        if (p.start != null) values.add(((AssignExpr) p.start.getExpression()).getValue());
        else if (v.getInitializer().isPresent()) values.add(v.getInitializer().get());
        String type = null;
        for (NameExpr r : p.refs) {
            Node parent = r.getParentNode().orElse(null);
            if (parent instanceof AssignExpr a && a.getTarget() == r) {
                if (a.getOperator() != AssignExpr.Operator.ASSIGN) return null;
                values.add(a.getValue());
            } else if (parent instanceof CastExpr c) {
                if (type == null) type = c.getType().asString();
                else if (!type.equals(c.getType().asString())) return null;
            } else if (!(parent instanceof BinaryExpr be && (be.getOperator() == BinaryExpr.Operator.EQUALS || be.getOperator() == BinaryExpr.Operator.NOT_EQUALS)) &&
                    !(parent instanceof InstanceOfExpr) &&
                    !(parent instanceof AssignExpr a2 && a2.getValue() == r) &&
                    !(parent instanceof VariableDeclarator) &&
                    !(parent instanceof ReturnStmt)) {
                return null; // e.g. an argument, a method could be chosen by the type
            }
        }
        String valueType = null;
        for (Expression e : values) {
            if (e instanceof NullLiteralExpr) continue;
            String t = valueType(l, e);
            if (t == null) return null;
            if (valueType == null) valueType = t;
            else if (!valueType.equals(t)) return null;
        }
        if (valueType == null || valueType.equals("Object") || type != null && !type.equals(valueType)) return null;
        return valueType;
    }

    /**
     * the static type of a value: a cast, a {@code new}, {@code this.f}, a local, a parameter or a field,
     * or a field of one of them ({@code ((Node) x).next}).
     * @return null when it can't be told
     */
    private String valueType(Local l, Expression e) {
        if (e instanceof EnclosedExpr ee) return valueType(l, ee.getInner());
        if (e instanceof CastExpr c) return c.getType().asString();
        if (e instanceof ObjectCreationExpr oc) return isDiamond(oc.getType()) ? null : oc.getType().asString();
        if (e instanceof FieldAccessExpr fa && !(fa.getScope() instanceof ThisExpr)) {
            String scope = valueType(l, fa.getScope());
            return scope == null || scope.equals("Object") ? null : project.fieldType(scope, fa.getNameAsString());
        }
        String t = fieldType(l, e);
        // method return values and anything else, by the symbol solver
        return t != null ? t : project.typeText(e);
    }

    /** the type to write for a retyped declaration, "var" when it is long and the initializer has exactly the type */
    private String declaredType(Local l, String type, Expression initializer) {
        if (varOver <= 0 || type.length() <= varOver || initializer == null || initializer instanceof NullLiteralExpr) return type;
        if (initializer instanceof ObjectCreationExpr oc && oc.getAnonymousClassBody().isPresent()) return type; // var would be the anonymous class
        return type.equals(valueType(l, initializer)) ? "var" : type;
    }

    /** the declared type of {@code this.f}, or of an unqualified {@code f} that is a local, a parameter or a field, null otherwise */
    private String fieldType(Local l, Expression e) {
        String name;
        if (e instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr) name = fa.getNameAsString();
        else if (e instanceof NameExpr ne) name = ne.getNameAsString();
        else return null;
        if (e instanceof NameExpr) {
            // a local or a parameter of the name wins, it has to be the only one
            List<Type> types = new ArrayList<>();
            l.outer.decl.findAll(VariableDeclarator.class).stream().filter(x -> x.getNameAsString().equals(name)).forEach(x -> types.add(x.getType()));
            l.outer.decl.findAll(Parameter.class).stream().filter(x -> x.getNameAsString().equals(name)).forEach(x -> types.add(x.getType()));
            if (types.size() > 1) return null;
            if (types.size() == 1) return types.getFirst().isVarType() ? null : types.getFirst().asString();
        }
        return project.fieldType(l.method.ownerName, name);
    }

    private static boolean isDiamond(ClassOrInterfaceType t) {
        return t.getTypeArguments().map(List::isEmpty).orElse(false);
    }

    // ---- text ----

    private static Edit replace(int[] lineStarts, Node n, String text) {
        return new Edit(offset(lineStarts, n.getBegin().get()), offset(lineStarts, n.getEnd().get()) + 1, text);
    }

    private static Edit replace(int[] lineStarts, Type t, String text) {
        return new Edit(offset(lineStarts, t.getBegin().get()), offset(lineStarts, t.getEnd().get()) + 1, text);
    }

    /** removes the statement, with its line when nothing else is on it */
    private static Edit deleteStatement(String source, int[] lineStarts, Statement s) {
        int b = offset(lineStarts, s.getBegin().get()), e = offset(lineStarts, s.getEnd().get()) + 1;
        int lb = b;
        while (lb > 0 && (source.charAt(lb - 1) == ' ' || source.charAt(lb - 1) == '\t')) lb--;
        int le = e;
        while (le < source.length() && (source.charAt(le) == ' ' || source.charAt(le) == '\t')) le++;
        if ((lb == 0 || source.charAt(lb - 1) == '\n') && le < source.length() && source.charAt(le) == '\n') return new Edit(lb, le + 1, "");
        return new Edit(b, e, "");
    }

    private static String apply(Unit u, List<Edit> edits) {
        edits.sort(Comparator.comparingInt(Edit::begin).reversed());
        StringBuilder sb = new StringBuilder(u.source);
        int last = Integer.MAX_VALUE;
        for (Edit e : edits) {
            if (e.end > last) throw new IllegalStateException("overlapping edits in " + u.path + " at " + e.begin);
            sb.replace(e.begin, e.end, e.text);
            last = e.begin;
        }
        return sb.toString();
    }

    private static int[] lineStarts(String s) {
        List<Integer> l = new ArrayList<>();
        l.add(0);
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') l.add(i + 1);
        return l.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int offset(int[] lineStarts, Position p) {
        return lineStarts[p.line - 1] + p.column - 1;
    }
}
