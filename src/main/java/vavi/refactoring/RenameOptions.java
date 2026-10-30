/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.refactoring;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.github.javaparser.ast.type.Type;


/**
 * Options for {@link ParameterRenamer}.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-10-30 nsano initial version <br>
 */
public class RenameOptions {

    /** JD-GUI/JAD style names: paramInt, paramArrayOfByte1, paramString2 ... */
    public static final String DEFAULT_PATTERN = "param[A-Z0-9_]\\w*";

    /** which parameter names are treated as obfuscated */
    Pattern obfuscated = Pattern.compile(DEFAULT_PATTERN);

    /**
     * JD-GUI/JAD names spell the declared type, "param" + type + digits ({@code int paramInt2}, {@code byte[] paramArrayOfByte}),
     * only those are renamed so that real names like {@code param1} or {@code paramBlock} are kept.
     * on for the default pattern, off when a pattern is given.
     */
    boolean typeSpelled = true;

    /** names known to be wrong in the sources, they are never copied to other methods */
    final Set<String> poisonNames = new HashSet<>();

    /** simple type name -> parameter name for the type fallback, e.g. "QDRect" -> "rect" */
    final Map<String, String> typeNames = new HashMap<>(Map.of("Class", "clazz"));

    /**
     * methods whose single argument stands for the argument itself when propagating names,
     * e.g. "ID" for handle wrappers like {@code QTObject.ID(movie)}
     */
    final Set<String> unwrapCalls = new HashSet<>();

    /** name reference type parameters after their type when nothing better is found */
    boolean typeFallback = true;

    /** sets the names to rename, this turns off {@link #typeSpelled(boolean)} */
    public RenameOptions obfuscatedPattern(String regex) {
        this.obfuscated = Pattern.compile(regex);
        this.typeSpelled = false;
        return this;
    }

    public RenameOptions typeSpelled(boolean typeSpelled) {
        this.typeSpelled = typeSpelled;
        return this;
    }

    public RenameOptions poisonNames(Set<String> names) {
        poisonNames.addAll(names);
        return this;
    }

    public RenameOptions typeName(String type, String name) {
        typeNames.put(type, name);
        return this;
    }

    public RenameOptions unwrapCalls(Set<String> methods) {
        unwrapCalls.addAll(methods);
        return this;
    }

    public RenameOptions typeFallback(boolean typeFallback) {
        this.typeFallback = typeFallback;
        return this;
    }

    /** whether a name looks obfuscated, such names are never copied to other parameters */
    public boolean isObfuscated(String name) {
        return obfuscated.matcher(name).matches();
    }

    /** whether a parameter declared as {@code type name} is to be renamed */
    public boolean isObfuscated(String name, Type type, boolean varArgs) {
        if (!isObfuscated(name)) return false;
        if (!typeSpelled) return true;
        boolean array = varArgs;
        Type t = type;
        while (t.isArrayType()) {
            array = true;
            t = t.asArrayType().getComponentType();
        }
        String simple = t.isClassOrInterfaceType() ? t.asClassOrInterfaceType().getNameAsString() : t.asString();
        String spelled = Character.toUpperCase(simple.charAt(0)) + simple.substring(1);
        // paramAnonymousXxx in anonymous classes, paramArrayOfXxx for arrays of any dimension
        return name.matches("param(Anonymous)?" + (array ? "(ArrayOf)+" : "") + Pattern.quote(spelled) + "\\d*");
    }
}
