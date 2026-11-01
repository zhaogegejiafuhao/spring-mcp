package io.github.springmcp.server.declarative;

import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * The declarative extension file, as data.
 *
 * <p>
 * Two kinds of extension live here. {@code tools} are pure configuration: they name an existing
 * built-in query, pin part of its input, and reshape the output — no code, no compiler. {@code
 * external} entries delegate to a child process so arbitrary logic can be added in any language.
 * </p>
 *
 * <p>
 * Every record is deliberately flat. A richer model (conditionals, loops, expressions) would turn
 * this into a scripting language with a worse syntax and no debugger.
 * </p>
 *
 * @param tools declarative tools, may be empty
 * @param external external process tools, may be empty
 */
public record DeclarativeConfig(List<ToolDef> tools, List<ExternalDef> external) {

    public DeclarativeConfig {
        tools = tools == null ? List.of() : List.copyOf(tools);
        external = external == null ? List.of() : List.copyOf(external);
    }

    /** @return config with nothing declared */
    public static DeclarativeConfig empty() {
        return new DeclarativeConfig(List.of(), List.of());
    }

    /** @return whether there is nothing to register */
    public boolean isEmpty() {
        return tools.isEmpty() && external.isEmpty();
    }

    /**
     * A tool defined purely by configuration.
     *
     * @param name tool name, must be kebab-case
     * @param title optional display title
     * @param description what it does, including when not to use it
     * @param query which built-in query to delegate to, e.g. {@code list-controllers}
     * @param fixed arguments pinned by the config author; the caller cannot override them
     * @param expose base arguments the caller may supply, optionally with rewritten wording
     * @param output output shaping
     * @param summary fixed text for the human readable summary
     */
    public record ToolDef(String name, String title, String description, String query, Map<String, Object> fixed,
            List<Param> expose, Output output, String summary) {

        public ToolDef {
            // Ordered: the key order reaches the tool description, and Map.copyOf randomises it.
            fixed = fixed == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fixed));
            expose = expose == null ? List.of() : List.copyOf(expose);
        }
    }

    /**
     * A caller-supplied argument.
     *
     * @param name base argument name
     * @param description replacement wording; {@code null} keeps the original
     */
    public record Param(String name, String description) {
    }

    /**
     * Output shaping. Intentionally limited to selection, grouping, ordering and truncation.
     *
     * @param source array field to shape; {@code null} picks the largest array in the payload
     * @param select fields to keep per item; empty keeps whole items
     * @param groupBy field to group by; {@code null} for a flat list
     * @param sort ordering, applied before truncation
     * @param limit maximum items overall
     * @param limitPerGroup maximum items per group
     */
    public record Output(String source, List<String> select, String groupBy, List<Sort> sort, Integer limit,
            Integer limitPerGroup) {

        public Output {
            select = select == null ? List.of() : List.copyOf(select);
            sort = sort == null ? List.of() : List.copyOf(sort);
        }

        /** @return whether any shaping was requested */
        public boolean isNoop() {
            return source == null && select.isEmpty() && groupBy == null && sort.isEmpty() && limit == null
                    && limitPerGroup == null;
        }
    }

    /**
     * One ordering key.
     *
     * @param field item field
     * @param desc whether to reverse
     */
    public record Sort(String field, Boolean desc) {

        /** @return whether this key sorts descending */
        public boolean descending() {
            return Boolean.TRUE.equals(desc);
        }
    }

    /**
     * A tool implemented by a child process.
     *
     * @param name tool name, must be kebab-case
     * @param title optional display title
     * @param description what it does
     * @param command argv; must be a list so no shell is involved
     * @param cwd working directory, relative to the project root; {@code null} means the root
     * @param timeoutMs hard timeout, defaults to 15s
     * @param maxOutputBytes cap on captured stdout, defaults to 256 KiB
     * @param expose arguments forwarded to the process
     * @param inputSchemaJson optional JSON schema for the arguments; defaults to an object with the
     * exposed names as optional strings
     */
    public record ExternalDef(String name, String title, String description, List<String> command, String cwd,
            Integer timeoutMs, Integer maxOutputBytes, List<Param> expose, String inputSchemaJson) {

        public ExternalDef {
            command = command == null ? List.of() : List.copyOf(command);
            expose = expose == null ? List.of() : List.copyOf(expose);
        }

        /** @return effective timeout in milliseconds */
        public long effectiveTimeoutMs() {
            return timeoutMs == null || timeoutMs <= 0 ? 15_000L : timeoutMs;
        }

        /** @return effective stdout cap in bytes */
        public int effectiveMaxOutputBytes() {
            return maxOutputBytes == null || maxOutputBytes <= 0 ? 256 * 1024 : maxOutputBytes;
        }
    }
}
