package io.github.springmcp.core.analyze;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.springmcp.core.index.AnnotationUse;
import io.github.springmcp.core.index.CuSummary;
import io.github.springmcp.core.index.FieldSummary;
import io.github.springmcp.core.index.MethodSummary;
import io.github.springmcp.core.index.ParameterSummary;
import io.github.springmcp.core.index.TypeSummary;
import io.github.springmcp.core.resolve.TypeNameResolver;
import io.github.springmcp.core.util.AnnotationNames;
import io.github.springmcp.spi.model.Annotations;

/**
 * Finds every element carrying a given annotation.
 *
 * <p>
 * Works purely on summaries, so it never parses a file again after indexing. Matching is by simple
 * name unless {@code strictFqn} is requested — see {@link AnnotationNames} for why.
 * </p>
 */
public final class AnnotationAnalyzer {

    private static final int DEFAULT_LIMIT = 200;

    /**
     * @param types every type in the index
     * @param byFile file summaries, needed to resolve annotation names through imports
     * @param resolver project name resolver
     * @param query caller query
     * @return hits, sorted by file then line
     */
    public Annotations.Report analyze(List<TypeSummary> types, Map<Path, CuSummary> byFile,
            TypeNameResolver resolver, Annotations.Query query) {

        String wanted = AnnotationNames.normalize(query.annotation());
        Set<Annotations.ElementKind> kinds = query.elementKinds();
        int limit = query.limit() > 0 ? query.limit() : DEFAULT_LIMIT;

        List<Annotations.Hit> hits = new ArrayList<>();
        boolean[] simpleNameMatch = { false };

        for (TypeSummary type : types) {
            CuSummary cu = byFile.get(type.file());

            if (accept(kinds, Annotations.ElementKind.TYPE)) {
                for (AnnotationUse a : type.annotations()) {
                    if (AnnotationNames.matches(a, cu, resolver, wanted, query.strictFqn())) {
                        simpleNameMatch[0] = true;
                        hits.add(new Annotations.Hit(a.name(), a.simpleName(), Annotations.ElementKind.TYPE,
                                type.fqn(), null, null, type.file().toString(), a.line(), 0));
                    }
                }
            }

            if (accept(kinds, Annotations.ElementKind.CONSTRUCTOR) || accept(kinds, Annotations.ElementKind.METHOD)) {
                for (MethodSummary m : type.methods()) {
                    Annotations.ElementKind kind = m.constructor() ? Annotations.ElementKind.CONSTRUCTOR
                            : Annotations.ElementKind.METHOD;
                    if (!accept(kinds, kind)) {
                        continue;
                    }
                    for (AnnotationUse a : m.annotations()) {
                        if (AnnotationNames.matches(a, cu, resolver, wanted, query.strictFqn())) {
                            simpleNameMatch[0] = true;
                            hits.add(new Annotations.Hit(a.name(), a.simpleName(), kind, type.fqn(), m.name(),
                                    m.signature(), type.file().toString(), a.line(), m.column()));
                        }
                    }
                    for (ParameterSummary p : m.parameters()) {
                        if (!accept(kinds, Annotations.ElementKind.PARAMETER)) {
                            continue;
                        }
                        for (AnnotationUse a : p.annotations()) {
                            if (AnnotationNames.matches(a, cu, resolver, wanted, query.strictFqn())) {
                                simpleNameMatch[0] = true;
                                hits.add(new Annotations.Hit(a.name(), a.simpleName(),
                                        Annotations.ElementKind.PARAMETER, type.fqn(), p.name(),
                                        m.signature(), type.file().toString(), a.line(), m.column()));
                            }
                        }
                    }
                }
            }

            if (accept(kinds, Annotations.ElementKind.FIELD)) {
                for (FieldSummary f : type.fields()) {
                    for (AnnotationUse a : f.annotations()) {
                        if (AnnotationNames.matches(a, cu, resolver, wanted, query.strictFqn())) {
                            simpleNameMatch[0] = true;
                            hits.add(new Annotations.Hit(a.name(), a.simpleName(), Annotations.ElementKind.FIELD,
                                    type.fqn(), f.name(), f.typeRaw(), type.file().toString(), a.line(), f.column()));
                        }
                    }
                }
            }
        }

        hits.sort(Comparator.comparing(Annotations.Hit::file)
            .thenComparingInt(Annotations.Hit::line)
            .thenComparing(h -> h.ownerType() == null ? "" : h.ownerType()));

        int total = hits.size();
        boolean truncated = total > limit;
        List<Annotations.Hit> page = truncated ? List.copyOf(hits.subList(0, limit)) : List.copyOf(hits);

        List<String> warnings = new ArrayList<>();
        if (simpleNameMatch[0] && !query.strictFqn()) {
            warnings.add("按注解简单名匹配，可能包含同名的自定义注解；需要精确匹配时请传全限定名并设 strictFqn=true");
        }

        return new Annotations.Report(page, total, truncated, warnings);
    }

    private static boolean accept(Set<Annotations.ElementKind> kinds, Annotations.ElementKind kind) {
        return kinds.isEmpty() || kinds.contains(kind);
    }

    private static boolean isTestSource(TypeSummary type) {
        return type.file().toString().replace('\\', '/').contains("/src/test/");
    }
}
