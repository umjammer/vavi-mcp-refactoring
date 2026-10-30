/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.refactoring;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import vavi.refactoring.JavaProject.Method;
import vavi.refactoring.ParameterRenamer.Rename;


/**
 * Command line front end of {@link ParameterRenamer}.
 * <pre>
 * java vavi.refactoring.ParameterRenamerCli [options] srcdir...
 *   --map file          names given by hand, see {@link NameMap}
 *   --pattern regex     obfuscated names (default: param[A-Z0-9_]\w*)
 *   --poison a,b        names never copied to other methods
 *   --unwrap ID,...     methods whose single argument stands for itself
 *   --type-name T=name  a name for a type in the type fallback
 *   --no-type-fallback  don't name parameters after their types
 *   --report file       write the renames and the unresolved methods
 *   --apply             write the sources, a dry run without it
 * </pre>
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-10-30 nsano initial version <br>
 */
public class ParameterRenamerCli {

    public static void main(String[] args) throws Exception {
        RenameOptions options = new RenameOptions();
        NameMap map = new NameMap();
        boolean apply = false;
        Path report = null;
        List<Path> roots = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--map" -> map = NameMap.parse(Files.readString(Path.of(args[++i])));
                case "--pattern" -> options.obfuscatedPattern(args[++i]);
                case "--poison" -> options.poisonNames(Set.of(args[++i].split(",")));
                case "--unwrap" -> options.unwrapCalls(Set.of(args[++i].split(",")));
                case "--type-name" -> {
                    String[] kv = args[++i].split("=", 2);
                    options.typeName(kv[0], kv[1]);
                }
                case "--no-type-fallback" -> options.typeFallback(false);
                case "--report" -> report = Path.of(args[++i]);
                case "--apply" -> apply = true;
                default -> roots.add(Path.of(args[i]));
            }
        }
        if (roots.isEmpty()) {
            System.err.println("usage: ParameterRenamerCli [--map file] [--pattern regex] [--poison a,b] [--unwrap m,..] [--type-name T=name] [--no-type-fallback] [--report file] [--apply] srcdir...");
            System.exit(1);
        }

        JavaProject project = new JavaProject(roots);
        ParameterRenamer renamer = new ParameterRenamer(project, options);
        int before = renamer.countObfuscated(true);
        renamer.apply(map);
        renamer.infer();
        List<Rename> renames = renamer.renames();
        List<Method> unresolved = renamer.unresolved();

        project.problems().forEach(p -> System.err.println("parse error: " + p));
        renamer.warnings().forEach(w -> System.err.println("warning: " + w));
        System.err.println(before + " obfuscated, " + renames.size() + " renamed, " + renamer.countObfuscated(false) + " left in " + unresolved.size() + " methods");

        if (report != null) {
            StringBuilder sb = new StringBuilder();
            renames.forEach(r -> sb.append(r.method().unit().path()).append(':').append(r.method().line()).append(' ').append(r).append('\n'));
            unresolved.forEach(m -> sb.append(m.unit().path()).append(':').append(m.line()).append(" UNRESOLVED ").append(m).append('\n'));
            Files.writeString(report, sb);
        }
        if (apply) {
            System.err.println("wrote " + renamer.write().size() + " files");
        }
    }
}
