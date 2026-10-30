/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.refactoring;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import vavi.refactoring.JavaProject.Method;


/**
 * Parameter names given by hand.
 * <pre>
 * # comment
 * Owner.method/arity        name1 name2 ...
 * Owner.method(int,byte[])  name1 name2      # typed key, wins over the arity key
 * Owner.&lt;init&gt;/2            -     name2      # "-" keeps the current name
 * Owner.method/1            =name1           # "=" renames even when the current name is not obfuscated
 * Owner.method/2:arrayOfInt  box             # a local variable, all locals of the name in the method
 * Owner.method/2:str@2       title           # the 2nd local named str in the method (in source order)
 * </pre>
 * A plain name only replaces an obfuscated name, a local name is always replaced.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-10-30 nsano initial version <br>
 */
public class NameMap {

    private final Map<String, String[]> entries = new LinkedHashMap<>();
    /** "method key:local[@n]" → name */
    private final Map<String, String> locals = new LinkedHashMap<>();

    public NameMap() {
    }

    /** parses the text format, see the class comment */
    public static NameMap parse(String text) {
        NameMap map = new NameMap();
        if (text == null) return map;
        int n = 0;
        for (String line : text.split("\\R")) {
            n++;
            int c = line.indexOf('#');
            if (c >= 0) line = line.substring(0, c);
            line = line.strip();
            if (line.isEmpty()) continue;
            String[] f = line.split("\\s+");
            if (f.length < 2) throw new IllegalArgumentException("line " + n + ": no names: " + line);
            if (f[0].contains(":")) {
                if (f.length != 2) throw new IllegalArgumentException("line " + n + ": one name for a local: " + line);
                map.putLocal(f[0], f[1]);
            } else {
                map.put(f[0], Arrays.copyOfRange(f, 1, f.length));
            }
        }
        return map;
    }

    public void put(String key, String... names) {
        entries.put(key.replace(" ", ""), names);
    }

    /** @param key "Owner.method/arity:local" or "Owner.method(types):local@n" */
    public void putLocal(String key, String name) {
        locals.put(key.replace(" ", ""), name);
    }

    public Set<String> keys() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    public Set<String> localKeys() {
        return Collections.unmodifiableSet(locals.keySet());
    }

    public boolean isEmpty() {
        return entries.isEmpty() && locals.isEmpty();
    }

    static String typedKey(Method m) {
        return m.ownerName + "." + m.name + "(" + m.signature() + ")";
    }

    static String arityKey(Method m) {
        return m.ownerName + "." + m.name + "/" + m.arity();
    }

    /**
     * @param occurrence 1 based index among the locals of the name in the method
     * @return the key found and the name, null when there is no entry
     */
    String[] lookupLocal(Method m, String local, int occurrence) {
        for (String k : new String[] {typedKey(m), arityKey(m)}) {
            for (String key : new String[] {k + ":" + local + "@" + occurrence, k + ":" + local}) {
                String name = locals.get(key);
                if (name != null) return new String[] {key, name};
            }
        }
        return null;
    }

    /** @return null when there is no entry for the method */
    String[] lookup(Method m) {
        String[] names = entries.get(typedKey(m));
        return names != null ? names : entries.get(arityKey(m));
    }
}
