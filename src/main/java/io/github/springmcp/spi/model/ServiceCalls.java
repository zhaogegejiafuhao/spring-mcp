package io.github.springmcp.spi.model;

import java.util.List;

/**
 * Who calls a given service class or method.
 */
public final class ServiceCalls {

    private ServiceCalls() {
    }

    /**
     * How the resolved receiver relates to the requested target.
     */
    public enum MatchKind {

        /** Receiver type equals the requested type. */
        EXACT,

        /** Caller asked for an interface and the receiver is one of its implementations. */
        IMPLEMENTATION,

        /** Caller asked for a class and the receiver is an interface it implements. */
        INTERFACE_OF_IMPL,

        /** The call originates inside the target service itself. */
        SELF_CALL
    }

    /**
     * @param serviceClass simple or fully qualified class name. Required.
     * @param methodName restrict to calls of this method name; {@code null} means any method
     * @param includeSelfCalls include calls made from within the service implementation itself
     * @param includeUnresolved include a list of call-looking expressions whose receiver could
     * not be resolved
     * @param includeLowConfidence include hits inferred from the variable name (see
     * {@link ResolutionRule#NAME_CONVENTION}); off by default because they can be wrong
     * @param limit hard upper bound on returned call sites, protects the LLM context window
     */
    public record Query(String serviceClass, String methodName, boolean includeSelfCalls, boolean includeUnresolved,
            boolean includeLowConfidence, int limit) {
    }

    /**
     * A resolved call site.
     *
     * @param callerType fully qualified type containing the call
     * @param callerMethod method or constructor name containing the call
     * @param callerSignature readable signature of the enclosing method
     * @param file absolute source path
     * @param line 1-based line of the call
     * @param column 1-based column of the call
     * @param receiverExpression receiver text exactly as written, e.g. {@code orderService}
     * @param receiverType resolved fully qualified receiver type
     * @param declaredIn whether the receiver was declared as a field/param/local, and in which
     * type — helpful to see where the dependency is injected
     * @param rule which syntactic rule produced the answer
     * @param matchKind relation between receiver type and requested target
     * @param confidence see {@link Confidence}
     * @param codeSnippet single trimmed source line, truncated to 200 chars
     */
    public record CallSite(String callerType, String callerMethod, String callerSignature, String file, int line,
            int column, String receiverExpression, String receiverType, String declaredIn, ResolutionRule rule,
            MatchKind matchKind, Confidence confidence, String codeSnippet) {
    }

    /**
     * A call of the right method name whose receiver type could not be determined.
     *
     * @param reason {@code CHAINED_CALL}, {@code METHOD_REFERENCE}, {@code LAMBDA_PARAM},
     * {@code GENERIC_TYPE}, {@code UNKNOWN_RECEIVER}, {@code AMBIGUOUS}
     * @param receiverCandidates plausible types with a similar name, so the caller can retry
     * with a concrete class; may be empty
     */
    public record UnresolvedCallSite(String callerType, String callerMethod, String file, int line,
            String receiverExpression, String reason, List<String> receiverCandidates) {

        public UnresolvedCallSite {
            receiverCandidates = receiverCandidates == null ? List.of() : List.copyOf(receiverCandidates);
        }
    }

    /**
     * @param targetFqn fully qualified target the query resolved to; empty when ambiguous
     * @param targetCandidates all classes matching the requested simple name
     * @param totalCallSites resolved plus unresolved
     * @param resolvedCount hits in {@code callSites}
     * @param unresolvedCount entries in {@code unresolved}
     * @param truncated {@code true} when {@code limit} cut the result short
     * @param warnings non-fatal notes
     */
    public record Report(String targetFqn, List<String> targetCandidates, int totalCallSites, int resolvedCount,
            int unresolvedCount, boolean truncated, List<CallSite> callSites, List<UnresolvedCallSite> unresolved,
            List<String> warnings) {

        public Report {
            targetCandidates = targetCandidates == null ? List.of() : List.copyOf(targetCandidates);
            callSites = callSites == null ? List.of() : List.copyOf(callSites);
            unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
