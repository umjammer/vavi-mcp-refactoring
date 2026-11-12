/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.refactoring.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import vavi.refactoring.Deobfuscator;
import vavi.refactoring.JavaProject;
import vavi.refactoring.JavaProject.Method;
import vavi.refactoring.LocalNameTransfer;
import vavi.refactoring.LocalVariableRenamer;
import vavi.refactoring.LocalVariableRenamer.Local;
import vavi.refactoring.NameMap;
import vavi.refactoring.ParameterRenamer;
import vavi.refactoring.ParameterRenamer.Rename;
import vavi.refactoring.RenameOptions;
import vavi.refactoring.VersionAligner;
import vavi.refactoring.bytecode.JarDeobfuscator;

import static java.lang.System.getLogger;


/**
 * MCP server (stdio) exposing the refactoring tools.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-10-30 nsano initial version <br>
 */
public class RefactoringMCP {

    private static final Logger logger = getLogger(RefactoringMCP.class.getName());

    static final String VERSION = "0.0.1";

    static McpAsyncServer createMcpServer(InputStream in, OutputStream out) {
        StdioServerTransportProvider provider = new StdioServerTransportProvider(McpJsonDefaults.getMapper(), in, out);
        var server = McpServer.async(provider)
                .serverInfo("vavi-mcp-refactoring", VERSION);

        registerParameterTools(server);
        registerBytecodeTools(server);

        return server.build();
    }

    // ---- mcp plumbing ----

    static final Gson gson = new GsonBuilder().create();

    /**
     * @param properties property name to json schema, a property is required unless it has {@code "optional": true}
     */
    static String createSchema(Map<String, Map<String, ?>> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", "http://json-schema.org/draft-07/schema#");
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (properties != null) {
            properties.forEach((name, property) -> {
                Map<String, Object> p = new LinkedHashMap<>(property);
                if (!Boolean.TRUE.equals(p.remove("optional"))) required.add(name);
                props.put(name, p);
            });
        }
        schema.put("properties", props);
        if (!required.isEmpty()) schema.put("required", required);
        return gson.toJson(schema);
    }

    /** name, schema, name, schema ... in this order */
    @SuppressWarnings("unchecked")
    static Map<String, Map<String, ?>> properties(Object... nameAndSchemas) {
        Map<String, Map<String, ?>> out = new LinkedHashMap<>();
        for (int i = 0; i < nameAndSchemas.length; i += 2) out.put((String) nameAndSchemas[i], (Map<String, ?>) nameAndSchemas[i + 1]);
        return out;
    }

    static Tool tool(String name, String description, String schema) {
        return Tool.builder(name)
                .description(description)
                .inputSchema(McpJsonDefaults.getMapper(), schema)
                .build();
    }

    static CallToolResult result(String text, boolean isError) {
        return CallToolResult.builder()
                .addContent(TextContent.builder(text).build())
                .isError(isError)
                .build();
    }

    /**
     * tool bodies run one by one: they read and write the same files, and the stdio transport
     * fails to enqueue responses that complete at the same time on different threads
     */
    static final Scheduler worker = Schedulers.newSingle("refactoring");

    /** tool calls received and not answered yet */
    static final AtomicInteger inFlight = new AtomicInteger();
    /** when a tool call was received or answered last */
    static volatile long lastActivity = System.currentTimeMillis();

    /** runs a (blocking) tool body off the transport thread, exceptions become error results */
    static BiFunction<McpAsyncServerExchange, CallToolRequest, Mono<CallToolResult>> handler(Function<Map<String, Object>, Callable<String>> body) {
        return (exchange, request) -> Mono.<CallToolResult>fromCallable(() -> {
                    try {
                        return result(body.apply(request.arguments() != null ? request.arguments() : Map.of()).call(), false);
                    } catch (Exception e) {
                        logger.log(Level.DEBUG, request.name() + ": " + e, e);
                        return result(e.getMessage() != null ? e.getMessage() : e.toString(), true);
                    }
                })
                .doOnSubscribe(x -> {
                    inFlight.incrementAndGet();
                    lastActivity = System.currentTimeMillis();
                })
                .doFinally(x -> {
                    inFlight.decrementAndGet();
                    lastActivity = System.currentTimeMillis();
                })
                .subscribeOn(worker);
    }

    /**
     * exits once the client has gone: stdin is closed, no call is running and nothing happened for a second
     * (requests read just before the end are dispatched a little later)
     */
    static void exitWhenIdle() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
                if (inFlight.get() == 0 && System.currentTimeMillis() - lastActivity > 1000) {
                    logger.log(Level.DEBUG, "stdin closed, shutting down");
                    System.exit(0);
                }
            }
        }, "exit-when-idle");
        t.setDaemon(true);
        t.start();
    }

    static int intArg(Map<String, Object> args, String name, int defaultValue) {
        Object value = args.get(name);
        if (value == null) return defaultValue;
        return value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
    }

    static boolean booleanArg(Map<String, Object> args, String name, boolean defaultValue) {
        Object value = args.get(name);
        if (value == null) return defaultValue;
        return value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString());
    }

    static String stringArg(Map<String, Object> args, String name, String defaultValue) {
        Object value = args.get(name);
        return value != null ? value.toString() : defaultValue;
    }

    static String stringArg(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null || value.toString().isEmpty()) throw new IllegalArgumentException("'" + name + "' is required");
        return value.toString();
    }

    static List<String> stringsArg(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) return List.of();
        if (value instanceof List<?> l) return l.stream().map(Object::toString).toList();
        return List.of(value.toString().split("[,\\s]+"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> mapArg(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) return Map.of();
        if (value instanceof Map<?, ?> m) {
            Map<String, String> out = new LinkedHashMap<>();
            ((Map<Object, Object>) m).forEach((k, v) -> out.put(k.toString(), v.toString()));
            return out;
        }
        throw new IllegalArgumentException("'" + name + "' must be an object");
    }

    // ---- tool arguments ----

    static final Map<String, ?> ROOTS = Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "Source directories or java files (absolute paths), e.g. [\"/path/to/project/src/main/java\"]. Include every root whose methods are called, names propagate through them");
    static final Map<String, ?> PATTERN = Map.of("type", "string", "optional", true,
            "description", "Regex of obfuscated parameter names (default: " + RenameOptions.DEFAULT_PATTERN + ", JD-GUI/JAD style paramInt, paramArrayOfByte1 ...)");

    /** the options of the parameter inference, shared by the tools that run it */
    static final Object[] PARAMETER_OPTIONS = {
            "name_map", Map.of("type", "string", "optional", true, "description", "Names given by hand, one method per line, '#' starts a comment"),
            "name_map_file", Map.of("type", "string", "optional", true, "description", "A file in the name_map format, read before name_map"),
            "poison_names", Map.of("type", "array", "items", Map.of("type", "string"), "optional", true,
                    "description", "Names known to be wrong in the sources, they are never copied to other variables"),
            "unwrap_calls", Map.of("type", "array", "items", Map.of("type", "string"), "optional", true,
                    "description", "Methods whose single argument stands for itself when propagating, e.g. [\"ID\"] for QTObject.ID(movie)"),
            "type_names", Map.of("type", "object", "additionalProperties", Map.of("type", "string"), "optional", true,
                    "description", "Names for types in the type fallback, e.g. {\"QDRect\": \"rect\"} (default: Movie -> movie)"),
            "type_fallback", Map.of("type", "boolean", "optional", true, "description", "Name reference type parameters after their types (default: true)"),
    };

    static Object[] concat(Object[]... arrays) {
        List<Object> out = new ArrayList<>();
        for (Object[] a : arrays) out.addAll(List.of(a));
        return out.toArray();
    }

    static List<Path> roots(Map<String, Object> args) {
        List<String> roots = stringsArg(args, "roots");
        if (roots.isEmpty()) throw new IllegalArgumentException("'roots' is required");
        List<Path> paths = new ArrayList<>();
        for (String r : roots) {
            Path p = Path.of(r);
            if (!Files.exists(p)) throw new IllegalArgumentException("no such file or directory: " + r);
            paths.add(p);
        }
        return paths;
    }

    static RenameOptions options(Map<String, Object> args) {
        RenameOptions options = new RenameOptions()
                .poisonNames(new HashSet<>(stringsArg(args, "poison_names")))
                .unwrapCalls(new HashSet<>(stringsArg(args, "unwrap_calls")))
                .typeFallback(booleanArg(args, "type_fallback", true));
        String pattern = stringArg(args, "pattern", null);
        if (pattern != null) options.obfuscatedPattern(pattern);
        mapArg(args, "type_names").forEach(options::typeName);
        return options;
    }

    static NameMap nameMap(Map<String, Object> args) throws IOException {
        StringBuilder text = new StringBuilder();
        String file = stringArg(args, "name_map_file", null);
        if (file != null) text.append(Files.readString(Path.of(file))).append('\n');
        text.append(stringArg(args, "name_map", ""));
        return NameMap.parse(text.toString());
    }

    static String location(Method m) {
        return m.unit().path() + ":" + m.line();
    }

    /** at most {@code maxLines} lines of the method source */
    static String snippet(Method m, int maxLines) {
        String[] lines = m.source().split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length && i < maxLines; i++) sb.append("    ").append(lines[i]).append('\n');
        if (lines.length > maxLines) sb.append("    ... (").append(lines.length - maxLines).append(" more lines)\n");
        return sb.toString();
    }

    /** the reason without its detail, "arg MoveTo/2" -> "arg" */
    static String category(String reason) {
        int i = reason.indexOf(' ');
        return i < 0 ? reason : reason.substring(0, i);
    }

    // ---- tools ----

    static void registerParameterTools(McpServer.AsyncSpecification<?> server) {
        server.toolCall(tool(
                        "find-obfuscated-parameters",
                        "List methods whose parameters have obfuscated names (decompiler output like paramInt1, paramArrayOfByte)." +
                        " Use include_source to see the code when naming them by hand", createSchema(properties(
                        "roots", ROOTS,
                        "pattern", PATTERN,
                        "include_source", Map.of("type", "boolean", "optional", true, "description", "Show the method source (default: false)"),
                        "max_source_lines", Map.of("type", "number", "optional", true, "description", "Lines of source per method (default: 12)"),
                        "offset", Map.of("type", "number", "optional", true, "description", "Skip this many methods (default: 0)"),
                        "limit", Map.of("type", "number", "optional", true, "description", "At most this many methods (default: 100)")
                ))),
                handler(args -> () -> {
                    RenameOptions options = options(args);
                    JavaProject project = new JavaProject(roots(args));
                    ParameterRenamer renamer = new ParameterRenamer(project, options);
                    List<Method> methods = renamer.unresolved();
                    int offset = intArg(args, "offset", 0), limit = intArg(args, "limit", 100);
                    boolean source = booleanArg(args, "include_source", false);
                    int maxLines = intArg(args, "max_source_lines", 12);

                    StringBuilder sb = new StringBuilder();
                    sb.append(renamer.countObfuscated(true)).append(" obfuscated parameters in ").append(methods.size()).append(" methods, ")
                            .append(project.units().size()).append(" files\n");
                    project.problems().forEach(p -> sb.append("parse error: ").append(p).append('\n'));
                    for (Method m : methods.subList(Math.min(offset, methods.size()), Math.min(offset + limit, methods.size()))) {
                        sb.append(m).append("  ").append(location(m)).append('\n');
                        if (source) sb.append(snippet(m, maxLines));
                    }
                    if (offset + limit < methods.size()) sb.append("... ").append(methods.size() - offset - limit).append(" more, use offset\n");
                    return sb.toString();
                })
        );

        server.toolCall(tool(
                        "infer-parameter-names",
                        "Infer names for obfuscated parameters and optionally write them into the sources." +
                        " Names come from (in priority order) the name map, overridden/overriding methods, setters, assigned fields," +
                        " the parameters of the methods they are passed to (propagated until nothing changes) and finally their types." +
                        " A name is never taken when it clashes with another variable, so the code keeps its meaning." +
                        " Run it as a dry run first, check the renames and the unresolved methods, give names by hand in name_map and run again with apply." +
                        " name_map lines: 'Owner.method/arity name1 name2' or 'Owner.method(int,byte[]) name1 name2', constructors are 'Owner.<init>/2'," +
                        " '-' keeps a name, '=name' also replaces a name that is not obfuscated (to fix a wrong one)", createSchema(properties(concat(new Object[] {
                        "roots", ROOTS,
                        "pattern", PATTERN}, PARAMETER_OPTIONS, new Object[] {
                        "apply", Map.of("type", "boolean", "optional", true, "description", "Write the renames into the files (default: false, a dry run)"),
                        "report_file", Map.of("type", "string", "optional", true, "description", "Write the full list of renames and unresolved parameters to this file"),
                        "limit", Map.of("type", "number", "optional", true, "description", "At most this many renames and unresolved methods in the result (default: 200)")
                })))),
                handler(args -> () -> {
                    RenameOptions options = options(args);
                    JavaProject project = new JavaProject(roots(args));
                    ParameterRenamer renamer = new ParameterRenamer(project, options);
                    int before = renamer.countObfuscated(true);
                    renamer.apply(nameMap(args));
                    renamer.infer();
                    List<Rename> renames = renamer.renames();
                    List<Method> unresolved = renamer.unresolved();
                    int limit = intArg(args, "limit", 200);

                    Map<String, Integer> byReason = new TreeMap<>();
                    renames.forEach(r -> byReason.merge(category(r.reason()), 1, Integer::sum));

                    StringBuilder sb = new StringBuilder();
                    sb.append(before).append(" obfuscated parameters, ").append(renames.size()).append(" renamed ").append(byReason)
                            .append(", ").append(renamer.countObfuscated(false)).append(" left in ").append(unresolved.size()).append(" methods\n");
                    project.problems().forEach(p -> sb.append("parse error: ").append(p).append('\n'));
                    renamer.warnings().stream().limit(20).forEach(w -> sb.append("warning: ").append(w).append('\n'));
                    if (renamer.warnings().size() > 20) sb.append("... ").append(renamer.warnings().size() - 20).append(" more warnings").append('\n');

                    String reportFile = stringArg(args, "report_file", null);
                    if (reportFile != null) {
                        StringBuilder report = new StringBuilder();
                        renames.forEach(r -> report.append(location(r.method())).append(' ').append(r).append('\n'));
                        unresolved.forEach(m -> report.append(location(m)).append(" UNRESOLVED ").append(m).append('\n'));
                        renamer.warnings().forEach(w -> report.append("WARNING ").append(w).append('\n'));
                        Files.writeString(Path.of(reportFile), report);
                        sb.append("report: ").append(reportFile).append('\n');
                    }

                    if (booleanArg(args, "apply", false)) {
                        List<Path> changed = renamer.write();
                        sb.append("wrote ").append(changed.size()).append(" files\n");
                    } else {
                        sb.append("dry run, nothing written (use apply: true)\n");
                    }

                    sb.append("\nrenames:\n");
                    renames.stream().limit(limit).forEach(r -> sb.append("  ").append(r).append('\n'));
                    if (renames.size() > limit) sb.append("  ... ").append(renames.size() - limit).append(" more").append(reportFile == null ? ", use report_file" : "").append('\n');
                    if (!unresolved.isEmpty()) {
                        sb.append("\nunresolved:\n");
                        unresolved.stream().limit(limit).forEach(m -> sb.append("  ").append(m).append("  ").append(location(m)).append('\n'));
                        if (unresolved.size() > limit) sb.append("  ... ").append(unresolved.size() - limit).append(" more\n");
                    }
                    return sb.toString();
                })
        );

        server.toolCall(tool(
                        "infer-local-names",
                        "Infer names for decompiled local variables (JD-GUI/JAD style localQDRect, localObject1, arrayOfInt2, str, bool, localIOException)" +
                        " and optionally write them into the sources. The parameters are named first like infer-parameter-names (same options)," +
                        " then the locals use those names: a catch parameter is 'e', the element of a for-each over 'listeners' is 'listener'," +
                        " then an assigned field, the parameter of the method it is passed to (out parameters like int[] arrayOfInt), a getter it is initialized with" +
                        " (x = getTrack() -> track), the getter it is returned from and finally its type." +
                        " References are resolved by java scoping, and a name never clashes with an overlapping variable or a reference in the scope." +
                        " With split_reused, a local reused for different values (Object localObject holding a target, later a node) is split into one variable" +
                        " per value first, an Object piece gets the type of its values and its casts are removed." +
                        " name_map also takes locals: 'Owner.method/arity:localName newName', 'Owner.method/arity:str@2 title' for the 2nd str", createSchema(properties(concat(new Object[] {
                        "roots", ROOTS,
                        "pattern", PATTERN}, PARAMETER_OPTIONS, new Object[] {
                        "split_reused", Map.of("type", "boolean", "optional", true, "description", "Split locals reused for different values first (default: false)"),
                        "var_over", Map.of("type", "number", "optional", true,
                                "description", "With split_reused, declare retyped pieces with var when the type is longer than this many characters (default: 0, never)"),
                        "local_pattern", Map.of("type", "string", "optional", true,
                                "description", "Regex of locals to rename (default: " + LocalVariableRenamer.DEFAULT_PATTERN + " that spell the declared type)"),
                        "apply", Map.of("type", "boolean", "optional", true, "description", "Write the parameter and local renames into the files (default: false, a dry run)"),
                        "report_file", Map.of("type", "string", "optional", true, "description", "Write the full list of renames and unresolved variables to this file"),
                        "limit", Map.of("type", "number", "optional", true, "description", "At most this many renames and unresolved locals in the result (default: 200)")
                })))),
                handler(args -> () -> {
                    Deobfuscator d = new Deobfuscator(roots(args), options(args)).nameMap(nameMap(args)).locals(true)
                            .localPattern(stringArg(args, "local_pattern", null)).split(booleanArg(args, "split_reused", false)).varOver(intArg(args, "var_over", 0)).run();
                    JavaProject project = d.project();
                    ParameterRenamer renamer = d.parameters();
                    LocalVariableRenamer locals = d.locals();
                    List<Local> renames = locals.renames();
                    List<Local> unresolved = locals.unresolved();
                    int limit = intArg(args, "limit", 200);

                    Map<String, Integer> byReason = new TreeMap<>();
                    renames.forEach(l -> byReason.merge(category(l.reason()), 1, Integer::sum));

                    StringBuilder sb = new StringBuilder();
                    if (d.splitter() != null) {
                        sb.append(d.splitter().splits().size()).append(" locals split, ").append(d.splitter().retyped()).append(" pieces retyped\n");
                    }
                    sb.append(d.obfuscatedParameters()).append(" obfuscated parameters, ").append(renamer.renames().size()).append(" renamed, ")
                            .append(renamer.countObfuscated(false)).append(" left\n");
                    sb.append(locals.locals().size()).append(" obfuscated locals, ").append(renames.size()).append(" renamed ").append(byReason)
                            .append(", ").append(unresolved.size()).append(" left\n");
                    project.problems().forEach(p -> sb.append("parse error: ").append(p).append('\n'));
                    d.warnings().stream().limit(20).forEach(w -> sb.append("warning: ").append(w).append('\n'));

                    String reportFile = stringArg(args, "report_file", null);
                    if (reportFile != null) {
                        StringBuilder report = new StringBuilder();
                        if (d.splitter() != null) d.splitter().splits().forEach(x -> report.append(x.file()).append(':').append(x.line()).append(" SPLIT ").append(x).append('\n'));
                        renamer.renames().forEach(r -> report.append(location(r.method())).append(' ').append(r).append('\n'));
                        renames.forEach(l -> report.append(l.method().unit().path()).append(':').append(l.line()).append(" LOCAL ").append(l).append('\n'));
                        unresolved.forEach(l -> report.append(l.method().unit().path()).append(':').append(l.line()).append(" LOCAL UNRESOLVED ").append(l).append('\n'));
                        Files.writeString(Path.of(reportFile), report);
                        sb.append("report: ").append(reportFile).append('\n');
                    }

                    if (booleanArg(args, "apply", false)) {
                        sb.append("wrote ").append(d.write().size()).append(" files\n");
                    } else {
                        sb.append("dry run, nothing written (use apply: true)\n");
                    }

                    if (d.splitter() != null && !d.splitter().splits().isEmpty()) {
                        sb.append("\nsplits:\n");
                        d.splitter().splits().stream().limit(limit).forEach(x -> sb.append("  ").append(x).append('\n'));
                    }
                    sb.append("\nlocal renames:\n");
                    renames.stream().limit(limit).forEach(l -> sb.append("  ").append(l).append('\n'));
                    if (renames.size() > limit) sb.append("  ... ").append(renames.size() - limit).append(" more").append(reportFile == null ? ", use report_file" : "").append('\n');
                    if (!unresolved.isEmpty()) {
                        sb.append("\nunresolved locals:\n");
                        unresolved.stream().limit(limit).forEach(l -> sb.append("  ").append(l).append("  ").append(l.method().unit().path()).append(':').append(l.line()).append('\n'));
                        if (unresolved.size() > limit) sb.append("  ... ").append(unresolved.size() - limit).append(" more\n");
                    }
                    return sb.toString();
                })
        );

        server.toolCall(tool(
                        "rename-parameter",
                        "Rename one parameter of a method in a java file, the declaration, its references and the javadoc @param tag." +
                        " Refuses names that clash with other variables in the method", createSchema(properties(
                        "file", Map.of("type", "string", "description", "The java file (absolute path)"),
                        "line", Map.of("type", "number", "description", "A line in the method (the declaration line is fine)"),
                        "method", Map.of("type", "string", "optional", true, "description", "The method name (the class name for a constructor), to pick among nested methods"),
                        "parameter", Map.of("type", "string", "description", "The current parameter name, or its 0 based index"),
                        "new_name", Map.of("type", "string", "description", "The new name"),
                        "apply", Map.of("type", "boolean", "optional", true, "description", "Write the file (default: true)")
                ))),
                handler(args -> () -> {
                    Path file = Path.of(stringArg(args, "file"));
                    if (!Files.isRegularFile(file)) throw new IllegalArgumentException("no such file: " + file);
                    int line = intArg(args, "line", 0);
                    String parameter = stringArg(args, "parameter");
                    String newName = stringArg(args, "new_name");

                    JavaProject project = new JavaProject(List.of(file));
                    ParameterRenamer renamer = new ParameterRenamer(project, new RenameOptions());
                    Method m = renamer.find(file, line, stringArg(args, "method", null));
                    if (m == null) throw new IllegalArgumentException("no method at " + file + ":" + line);
                    int index = -1;
                    for (int i = 0; i < m.arity(); i++) if (m.name(i).equals(parameter)) index = i;
                    if (index < 0 && parameter.matches("\\d+")) index = Integer.parseInt(parameter);
                    if (index < 0 || index >= m.arity()) throw new IllegalArgumentException("no parameter '" + parameter + "' in " + m);

                    String before = m.toString();
                    renamer.rename(m, index, newName);
                    if (booleanArg(args, "apply", true)) {
                        renamer.write();
                        return "renamed: " + before + " -> " + m;
                    }
                    return "dry run: " + before + " -> " + m;
                })
        );
    }

    // ---- bytecode tools ----

    static final Map<String, ?> JAR = Map.of("type", "string", "description", "The obfuscated jar (absolute path)");
    static final Map<String, ?> REFS = Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "References (jars or class directories, absolute paths), matched in a chain: e.g. [old obfuscated jar, old de-obfuscated classes]." +
            " The last one gives the names, compiled with -g its LocalVariableTable gives the names of the locals");
    static final Map<String, ?> WORK = Map.of("type", "string", "description", "The work directory (absolute path), the steps read and write their files there");

    /** the options of the steps, shared by the tools that run them */
    static final Object[] MATCH_OPTIONS = {
            "names_file", Map.of("type", "string", "optional", true,
                    "description", "Names given by hand: 'placeholder newName' for what has no counterpart (see todo.txt), 'pkg.Class.member newName' to fix a name"),
            "params_file", Map.of("type", "string", "optional", true, "description", "Parameter names given by hand: 'pkg.Class.method(int,byte[]) name1 name2', they win"),
            "mapping_file", Map.of("type", "string", "optional", true, "description", "Where the mapping (proguard format) goes (default: work/mapping.txt)"),
            "keep", Map.of("type", "string", "optional", true, "description", "Regex of names the obfuscator did not touch (default: 4 letters or more)"),
            "min_score", Map.of("type", "number", "optional", true, "description", "Matches scoring under it are not applied (default: 0.5)"),
    };
    static final Object[] PREPARE_OPTIONS = {
            "defaults", Map.of("type", "array", "items", Map.of("type", "string"), "optional", true,
                    "description", "Interfaces the obfuscator merged (names after renaming, e.g. \"pkg.Device\"), their methods become default methods doing nothing"),
    };
    static final Object[] DECOMPILE_OPTIONS = {
            "vineflower_jar", Map.of("type", "string", "optional", true, "description", "A Vineflower jar (default: the bundled one)"),
            "fernflower_jar", Map.of("type", "string", "optional", true, "description", "A Fernflower jar, for fernflower_classes"),
            "fernflower_classes", Map.of("type", "array", "items", Map.of("type", "string"), "optional", true,
                    "description", "Classes Fernflower decompiles better than Vineflower, \"pkg/Class\""),
            "patch_file", Map.of("type", "string", "optional", true,
                    "description", "Fixes by hand of what the decompiler gets wrong, a unified diff applied with 'patch -p0' in the sources (make it against work/src.orig)"),
    };

    static Path pathArg(Map<String, Object> args, String name) {
        String s = stringArg(args, name, null);
        return s != null ? Path.of(s) : null;
    }

    static JarDeobfuscator jarDeobfuscator(Map<String, Object> args) {
        String jar = stringArg(args, "jar", null);
        List<Path> refs = stringsArg(args, "refs").stream().map(Path::of).toList();
        for (Path p : refs) if (!Files.exists(p)) throw new IllegalArgumentException("no such file or directory: " + p);
        if (jar != null && !Files.exists(Path.of(jar))) throw new IllegalArgumentException("no such file: " + jar);
        JarDeobfuscator d = new JarDeobfuscator(jar != null ? Path.of(jar) : null, refs, Path.of(stringArg(args, "work")))
                .names(pathArg(args, "names_file"))
                .params(pathArg(args, "params_file"))
                .mapping(pathArg(args, "mapping_file"))
                .keep(stringArg(args, "keep", null))
                .defaults(stringsArg(args, "defaults"))
                .vineflower(pathArg(args, "vineflower_jar"))
                .fernflower(pathArg(args, "fernflower_jar"), stringsArg(args, "fernflower_classes"))
                .patch(pathArg(args, "patch_file"));
        Object min = args.get("min_score");
        if (min != null) d.minScore(min instanceof Number n ? n.doubleValue() : Double.parseDouble(min.toString()));
        return d;
    }

    static void registerBytecodeTools(McpServer.AsyncSpecification<?> server) {
        server.toolCall(tool(
                        "match-jars",
                        "Derive a de-obfuscation mapping for an obfuscated jar from references (older versions, obfuscated or not) by structural matching." +
                        " Writes work/mapping.txt (proguard format), params.txt, report.txt (HINT, AMBIGUOUS, LOW, CLASH ... to check) and todo.txt:" +
                        " the placeholders (Obf_x, f_x__y, m_x__y) of what has no counterpart, to be named in names_file and matched again." +
                        " The first step of deobfuscate-jar", createSchema(properties(concat(new Object[] {
                        "jar", JAR, "refs", REFS, "work", WORK}, MATCH_OPTIONS)))),
                handler(args -> () -> jarDeobfuscator(args).match())
        );

        server.toolCall(tool(
                        "prepare-jar",
                        "Apply the mapping of match-jars to the jar and prepare it for decompiling: rename (also methods the obfuscator named alike)," +
                        " raise access the obfuscator weakened, drop bogus final, derive the throws clauses it dropped, give every variable a slot of its own," +
                        " make the methods of merged interfaces default methods, write the parameter names." +
                        " Also finds the names of the locals in the last reference (work/locals.txt, for transfer-local-names). Writes work/named.jar", createSchema(properties(concat(new Object[] {
                        "jar", JAR, "refs", REFS, "work", WORK}, new Object[] {
                        "mapping_file", MATCH_OPTIONS[5]}, PREPARE_OPTIONS)))),
                handler(args -> () -> jarDeobfuscator(args).prepare())
        );

        server.toolCall(tool(
                        "decompile-jar",
                        "Decompile work/named.jar of prepare-jar with Vineflower (Fernflower for the classes given), apply the patch of fixes by hand" +
                        " and copy the sources to out_dir. work/src.orig keeps the sources before the patch, make the patch against it", createSchema(properties(concat(new Object[] {
                        "work", WORK,
                        "out_dir", Map.of("type", "string", "description", "Where the sources go (absolute path), files there are replaced")}, DECOMPILE_OPTIONS)))),
                handler(args -> () -> jarDeobfuscator(args).decompile(Path.of(stringArg(args, "out_dir"))))
        );

        server.toolCall(tool(
                        "transfer-local-names",
                        "Name the locals of decompiled sources (var12: named after its slot by Vineflower/Fernflower) with the names found in the bytecode" +
                        " of an older version (work/locals.txt of prepare-jar: 'pkg.Owner.method(int,byte[]) slot:name ...')." +
                        " A clashing name gets a number. With infer, then names the parameters and locals still var12 like infer-local-names", createSchema(properties(
                        "roots", ROOTS,
                        "locals_file", Map.of("type", "string", "description", "The names of the locals (work/locals.txt of prepare-jar)"),
                        "slot_pattern", Map.of("type", "string", "optional", true,
                                "description", "Regex of a local named after its slot, group 1 is the slot (default: " + LocalNameTransfer.DEFAULT_SLOT_PATTERN + ")"),
                        "infer", Map.of("type", "boolean", "optional", true, "description", "Then infer names for the rest (default: false)"),
                        "apply", Map.of("type", "boolean", "optional", true, "description", "Write the renames into the files (default: false, a dry run)"),
                        "report_file", Map.of("type", "string", "optional", true, "description", "Write the full list of renames and unresolved locals to this file")
                ))),
                handler(args -> () -> {
                    LocalNameTransfer transfer = LocalNameTransfer.read(Path.of(stringArg(args, "locals_file")));
                    String slotPattern = stringArg(args, "slot_pattern", null);
                    if (slotPattern != null) transfer.slotPattern(slotPattern);
                    boolean infer = booleanArg(args, "infer", false);
                    RenameOptions options = new RenameOptions().obfuscatedPattern("var\\d+").typeFallback(false);
                    Deobfuscator d = new Deobfuscator(roots(args), options).transfer(transfer).localPattern("var(?!1000\\d\\b)\\d+").infer(infer).run();
                    LocalVariableRenamer locals = d.locals();
                    StringBuilder sb = new StringBuilder();
                    sb.append(transfer.size()).append(" methods in the file, ").append(d.transferred()).append(" locals named from the bytecode");
                    if (infer) sb.append(", ").append(d.parameters().renames().size()).append(" parameters and ")
                            .append(locals.renames().size() - d.transferred()).append(" locals inferred");
                    sb.append(", ").append(locals.unresolved().size()).append(" locals left\n");
                    d.project().problems().forEach(p -> sb.append("parse error: ").append(p).append('\n'));
                    d.warnings().stream().limit(20).forEach(w -> sb.append("warning: ").append(w).append('\n'));
                    String reportFile = stringArg(args, "report_file", null);
                    if (reportFile != null) {
                        StringBuilder report = new StringBuilder();
                        d.parameters().renames().forEach(r -> report.append(location(r.method())).append(' ').append(r).append('\n'));
                        locals.renames().forEach(l -> report.append(l.method().unit().path()).append(':').append(l.line()).append(" LOCAL ").append(l).append('\n'));
                        locals.unresolved().forEach(l -> report.append(l.method().unit().path()).append(':').append(l.line()).append(" LOCAL UNRESOLVED ").append(l).append('\n'));
                        d.warnings().forEach(w -> report.append("WARNING ").append(w).append('\n'));
                        Files.writeString(Path.of(reportFile), report);
                        sb.append("report: ").append(reportFile).append('\n');
                    }
                    if (booleanArg(args, "apply", false)) {
                        sb.append("wrote ").append(d.write().size()).append(" files\n");
                    } else {
                        sb.append("dry run, nothing written (use apply: true)\n");
                    }
                    return sb.toString();
                })
        );

        server.toolCall(tool(
                        "deobfuscate-jar",
                        "De-obfuscate a jar referring to older versions, all steps: match-jars, prepare-jar, decompile-jar, transfer-local-names (with infer)." +
                        " Run match-jars first to name what has no counterpart (todo.txt) and check report.txt, then this", createSchema(properties(concat(new Object[] {
                        "jar", JAR, "refs", REFS, "work", WORK,
                        "out_dir", Map.of("type", "string", "description", "Where the sources go (absolute path), files there are replaced"),
                        "infer", Map.of("type", "boolean", "optional", true, "description", "Infer names for the parameters and locals the bytecode does not name (default: true)")},
                        MATCH_OPTIONS, PREPARE_OPTIONS, DECOMPILE_OPTIONS)))),
                handler(args -> () -> jarDeobfuscator(args).run(Path.of(stringArg(args, "out_dir")), booleanArg(args, "infer", true)))
        );

        server.toolCall(tool(
                        "align-versions",
                        "Make the decompiled sources of two versions identical where the code is the same, so a diff between them shows only what changed:" +
                        " the locals, parameters and labels that correspond in the aligned tokens of both get one name (a real name wins over var12" +
                        " and a numbered one, on a tie the preferred side), the members of a are ordered like those of b (methods, nested types," +
                        " fields that are no code). Both sides are renamed, line numbers of b stay. Run after deobfuscate-jar of both", createSchema(properties(
                        "a", Map.of("type", "string", "description", "Source root of one version (absolute path), its members are reordered"),
                        "b", Map.of("type", "string", "description", "Source root of the other version (absolute path)"),
                        "prefer", Map.of("type", "string", "optional", true, "enum", List.of("a", "b"),
                                "description", "Whose name wins when both are real names (default: a)"),
                        "aliases_file", Map.of("type", "string", "optional", true,
                                "description", "Members named differently on purpose (e.g. renumbered), 'pkg/Class.java nameInA nameInB' per line"),
                        "names", Map.of("type", "boolean", "optional", true, "description", "Align the names (default: true)"),
                        "members", Map.of("type", "boolean", "optional", true, "description", "Align the order of the members (default: true)"),
                        "apply", Map.of("type", "boolean", "optional", true, "description", "Write the changes into the files (default: false, a dry run)"),
                        "report_file", Map.of("type", "string", "optional", true, "description", "Write the full list of renames and moves to this file")
                ))),
                handler(args -> () -> {
                    VersionAligner va = new VersionAligner(Path.of(stringArg(args, "a")), Path.of(stringArg(args, "b")))
                            .prefer(VersionAligner.Side.valueOf(stringArg(args, "prefer", "a").toUpperCase()));
                    Path aliases = pathArg(args, "aliases_file");
                    if (aliases != null) va.aliases(aliases);
                    boolean names = booleanArg(args, "names", true), members = booleanArg(args, "members", true);
                    if (names) va.alignNames();
                    if (members) va.alignMembers();
                    if (names && members) va.alignNames();
                    StringBuilder sb = new StringBuilder();
                    long ra = va.renames().stream().filter(r -> r.side() == VersionAligner.Side.A).count();
                    sb.append("renamed: a ").append(ra).append(", b ").append(va.renames().size() - ra)
                            .append(", moved members: ").append(va.moves().stream().mapToInt(VersionAligner.Move::members).sum())
                            .append(", files still differing: ").append(va.differingFiles()).append('\n');
                    va.problems().forEach(p -> sb.append("parse error: ").append(p).append('\n'));
                    va.renames().stream().limit(30).forEach(r -> sb.append(r).append('\n'));
                    String reportFile = stringArg(args, "report_file", null);
                    if (reportFile != null) {
                        StringBuilder report = new StringBuilder();
                        va.renames().forEach(r -> report.append(r).append('\n'));
                        va.moves().forEach(m -> report.append("MOVE ").append(m).append('\n'));
                        Files.writeString(Path.of(reportFile), report);
                        sb.append("report: ").append(reportFile).append('\n');
                    }
                    if (booleanArg(args, "apply", false)) {
                        sb.append("wrote ").append(va.write().size()).append(" files\n");
                    } else {
                        sb.append("dry run, nothing written (use apply: true)\n");
                    }
                    return sb.toString();
                })
        );
    }

    /** Main Application */
    public static void main(String[] args) {
        try {
            // the client closes stdin when it goes away
            InputStream in = new FilterInputStream(System.in) {
                boolean closed;
                int eof(int n) {
                    if (n < 0 && !closed) {
                        closed = true;
                        lastActivity = System.currentTimeMillis();
                        exitWhenIdle();
                    }
                    return n;
                }
                @Override public int read() throws IOException { return eof(super.read()); }
                @Override public int read(byte[] b, int off, int len) throws IOException { return eof(super.read(b, off, len)); }
            };
            createMcpServer(in, System.out);
            logger.log(Level.DEBUG, "Refactoring MCP Server running on stdio");
            // Keep the main thread alive
            Thread.currentThread().join();
        } catch (Exception e) {
            logger.log(Level.ERROR, "Failed to start server: " + e.getMessage(), e);
            System.exit(1);
        }
    }
}
