package io.github.springmcp.core.resolve;

import java.util.List;

/**
 * Outcome of mapping a simple (or partially qualified) type name to a fully qualified one.
 *
 * <p>
 * The important design rule here is that {@link #AMBIGUOUS} is never silently collapsed into a
 * guess. Two classes called {@code OrderService} in different packages are a real possibility,
 * and picking one at random would produce a confidently wrong answer.
 * </p>
 *
 * @param fqn resolved fully qualified name, {@code null} unless the status is
 * {@link Status#RESOLVED} or {@link Status#EXTERNAL}
 * @param status how confident the mapping is
 * @param candidates every class whose simple name matches, for diagnostics
 * @param rule which step of the resolution order produced the answer
 */
public record Resolution(String fqn, Status status, List<String> candidates, Rule rule) {

    /** Confidence of a name mapping. */
    public enum Status {

        /** The name maps to a type that exists in the analysed project. */
        RESOLVED,

        /** The name maps to something outside the project (JDK or third-party jar). */
        EXTERNAL,

        /** Several project types share this simple name. */
        AMBIGUOUS,

        /** Nothing could be concluded. */
        UNKNOWN
    }

    /** Which lookup step decided the mapping. */
    public enum Rule {

        /** The name was already fully qualified. */
        FULLY_QUALIFIED,

        /** A single-type import matched. */
        EXPLICIT_IMPORT,

        /** The type lives in the same package as the reference. */
        SAME_PACKAGE,

        /** A wildcard import matched. */
        WILDCARD_IMPORT,

        /** Exactly one type in the whole project has this simple name. */
        GLOBAL_UNIQUE,

        /** Nothing matched. */
        NONE
    }

    public Resolution {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /** @return whether the name maps to a type inside the indexed project */
    public boolean resolvedInternal() {
        return status == Status.RESOLVED && fqn != null;
    }

    /** @return whether a fully qualified name was produced at all */
    public boolean hasFqn() {
        return fqn != null;
    }

    static Resolution resolved(String fqn, Rule rule) {
        return new Resolution(fqn, Status.RESOLVED, List.of(), rule);
    }

    static Resolution external(String fqn, Rule rule) {
        return new Resolution(fqn, Status.EXTERNAL, List.of(), rule);
    }

    static Resolution ambiguous(List<String> candidates) {
        return new Resolution(null, Status.AMBIGUOUS, candidates, Rule.NONE);
    }

    static Resolution unknown() {
        return new Resolution(null, Status.UNKNOWN, List.of(), Rule.NONE);
    }
}
