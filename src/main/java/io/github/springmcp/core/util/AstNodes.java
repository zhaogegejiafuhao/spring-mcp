package io.github.springmcp.core.util;

import java.util.Optional;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

/**
 * Minimal upward navigation over a JavaParser AST.
 *
 * <p>
 * JavaParser's own {@code Node.findAncestor(Class...)} takes a varargs array of {@code Class<N>},
 * which produces unchecked-generic and heap-pollution warnings for generic node types such as
 * {@link TypeDeclaration}, and forces callers to write raw types. These three helpers cover every
 * lookup this project needs with plain parent walks, so the build stays warning free and the call
 * sites read exactly as what they mean.
 * </p>
 */
public final class AstNodes {

    private AstNodes() {
    }

    /**
     * Finds the innermost enclosing type declaration, including nested and anonymous-free cases.
     * @param from node to start from (the node itself is not considered)
     * @return the enclosing type, or empty when the node is not inside one
     */
    public static Optional<TypeDeclaration<?>> enclosingType(Node from) {
        Node current = from.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type) {
                return Optional.of(type);
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    /**
     * @param from node to start from
     * @return the innermost enclosing method declaration, or empty
     */
    public static Optional<MethodDeclaration> enclosingMethod(Node from) {
        Node current = from.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof MethodDeclaration method) {
                return Optional.of(method);
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    /**
     * @param from node to start from
     * @return the innermost enclosing constructor declaration, or empty
     */
    public static Optional<ConstructorDeclaration> enclosingConstructor(Node from) {
        Node current = from.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof ConstructorDeclaration constructor) {
                return Optional.of(constructor);
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    /**
     * @param from node to start from
     * @return the innermost enclosing method or constructor, or empty when the node sits in a field
     * initialiser or an initialiser block
     */
    public static Optional<Node> enclosingCallable(Node from) {
        Node current = from.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof MethodDeclaration || current instanceof ConstructorDeclaration) {
                return Optional.of(current);
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }
}
