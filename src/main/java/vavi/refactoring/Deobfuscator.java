/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.refactoring;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


/**
 * Runs the refactorings in their order: splitting reused locals, naming parameters, naming locals.
 * Nothing is written until {@link #write()}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-10-30 nsano initial version <br>
 */
public class Deobfuscator {

    private final List<Path> roots;
    private final RenameOptions options;
    private NameMap nameMap = new NameMap();
    private boolean locals;
    private String localPattern;
    private boolean split;

    private JavaProject project;
    private ParameterRenamer parameterRenamer;
    private LocalVariableRenamer localRenamer;
    private LocalSplitter splitter;
    private int obfuscatedParameters;

    public Deobfuscator(List<Path> roots, RenameOptions options) {
        this.roots = roots;
        this.options = options;
    }

    public Deobfuscator nameMap(NameMap nameMap) {
        this.nameMap = nameMap;
        return this;
    }

    /** also names locals */
    public Deobfuscator locals(boolean locals) {
        this.locals = locals;
        return this;
    }

    /** @param regex locals to rename, null for JD style names */
    public Deobfuscator localPattern(String regex) {
        this.localPattern = regex;
        return this;
    }

    /** splits locals reused for different values first, implies {@link #locals(boolean)} */
    public Deobfuscator split(boolean split) {
        this.split = split;
        if (split) locals = true;
        return this;
    }

    private LocalVariableRenamer newLocalRenamer(ParameterRenamer parameters) {
        LocalVariableRenamer l = new LocalVariableRenamer(parameters);
        if (localPattern != null) l.pattern(localPattern);
        return l;
    }

    /** infers all names */
    public Deobfuscator run() throws IOException {
        project = new JavaProject(roots);
        if (split) {
            splitter = new LocalSplitter(newLocalRenamer(new ParameterRenamer(project, options)));
            project = splitter.split();
        }
        parameterRenamer = new ParameterRenamer(project, options);
        obfuscatedParameters = parameterRenamer.countObfuscated(true);
        parameterRenamer.apply(nameMap);
        parameterRenamer.infer();
        if (locals) {
            localRenamer = newLocalRenamer(parameterRenamer);
            localRenamer.apply(nameMap);
            localRenamer.infer();
        }
        return this;
    }

    public JavaProject project() {
        return project;
    }

    public ParameterRenamer parameters() {
        return parameterRenamer;
    }

    /** null unless locals are named */
    public LocalVariableRenamer locals() {
        return localRenamer;
    }

    /** null unless locals are split */
    public LocalSplitter splitter() {
        return splitter;
    }

    /** obfuscated parameters in the sources */
    public int obfuscatedParameters() {
        return obfuscatedParameters;
    }

    /** warnings of the name map */
    public List<String> warnings() {
        List<String> w = new ArrayList<>(parameterRenamer.warnings());
        if (localRenamer != null) w.addAll(localRenamer.warnings());
        return w;
    }

    /**
     * writes the splits and the renames.
     * @return the changed files
     */
    public List<Path> write() throws IOException {
        return localRenamer != null ? localRenamer.write() : parameterRenamer.write();
    }
}
