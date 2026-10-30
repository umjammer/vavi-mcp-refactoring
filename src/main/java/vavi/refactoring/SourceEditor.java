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
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SimpleName;
import vavi.refactoring.JavaProject.Method;
import vavi.refactoring.JavaProject.Unit;


/**
 * Writes parameter renames into the sources.
 * <p>
 * Edits are made at the positions javaparser reports in the original text, so the formatting
 * and comments are kept as they are and line numbers never change.
 * The declaration, every reference in the body (except where a nested lambda or method
 * declares the same name) and the javadoc {@code @param} tag are renamed.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-10-30 nsano initial version <br>
 */
class SourceEditor {

    private record Edit(int begin, int end, String text) {}

    /** receives renames of identifiers */
    interface EditSink {
        void rename(SimpleName name, String to);
    }

    private final JavaProject project;
    /** nullable */
    private final LocalVariableRenamer locals;

    SourceEditor(JavaProject project) {
        this(project, null);
    }

    SourceEditor(JavaProject project, LocalVariableRenamer locals) {
        this.project = project;
        this.locals = locals;
    }

    /** @return the changed files */
    List<Path> write() throws IOException {
        List<Path> changed = new ArrayList<>();
        for (Unit u : project.units) {
            String result = edit(u);
            if (result != null) {
                Files.writeString(u.path, result);
                changed.add(u.path);
            }
        }
        return changed;
    }

    /** @return the edited source, null when nothing changes */
    String edit(Unit u) {
        List<Edit> edits = new ArrayList<>();
        int[] lineStarts = lineStarts(u.source);
        for (Method m : project.methods) {
            if (m.unit != u) continue;
            for (int i = 0; i < m.arity(); i++) {
                String from = m.originalNames[i], to = m.names[i];
                if (from.equals(to)) continue;
                edits.add(edit(lineStarts, m.params.get(i).getName(), to));
                for (NameExpr ne : m.decl.findAll(NameExpr.class)) {
                    if (ne.getNameAsString().equals(from) && !isShadowed(ne, from, m.decl)) edits.add(edit(lineStarts, ne.getName(), to));
                }
                m.decl.getComment().ifPresent(c -> {
                    int b = offset(lineStarts, c.getBegin().get()), e = offset(lineStarts, c.getEnd().get()) + 1;
                    Matcher matcher = Pattern.compile("@param\\s+(" + Pattern.quote(from) + ")\\b").matcher(u.source.substring(b, e));
                    while (matcher.find()) edits.add(new Edit(b + matcher.start(1), b + matcher.end(1), to));
                });
            }
        }
        if (locals != null) locals.edits(u, (name, to) -> edits.add(edit(lineStarts, name, to)));
        if (edits.isEmpty()) return null;
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

    /**
     * true when a lambda or a method (of a nested class) between the reference and the owner declares the name again,
     * the reference is not ours then.
     */
    static boolean isShadowed(Node n, String name, CallableDeclaration<?> owner) {
        for (Node x = n.getParentNode().orElse(null); x != null && x != owner; x = x.getParentNode().orElse(null)) {
            if (x instanceof CallableDeclaration<?> cd && (cd.getParameters().stream().anyMatch(q -> q.getNameAsString().equals(name)) ||
                    cd.findAll(VariableDeclarator.class).stream().anyMatch(v -> v.getNameAsString().equals(name)))) return true;
            if (x instanceof LambdaExpr le && le.getParameters().stream().anyMatch(q -> q.getNameAsString().equals(name))) return true;
        }
        return false;
    }

    private static Edit edit(int[] lineStarts, SimpleName n, String to) {
        int b = offset(lineStarts, n.getBegin().get());
        return new Edit(b, b + n.getIdentifier().length(), to);
    }

    private static int[] lineStarts(String s) {
        List<Integer> l = new ArrayList<>();
        l.add(0);
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') l.add(i + 1);
        return l.stream().mapToInt(Integer::intValue).toArray();
    }

    /** javaparser counts a tab as one column */
    private static int offset(int[] lineStarts, Position p) {
        return lineStarts[p.line - 1] + p.column - 1;
    }
}
