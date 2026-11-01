package io.github.springmcp.core.index;

import java.util.List;

/**
 * A method or constructor parameter.
 *
 * @param name parameter name as written
 * @param typeRaw declared type exactly as written
 * @param annotations annotations on the parameter
 */
public record ParameterSummary(String name, String typeRaw, List<AnnotationUse> annotations) {

    public ParameterSummary {
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }
}
