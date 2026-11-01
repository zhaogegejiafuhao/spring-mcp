package io.github.springmcp.server.declarative;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.springmcp.spi.ToolDescriptor;
import io.github.springmcp.spi.ToolInvocation;
import io.github.springmcp.spi.ToolOutcome;
import io.github.springmcp.spi.ToolProvider;

/**
 * A tool defined only by configuration, implemented by delegating to a built-in query.
 *
 * <p>
 * Delegation rather than reimplementation is the central decision here. Argument parsing, error
 * codes, warning merging and index handling already exist in the built-in tools; a second copy would
 * drift, and a declarative tool that answers slightly differently from the tool it claims to wrap is
 * worse than no tool at all. The cost is one extra object copy per call — microseconds against a
 * query that touches files.
 * </p>
 */
public final class DeclarativeTool implements ToolProvider {

    private final ToolDescriptor descriptor;

    private final ToolProvider delegate;

    private final Map<String, Object> fixed;

    private final Set<String> exposed;

    private final DeclarativeConfig.Output output;

    private final String summary;

    private final ResultShaper shaper = new ResultShaper();

    /**
     * @param descriptor derived descriptor, including the narrowed schema
     * @param delegate the built-in tool doing the actual work
     * @param fixed pinned arguments
     * @param expose caller-visible argument names
     * @param output shaping rules
     * @param summary fixed summary text, may be {@code null} to reuse the delegate's
     */
    public DeclarativeTool(ToolDescriptor descriptor, ToolProvider delegate, Map<String, Object> fixed,
            List<DeclarativeConfig.Param> expose, DeclarativeConfig.Output output, String summary) {
        this.descriptor = descriptor;
        this.delegate = delegate;
        // Ordered: the key order ends up in the tool description shown to the model.
        this.fixed = fixed == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fixed));
        this.output = output == null ? new DeclarativeConfig.Output(null, List.of(), null, List.of(), null, null)
                : output;
        this.summary = summary;
        Set<String> names = new LinkedHashSet<>();
        if (expose != null) {
            expose.forEach(param -> names.add(param.name()));
        }
        this.exposed = Set.copyOf(names);
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : invocation.arguments().entrySet()) {
            if (exposed.contains(entry.getKey())) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        // Config wins. The author pinned these values to build a specialised tool; a caller that can
        // override them should be using the general tool instead.
        merged.putAll(fixed);

        ToolOutcome outcome = delegate.invoke(new ToolInvocation(merged, invocation.context()));
        if (outcome instanceof ToolOutcome.Failure failure) {
            // Errors pass through untouched so the code and hint stay identical to the delegate's.
            return failure;
        }

        ToolOutcome.Success success = (ToolOutcome.Success) outcome;
        ObjectNode shaped = shaper.shape(success.structured(), output);
        String base = summary != null && !summary.isBlank() ? summary : success.humanSummary();
        return ToolOutcome.ok(shaped, base + counts(shaped), success.warnings());
    }

    private static String counts(ObjectNode shaped) {
        int total = shaped.path("total").asInt();
        int returned = shaped.path("returned").asInt();
        if (total == returned) {
            return "（" + total + " 条）";
        }
        return "（共 " + total + " 条，返回 " + returned + " 条）";
    }
}
