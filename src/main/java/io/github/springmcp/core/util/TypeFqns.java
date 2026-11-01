package io.github.springmcp.core.util;

import java.util.ArrayList;
import java.util.List;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;

/**
 * Computes the fully qualified name of a type declaration that is currently in an AST.
 *
 * <p>
 * Needed because {@link com.github.javaparser.ast.body.ClassOrInterfaceDeclaration} only knows its
 * own simple name; nesting requires walking up to the compilation unit.
 * </p>
 */
public final class TypeFqns {

    private TypeFqns() {
    }

    /**
     * @param declaration a type declaration node
     * @param packageName package of the enclosing compilation unit
     * @return dotted fully qualified name, e.g. {@code com.acme.Outer.Inner}
     */
    public static String of(TypeDeclaration<?> declaration, String packageName) {
        List<String> chain = new ArrayList<>();
        Node current = declaration;
        while (current instanceof TypeDeclaration<?> td) {
            chain.add(0, td.getNameAsString());
            current = current.getParentNode().orElse(null);
        }
        String joined = String.join(".", chain);
        return packageName == null || packageName.isEmpty() ? joined : packageName + "." + joined;
    }
}
