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
import vavi.refactoring.LocalVariableRenamer;
import vavi.refactoring.LocalVariableRenamer.Local;
import vavi.refactoring.NameMap;
import vavi.refactoring.ParameterRenamer;
import vavi.refactoring.ParameterRenamer.Rename;
import vavi.refactoring.RenameOptions;

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
                        "local_pattern", Map.of("type", "string", "optional", true,
                                "description", "Regex of locals to rename (default: " + LocalVariableRenamer.DEFAULT_PATTERN + " that spell the declared type)"),
                        "apply", Map.of("type", "boolean", "optional", true, "description", "Write the parameter and local renames into the files (default: false, a dry run)"),
                        "report_file", Map.of("type", "string", "optional", true, "description", "Write the full list of renames and unresolved variables to this file"),
                        "limit", Map.of("type", "number", "optional", true, "description", "At most this many renames and unresolved locals in the result (default: 200)")
                })))),
                handler(args -> () -> {
                    Deobfuscator d = new Deobfuscator(roots(args), options(args)).nameMap(nameMap(args)).locals(true)
                            .localPattern(stringArg(args, "local_pattern", null)).split(booleanArg(args, "split_reused", false)).run();
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
