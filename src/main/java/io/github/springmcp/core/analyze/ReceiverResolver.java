package io.github.springmcp.core.analyze;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;

import io.github.springmcp.core.index.CuSummary;
import io.github.springmcp.core.index.ImportInfo;
import io.github.springmcp.core.index.TypeSummary;
import io.github.springmcp.core.resolve.TypeNameResolver;
import io.github.springmcp.core.util.AstNodes;
import io.github.springmcp.core.util.TypeFqns;
import io.github.springmcp.spi.model.ResolutionRule;

/**
 * Determines the declared type of the expression a method is called on.
 *
 * <p>
 * This is where "static analysis without a symbol solver" cashes out. There is no type environment
 * to ask, so the receiver is resolved by walking outwards from the call site and stopping at the
 * first declaration that syntactically owns the name — exactly what a human does when reading the
 * code. The rules are ordered so that the cheapest and most certain wins:
 * </p>
 *
 * <ol>
 * <li>local variable or loop variable (most specific, shadows fields)</li>
 * <li>field of the enclosing class</li>
 * <li>field inherited from a supertype in the index</li>
 * <li>constructor parameter, then method parameter</li>
 * <li>static call on a class name</li>
 * <li>static import</li>
 * <li>naming convention, e.g. {@code orderService} -&gt; {@code OrderService}, and only when it
 * resolves to exactly one class in the project</li>
 * </ol>
 *
 * <p>
 * Anything else is reported as unresolved with a reason. Chained calls ({@code getX().y()}) and
 * lambdas are <em>not</em> guessed: a wrong receiver type would show up as a confident but false
 * call site, which is worse than an honest gap.
 * </p>
 */
public final class ReceiverResolver {

    /**
     * Outcome of resolving one receiver expression.
     *
     * @param typeRaw declared type as written, {@code null} when unresolved
     * @param rule which rule answered
     * @param declaredIn type that declares the receiver (field owner or enclosing type)
     * @param expression receiver text as written
     * @param unresolvedReason why resolution failed, {@code null} on success
     * @param candidates plausible classes when the name was ambiguous
     */
    public record Result(String typeRaw, ResolutionRule rule, String declaredIn, String expression,
            String unresolvedReason, List<String> candidates) {

        public Result {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        /** @return whether a declared type was determined */
        public boolean resolved() {
            return typeRaw != null;
        }

        static Result found(String typeRaw, ResolutionRule rule, String declaredIn, String expression) {
            return new Result(typeRaw, rule, declaredIn, expression, null, List.of());
        }

        static Result unresolved(String expression, String reason, List<String> candidates) {
            return new Result(null, null, null, expression, reason, candidates);
        }
    }

    private final Map<String, TypeSummary> byFqn;

    private final TypeNameResolver resolver;

    private final SuperTypeGraphHolder superTypes;

    /**
     * Minimal indirection over the supertype graph so this class stays testable without one.
     *
     * @param closureOf supertype closure lookup
     */
    public record SuperTypeGraphHolder(java.util.function.Function<String, java.util.Set<String>> closureOf) {
    }

    /**
     * @param byFqn every type keyed by fully qualified name
     * @param resolver name resolver
     * @param superTypes supertype closure provider
     */
    public ReceiverResolver(Map<String, TypeSummary> byFqn, TypeNameResolver resolver,
            SuperTypeGraphHolder superTypes) {
        this.byFqn = byFqn;
        this.resolver = resolver;
        this.superTypes = superTypes;
    }

    /**
     * Resolves the receiver of a call.
     * @param call the call expression
     * @param cu file summary the call lives in
     * @param methodName name of the invoked method
     * @param includeLowConfidence whether the naming-convention fallback may be used
     * @return the result, never {@code null}
     */
    public Result resolve(MethodCallExpr call, CuSummary cu, String methodName, boolean includeLowConfidence) {
        String expressionText = call.getScope().map(Object::toString).orElse(methodName);
        Expression scope = call.getScope().orElse(null);

        if (scope == null) {
            // Unqualified call: either an implicit `this` call or a statically imported method.
            for (ImportInfo imp : cu.imports()) {
                if (imp.isStatic() && !imp.isWildcard() && methodName.equals(imp.memberName())) {
                    return Result.found(imp.ownerType(), ResolutionRule.STATIC_IMPORT, null, methodName);
                }
            }
            String enclosing = enclosingFqn(call, cu);
            if (enclosing != null) {
                return Result.found(enclosing, ResolutionRule.IMPLICIT_THIS, enclosing, methodName);
            }
            return Result.unresolved(expressionText, "UNKNOWN_RECEIVER", List.of());
        }

        Expression normalized = unwrap(scope);
        if (normalized instanceof CastExpr cast) {
            return Result.found(cast.getType().asString(), ResolutionRule.REDUCED, null, expressionText);
        }
        if (normalized instanceof ObjectCreationExpr creation) {
            return Result.found(creation.getTypeAsString(), ResolutionRule.REDUCED, null, expressionText);
        }
        if (normalized instanceof ThisExpr) {
            String enclosing = enclosingFqn(call, cu);
            return enclosing == null ? Result.unresolved(expressionText, "UNKNOWN_RECEIVER", List.of())
                    : Result.found(enclosing, ResolutionRule.REDUCED, enclosing, expressionText);
        }
        if (normalized instanceof SuperExpr) {
            return Result.unresolved(expressionText, "SUPER_CALL", List.of());
        }
        if (normalized instanceof MethodCallExpr) {
            return Result.unresolved(expressionText, "CHAINED_CALL", List.of());
        }
        if (normalized instanceof FieldAccessExpr fa) {
            return resolveName(fa.getNameAsString(), call, cu, methodName, expressionText, includeLowConfidence);
        }
        if (normalized instanceof NameExpr ne) {
            return resolveName(ne.getNameAsString(), call, cu, methodName, expressionText, includeLowConfidence);
        }
        return Result.unresolved(expressionText, "UNKNOWN_RECEIVER", List.of());
    }

    private static Expression unwrap(Expression e) {
        Expression current = e;
        while (current instanceof EnclosedExpr enclosed) {
            current = enclosed.getInner();
        }
        return current;
    }

    private Result resolveName(String name, MethodCallExpr call, CuSummary cu, String methodName, String expression,
            boolean includeLowConfidence) {

        // 1. Local variable / loop variable / try-with-resources resource.
        VariableDeclarator local = latestLocalBefore(call, name);
        if (local != null) {
            return Result.found(local.getType().asString(), ResolutionRule.LOCAL_VAR,
                    enclosingFqn(call, cu), expression);
        }

        // 2. Field of the enclosing class.
        Optional<TypeDeclaration<?>> enclosingType = AstNodes.enclosingType(call);
        if (enclosingType.isPresent()) {
            TypeDeclaration<?> td = enclosingType.get();
            FieldDeclaration field = fieldOf(td, name);
            if (field != null) {
                String owner = TypeFqns.of(td, cu.packageName());
                return Result.found(field.getVariable(0).getType().asString(), ResolutionRule.FIELD, owner, expression);
            }
            // 3. Field inherited from a supertype known to the index.
            String owner = TypeFqns.of(td, cu.packageName());
            Result inherited = inheritedField(owner, name, expression);
            if (inherited != null) {
                return inherited;
            }
        }

        // 4. Constructor parameter.
        Optional<ConstructorDeclaration> ctor = AstNodes.enclosingConstructor(call);
        if (ctor.isPresent()) {
            for (Parameter p : ctor.get().getParameters()) {
                if (p.getNameAsString().equals(name)) {
                    return Result.found(p.getType().asString(), ResolutionRule.CONSTRUCTOR_PARAM,
                            enclosingFqn(call, cu), expression);
                }
            }
        }

        // 5. Method parameter (this is how setter injection looks).
        Optional<MethodDeclaration> method = AstNodes.enclosingMethod(call);
        if (method.isPresent()) {
            for (Parameter p : method.get().getParameters()) {
                if (p.getNameAsString().equals(name)) {
                    return Result.found(p.getType().asString(), ResolutionRule.METHOD_PARAM, enclosingFqn(call, cu),
                            expression);
                }
            }
        }

        // 6. Static call on a class name.
        var typeResolution = resolver.resolve(name, cu);
        if (typeResolution.resolvedInternal()) {
            TypeSummary type = byFqn.get(typeResolution.fqn());
            if (type != null && hasStaticMethod(type, methodName)) {
                return Result.found(type.fqn(), ResolutionRule.STATIC_CLASS, type.fqn(), expression);
            }
        }

        // 7. Naming convention, opt-in only.
        if (includeLowConfidence) {
            String guessed = capitalize(name);
            var guess = resolver.resolveGlobally(guessed);
            if (guess.resolvedInternal()) {
                return Result.found(guess.fqn(), ResolutionRule.NAME_CONVENTION, guess.fqn(), expression);
            }
        }

        return Result.unresolved(expression, "UNKNOWN_RECEIVER",
                typeResolution.candidates().isEmpty() ? List.of() : typeResolution.candidates());
    }

    private Result inheritedField(String ownerFqn, String name, String expression) {
        for (String superFqn : superTypes.closureOf().apply(ownerFqn)) {
            if (superFqn.equals(ownerFqn)) {
                continue;
            }
            TypeSummary sup = byFqn.get(superFqn);
            if (sup == null) {
                continue;
            }
            for (var f : sup.fields()) {
                if (f.name().equals(name)) {
                    return Result.found(f.typeRaw(), ResolutionRule.SUPER_FIELD, superFqn, expression);
                }
            }
        }
        return null;
    }

    /**
     * Finds the last local variable declaration of a name that appears before the call.
     * @param call call expression
     * @param name variable name
     * @return the declarator, or {@code null}
     */
    private static VariableDeclarator latestLocalBefore(MethodCallExpr call, String name) {
        Node owner = AstNodes.enclosingCallable(call).orElse(null);
        if (owner == null) {
            return null;
        }
        VariableDeclarator best = null;
        for (VariableDeclarator v : owner.findAll(VariableDeclarator.class)) {
            if (!v.getNameAsString().equals(name)) {
                continue;
            }
            if (!isBefore(v, call)) {
                continue;
            }
            if (best == null || isBefore(best, v)) {
                best = v;
            }
        }
        return best;
    }

    private static boolean isBefore(Node a, Node b) {
        var pa = a.getBegin();
        var pb = b.getBegin();
        return pa.isPresent() && pb.isPresent() && pa.get().isBefore(pb.get());
    }

    private static FieldDeclaration fieldOf(TypeDeclaration<?> td, String name) {
        for (BodyDeclaration<?> member : td.getMembers()) {
            if (member instanceof FieldDeclaration fd) {
                for (VariableDeclarator v : fd.getVariables()) {
                    if (v.getNameAsString().equals(name)) {
                        return fd;
                    }
                }
            }
        }
        return null;
    }

    private static boolean hasStaticMethod(TypeSummary type, String methodName) {
        for (var m : type.methods()) {
            if (!m.constructor() && m.isStatic() && m.name().equals(methodName)) {
                return true;
            }
        }
        return false;
    }

    private static String enclosingFqn(MethodCallExpr call, CuSummary cu) {
        return AstNodes.enclosingType(call).map(td -> TypeFqns.of(td, cu.packageName())).orElse(null);
    }

    private static String capitalize(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
