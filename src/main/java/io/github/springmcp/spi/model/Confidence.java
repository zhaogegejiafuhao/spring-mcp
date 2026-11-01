package io.github.springmcp.spi.model;

/**
 * How confident the resolver is about a receiver's type.
 *
 * <p>
 * The distinction matters because everything above {@link #LOW} comes from real syntax
 * (a field declaration, a constructor parameter, a local variable), while {@link #LOW} comes
 * from a naming convention guess. Reporting the level lets the caller decide whether to trust
 * a call site.
 * </p>
 */
public enum Confidence {

    /** Type came from a declaration that syntactically owns the call site. */
    HIGH,

    /** Type came from a declaration, but one of several same-named candidates matched. */
    MEDIUM,

    /** Type was inferred from the variable name; may be wrong. */
    LOW
}
