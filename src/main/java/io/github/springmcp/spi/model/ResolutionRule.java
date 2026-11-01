package io.github.springmcp.spi.model;

/**
 * Which syntactic rule resolved the receiver expression of a method call.
 *
 * <p>
 * Exposed to callers so a wrong answer can be explained: if a hit says {@link #NAME_CONVENTION}
 * the consumer knows it is a guess, whereas {@link #FIELD} means the type is literally declared
 * in the enclosing class.
 * </p>
 */
public enum ResolutionRule {

    /** The call is unqualified and therefore invokes a method on {@code this}. */
    IMPLICIT_THIS,

    /** The receiver is a field declared in the enclosing class. */
    FIELD,

    /** The receiver is a field declared in a superclass or super-interface. */
    SUPER_FIELD,

    /** The receiver is a constructor parameter. */
    CONSTRUCTOR_PARAM,

    /** The receiver is a method parameter. */
    METHOD_PARAM,

    /** The receiver is a local variable declared earlier in the same block. */
    LOCAL_VAR,

    /** The receiver is a for-each or try-with-resources variable. */
    LOOP_VARIABLE,

    /** The call is {@code ClassName.method(...)} and the class has a matching static method. */
    STATIC_CLASS,

    /** The call is unqualified and a matching {@code import static} exists. */
    STATIC_IMPORT,

    /** The receiver was a cast or parenthesised expression that reduced to another receiver. */
    REDUCED,

    /** The type was guessed from the variable name (e.g. {@code orderService} -> {@code OrderService}). */
    NAME_CONVENTION
}
