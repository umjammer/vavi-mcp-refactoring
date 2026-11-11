/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.refactoring.bytecode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import vavi.refactoring.Deobfuscator;
import vavi.refactoring.LocalNameTransfer;
import vavi.refactoring.RenameOptions;


/**
 * De-obfuscates a jar referring to older versions: runs the {@link JarMatcher} commands in their
 * order, decompiles and names the sources.
 * <ol>
 *  <li>{@link #match()}: obfuscated jar ↔ references → mapping, parameter names, report.
 *      Placeholders (members without a counterpart) are named by hand in the names file,
 *      parameter names in the params file.</li>
 *  <li>{@link #prepare()}: renames the jar, derives the throws clauses the obfuscator dropped,
 *      gives every variable a slot of its own, makes the methods of merged interfaces default methods,
 *      writes the parameter names, finds the names of the locals in the last reference.</li>
 *  <li>{@link #decompile(Path)}: Vineflower (Fernflower for the classes it does better), then a patch of
 *      fixes by hand. The patch is a diff of {@code work/src.orig} (the sources before the fixes).</li>
 *  <li>{@link #nameSources(Path, boolean)}: the names of the locals, then inferred names for the rest.</li>
 * </ol>
 * The files in the work directory:
 * <pre>
 * auto.txt mapping.txt report.txt todo.txt params.auto.txt params.txt
 * renamed.jar named.jar locals.txt locals.report.txt
 * src/ src.orig/ (src_ff/) vineflower.log (fernflower.log)
 * </pre>
 * Everything the work directory had of these is replaced.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-11-11 nsano initial version <br>
 */
public class JarDeobfuscator {

    /** the main class of Vineflower and Fernflower */
    static final String DECOMPILER = "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler";

    private final Path jar;
    private final List<Path> refs;
    private final Path work;
    private Path names;
    private Path params;
    private Path mapping;
    private List<String> defaults = List.of();
    private Path vineflower;
    private Path fernflower;
    private List<String> fernflowerClasses = List.of();
    private Path patch;
    private String keep;
    private double minScore = -1;

    /**
     * @param jar the obfuscated jar
     * @param refs references, e.g. the old obfuscated jar, then the old de-obfuscated classes.
     *             The last one gives the names, the locals need its LocalVariableTable (compiled with -g).
     *             None for {@link #decompile(Path)} only.
     * @param work the work directory
     */
    public JarDeobfuscator(Path jar, List<Path> refs, Path work) {
        this.jar = jar;
        this.refs = refs;
        this.work = work;
    }

    /** placeholder → name given by hand, "pkg.Class.member name" to fix a name, see {@link JarMatcher} names */
    public JarDeobfuscator names(Path names) {
        this.names = names;
        return this;
    }

    /** parameter names given by hand: "pkg.Class.method(int,byte[]) name1 name2", they win */
    public JarDeobfuscator params(Path params) {
        this.params = params;
        return this;
    }

    /** where the mapping goes (default: work/mapping.txt) */
    public JarDeobfuscator mapping(Path mapping) {
        this.mapping = mapping;
        return this;
    }

    /** interfaces the obfuscator merged (names after renaming, e.g. "pkg.Device"), their methods become default methods */
    public JarDeobfuscator defaults(List<String> interfaces) {
        this.defaults = interfaces;
        return this;
    }

    /** a Vineflower jar (default: the one on the class path) */
    public JarDeobfuscator vineflower(Path jar) {
        this.vineflower = jar;
        return this;
    }

    /**
     * classes Fernflower decompiles better than Vineflower
     * @param jar the Fernflower jar
     * @param classes "pkg/Class"
     */
    public JarDeobfuscator fernflower(Path jar, List<String> classes) {
        this.fernflower = jar;
        this.fernflowerClasses = classes;
        return this;
    }

    /** fixes by hand of what the decompiler gets wrong: a unified diff for "patch -p0" in the sources */
    public JarDeobfuscator patch(Path patch) {
        this.patch = patch;
        return this;
    }

    /** names the obfuscator did not touch (default: 4 letters or more), see {@link JarMatcher} match */
    public JarDeobfuscator keep(String regex) {
        this.keep = regex;
        return this;
    }

    /** matches scoring under it are not applied (default: 0.5) */
    public JarDeobfuscator minScore(double minScore) {
        this.minScore = minScore;
        return this;
    }

    private void needRefs() {
        if (jar == null || refs.isEmpty()) throw new IllegalArgumentException("no jar or no reference");
    }

    public Path mapping() {
        return mapping != null ? mapping : work.resolve("mapping.txt");
    }

    /** "slot:name" of the locals per method, see {@link LocalNameTransfer} */
    public Path locals() {
        return work.resolve("locals.txt");
    }

    /** the jar to decompile */
    public Path named() {
        return work.resolve("named.jar");
    }

    private String w(String name) {
        return work.resolve(name).toString();
    }

    /**
     * matches the jar with the references, applies the names by hand.
     * @return the log, the placeholders left are in work/todo.txt
     */
    public String match() throws IOException {
        needRefs();
        Files.createDirectories(work);
        StringBuilder log = new StringBuilder();
        List<String> args = new ArrayList<>(List.of("match", jar.toString()));
        refs.forEach(r -> args.add(r.toString()));
        if (keep != null) args.addAll(List.of("--keep", keep));
        if (minScore >= 0) args.addAll(List.of("--min", String.valueOf(minScore)));
        args.addAll(List.of("-o", w("auto.txt"), "-p", w("params.auto.txt"), "-r", w("report.txt")));
        log.append(JarMatcher.capture(args.toArray(String[]::new)));
        Path names = this.names;
        if (names == null || !Files.exists(names)) {
            names = work.resolve("names.empty.txt");
            Files.writeString(names, "");
        }
        Files.createDirectories(mapping().toAbsolutePath().getParent());
        log.append(JarMatcher.capture("names", w("auto.txt"), names.toString(), "-o", mapping().toString(), "-p", w("params.auto.txt"), w("params.txt")));
        if (params != null && Files.exists(params)) { // the ones given by hand win, they come last
            Files.writeString(work.resolve("params.txt"), Files.readString(work.resolve("params.txt")) + Files.readString(params));
        }
        String todo = JarMatcher.capture("todo", mapping().toString());
        Files.writeString(work.resolve("todo.txt"), todo);
        log.append("placeholders left: ").append(todo.lines().count()).append(" (see ").append(w("todo.txt")).append(")\n");
        return log.toString();
    }

    /**
     * renames the jar and prepares it for decompiling, finds the names of the locals.
     * @return the log
     */
    public String prepare() throws IOException {
        needRefs();
        StringBuilder log = new StringBuilder();
        log.append(JarMatcher.capture("rename", jar.toString(), mapping().toString(), w("renamed.jar")));
        log.append(JarMatcher.capture("throws", w("renamed.jar"), w("throws.jar")));
        log.append(JarMatcher.capture("unshare", w("throws.jar"), w("unshared.jar"), "--all"));
        String in = w("unshared.jar");
        if (!defaults.isEmpty()) {
            List<String> args = new ArrayList<>(List.of("defaults", in, w("defaults.jar")));
            args.addAll(defaults);
            log.append(JarMatcher.capture(args.toArray(String[]::new)));
            in = w("defaults.jar");
        }
        log.append(JarMatcher.capture("inject", in, w("params.txt"), named().toString(), "--lvt"));
        log.append(JarMatcher.capture("locals", named().toString(), refs.getLast().toString(), "-o", locals().toString(), "-r", w("locals.report.txt")));
        return log.toString();
    }

    /**
     * decompiles the prepared jar into work/src, applies the patch and copies the sources to out.
     * @return the log
     */
    public String decompile(Path out) throws IOException {
        StringBuilder log = new StringBuilder();
        Path src = work.resolve("src");
        clean(src);
        Files.createDirectories(src);
        run(List.of(java(), "-cp", decompilerJar(vineflower).toString(), DECOMPILER, "--indent-string=    ", named().toString(), src.toString()),
                null, work.resolve("vineflower.log"), "vineflower");
        if (!fernflowerClasses.isEmpty()) {
            if (fernflower == null) throw new IllegalArgumentException("no fernflower jar for " + fernflowerClasses);
            Path ff = work.resolve("src_ff");
            clean(ff);
            Files.createDirectories(ff);
            run(List.of(java(), "-jar", fernflower.toString(), "-ind=    ", named().toString(), ff.toString()), null, work.resolve("fernflower.log"), "fernflower");
            List<String> wanted = fernflowerClasses.stream().map(c -> c + ".java").toList();
            int n = 0;
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(ff.resolve(named().getFileName())))) {
                for (ZipEntry e; (e = zis.getNextEntry()) != null; ) {
                    if (!wanted.contains(e.getName())) continue;
                    Files.write(src.resolve(e.getName()), zis.readAllBytes());
                    n++;
                }
            }
            log.append("fernflower: ").append(n).append('/').append(wanted.size()).append(" classes\n");
        }
        Path orig = work.resolve("src.orig");
        clean(orig);
        copy(src, orig);
        if (patch != null) {
            log.append(run(List.of("patch", "-p0", "--no-backup-if-mismatch"), patch, null, "patch", src));
        }
        log.append("sources: ").append(copy(src, out)).append('\n');
        return log.toString();
    }

    /**
     * names the locals of the sources after the slots (see {@link #prepare()}), then infers names
     * for the parameters and locals still named "var" + slot and writes them.
     * @param infer false: only the names of the locals found in the bytecode
     * @return the log
     */
    public static String nameSources(Path out, Path locals, boolean infer) throws IOException {
        RenameOptions options = new RenameOptions().obfuscatedPattern("var\\d+").typeFallback(false);
        Deobfuscator d = new Deobfuscator(List.of(out), options)
                .transfer(LocalNameTransfer.read(locals))
                .localPattern("var(?!1000\\d\\b)\\d+") // var10000 ...: the decompiler's stack variables
                .infer(infer)
                .run();
        List<Path> written = d.write();
        return "locals from the bytecode: " + d.transferred() +
                (infer ? ", parameters inferred: " + d.parameters().renames().size() +
                        ", locals inferred: " + (d.locals().renames().size() - d.transferred()) + ", left: " + d.locals().unresolved().size() : "") +
                ", wrote " + written.size() + " files\n";
    }

    /** all of it */
    public String run(Path out, boolean infer) throws IOException {
        return match() + prepare() + decompile(out) + nameSources(out, locals(), infer);
    }

    // ----

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** the given jar or the one on the class path that has the decompiler */
    static Path decompilerJar(Path jar) {
        if (jar != null) return jar;
        try {
            return Path.of(Class.forName(DECOMPILER).getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (ClassNotFoundException | URISyntaxException | NullPointerException e) {
            throw new IllegalStateException("no vineflower on the class path, give its jar", e);
        }
    }

    /** runs a process, its output goes to the log file or is returned */
    private static String run(List<String> command, Path input, Path logFile, String what, Path... dir) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (dir.length > 0) pb.directory(dir[0].toFile());
        if (input != null) pb.redirectInput(input.toFile());
        if (logFile != null) pb.redirectOutput(logFile.toFile());
        Process p = pb.start();
        String output = "";
        if (logFile == null) {
            try (InputStream is = p.getInputStream()) {
                output = new String(is.readAllBytes());
            }
        }
        try {
            int exit = p.waitFor();
            if (exit != 0) throw new IOException(what + " failed (" + exit + ")" + (logFile != null ? ", see " + logFile : ":\n" + output));
        } catch (InterruptedException e) {
            p.destroy();
            throw new IOException(what + " interrupted", e);
        }
        return output;
    }

    /** removes a directory of the work directory */
    private void clean(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        if (!dir.toAbsolutePath().normalize().startsWith(work.toAbsolutePath().normalize())) throw new IllegalStateException("not in work: " + dir);
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** @return the java files copied */
    private static int copy(Path from, Path to) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(from)) {
            files = s.filter(p -> p.toString().endsWith(".java")).toList();
        }
        for (Path f : files) {
            Path t = to.resolve(from.relativize(f).toString());
            Files.createDirectories(t.getParent());
            Files.copy(f, t, StandardCopyOption.REPLACE_EXISTING);
        }
        return files.size();
    }

    /**
     * <pre>
     * JarDeobfuscator &lt;obf.jar&gt; &lt;outdir&gt; &lt;work&gt; &lt;ref&gt; [&lt;ref2&gt; ...]
     *     [--names names.txt] [--params params.txt] [--mapping mapping.txt] [--defaults pkg.If,...]
     *     [--vineflower jar] [--fernflower jar pkg/Class,...] [--patch fixes.patch] [--no-infer]
     * </pre>
     */
    public static void main(String[] args) throws IOException {
        List<String> plain = new ArrayList<>();
        Path names = null, params = null, mapping = null, vineflower = null, fernflower = null, patch = null;
        List<String> defaults = List.of(), ffClasses = List.of();
        boolean infer = true;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
            case "--names" -> names = Path.of(args[++i]);
            case "--params" -> params = Path.of(args[++i]);
            case "--mapping" -> mapping = Path.of(args[++i]);
            case "--defaults" -> defaults = List.of(args[++i].split(","));
            case "--vineflower" -> vineflower = Path.of(args[++i]);
            case "--fernflower" -> { fernflower = Path.of(args[++i]); ffClasses = List.of(args[++i].split(",")); }
            case "--patch" -> patch = Path.of(args[++i]);
            case "--no-infer" -> infer = false;
            default -> plain.add(args[i]);
            }
        }
        if (plain.size() < 4) {
            System.err.println("usage: JarDeobfuscator <obf.jar> <outdir> <work> <ref> [<ref2> ...] [--names f] [--params f] [--mapping f]" +
                    " [--defaults If,...] [--vineflower jar] [--fernflower jar pkg/Class,...] [--patch f] [--no-infer]");
            System.exit(1);
        }
        JarDeobfuscator d = new JarDeobfuscator(Path.of(plain.get(0)), plain.subList(3, plain.size()).stream().map(Path::of).toList(), Path.of(plain.get(2)))
                .names(names).params(params).mapping(mapping).defaults(defaults).vineflower(vineflower).fernflower(fernflower, ffClasses).patch(patch);
        System.err.print(d.run(Path.of(plain.get(1)), infer));
    }
}
