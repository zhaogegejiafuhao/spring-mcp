package io.github.springmcp.core.index;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;

import io.github.springmcp.core.parse.ParseOutcome;

/**
 * Turns a compilation unit into a {@link CuSummary} in a single pass, after which the AST can be
 * garbage collected.
 *
 * <p>
 * Everything here is syntactic. No symbol resolution, no classpath, no inference — the target
 * project is never compiled or loaded, which is the core promise of this server.
 * </p>
 */
public final class CuSummaryBuilder {

    /**
     * Builds a summary.
     * @param file absolute source path
     * @param module project-relative module path, {@code ""} when the project is single-module
     * @param outcome parse result
     * @param size file size in bytes
     * @param lastModifiedMillis file modification time
     * @return summary; empty when the unit was unusable
     */
    public CuSummary build(Path file, String module, ParseOutcome outcome, long size, long lastModifiedMillis) {
        List<String> problems = new ArrayList<>(outcome.problems());
        CompilationUnit cu = outcome.unit();
        if (cu == null) {
            if (problems.isEmpty()) {
                problems.add("文件无法解析");
            }
            return new CuSummary(file, module, "", List.of(), List.of(), Set.of(), Set.of(), problems,
                    lastModifiedMillis, size);
        }

        String packageName = cu.getPackageDeclaration().map(PackageDeclaration::getNameAsString).orElse("");

        List<ImportInfo> imports = new ArrayList<>();
        for (ImportDeclaration imp : cu.getImports()) {
            // Keep the raw name, member part included: `import static a.b.C.parse;` is the only way
            // to attribute an unqualified parse(...) call to a type.
            imports.add(new ImportInfo(imp.getNameAsString(), imp.isStatic(), imp.isAsterisk(),
                    imp.getBegin().map(p -> p.line).orElse(0)));
        }

        List<TypeSummary> topLevel = new ArrayList<>();
        for (TypeDeclaration<?> td : cu.getTypes()) {
            collectType(td, packageName, file, topLevel);
        }

        Set<String> called = new LinkedHashSet<>();
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            called.add(call.getNameAsString());
        }

        Set<String> referenced = new LinkedHashSet<>();
        for (ClassOrInterfaceType t : cu.findAll(ClassOrInterfaceType.class)) {
            referenced.add(t.getNameAsString());
        }

        return new CuSummary(file, module, packageName, imports, topLevel, called, referenced, problems,
                lastModifiedMillis, size);
    }

    private void collectType(TypeDeclaration<?> td, String packageName, Path file, List<TypeSummary> out) {
        String prefix = packageName.isEmpty() ? "" : packageName + ".";
        String fqn = prefix + td.getNameAsString();
        if (td.isNestedType() && td.getParentNode().isPresent()) {
            String parentFqn = parentFqn(td);
            if (parentFqn != null) {
                fqn = parentFqn + "." + td.getNameAsString();
            }
        }

        List<String> supers = new ArrayList<>();
        if (td instanceof ClassOrInterfaceDeclaration cid) {
            cid.getExtendedTypes().forEach(t -> supers.add(t.asString()));
            cid.getImplementedTypes().forEach(t -> supers.add(t.asString()));
        }
        else if (td instanceof EnumDeclaration ed) {
            ed.getImplementedTypes().forEach(t -> supers.add(t.asString()));
        }
        else if (td instanceof RecordDeclaration rd) {
            rd.getImplementedTypes().forEach(t -> supers.add(t.asString()));
        }

        List<FieldSummary> fields = new ArrayList<>();
        List<MethodSummary> methods = new ArrayList<>();
        // Interface fields are implicitly public static final; JavaParser only reports the modifiers
        // that are written, so without this every `String PREFIX = "/x";` in an interface — a very
        // common home for path constants — would look like an instance field and lose its value.
        boolean interfaceMember = td instanceof ClassOrInterfaceDeclaration cid && cid.isInterface();
        for (BodyDeclaration<?> member : td.getMembers()) {
            if (member instanceof FieldDeclaration fd) {
                fields.addAll(toFields(fd, interfaceMember));
            }
            else if (member instanceof MethodDeclaration md) {
                methods.add(toMethod(md));
            }
            else if (member instanceof ConstructorDeclaration cd) {
                methods.add(toConstructor(cd, td.getNameAsString()));
            }
        }

        boolean abstractType = td.getModifiers().stream()
            .anyMatch(modifier -> modifier.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.ABSTRACT);
        out.add(new TypeSummary(fqn, td.getNameAsString(), packageName, kindOf(td), abstractType,
                typeParametersOf(td), supers, toAnnotations(td), fields, methods, file, lineOf(td), columnOf(td)));

        // Recurse so nested types get their own entry and become independently searchable.
        for (BodyDeclaration<?> member : td.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                collectType(nested, packageName, file, out);
            }
        }
    }

    private String parentFqn(TypeDeclaration<?> td) {
        Node parent = td.getParentNode().orElse(null);
        while (parent != null) {
            if (parent instanceof TypeDeclaration<?> pt) {
                // Rebuild the parent's FQN by walking down from the compilation unit.
                String pkg = pt.findCompilationUnit()
                    .flatMap(CompilationUnit::getPackageDeclaration)
                    .map(PackageDeclaration::getNameAsString)
                    .orElse("");
                List<String> chain = new ArrayList<>();
                Node n = pt;
                while (n instanceof TypeDeclaration<?> nt) {
                    chain.add(0, nt.getNameAsString());
                    n = n.getParentNode().orElse(null);
                }
                return (pkg.isEmpty() ? "" : pkg + ".") + String.join(".", chain);
            }
            parent = parent.getParentNode().orElse(null);
        }
        return null;
    }

    private static TypeKind kindOf(TypeDeclaration<?> td) {
        if (td instanceof ClassOrInterfaceDeclaration cid) {
            return cid.isInterface() ? TypeKind.INTERFACE : TypeKind.CLASS;
        }
        if (td instanceof EnumDeclaration) {
            return TypeKind.ENUM;
        }
        if (td instanceof RecordDeclaration) {
            return TypeKind.RECORD;
        }
        if (td instanceof AnnotationDeclaration) {
            return TypeKind.ANNOTATION;
        }
        return TypeKind.CLASS;
    }

    private static List<FieldSummary> toFields(FieldDeclaration fd, boolean interfaceMember) {
        List<FieldSummary> out = new ArrayList<>();
        boolean isStatic = fd.isStatic() || interfaceMember;
        boolean isConstant = isStatic && (fd.isFinal() || interfaceMember);
        List<AnnotationUse> annotations = toAnnotations(fd);
        for (VariableDeclarator v : fd.getVariables()) {
            String init = v.getInitializer().map(Node::toString).orElse(null);
            boolean stringConstant = isConstant && init != null && isStringType(v.getType().asString());
            out.add(new FieldSummary(v.getNameAsString(), v.getType().asString(), annotations, isStatic,
                    stringConstant ? init : null, lineOf(fd), columnOf(fd)));
        }
        return out;
    }

    /**
     * @param typeRaw declared type
     * @return whether it is {@code String}, possibly qualified
     */
    private static boolean isStringType(String typeRaw) {
        String trimmed = typeRaw == null ? "" : typeRaw.trim();
        return "String".equals(trimmed) || trimmed.endsWith(".String");
    }

    /**
     * @param td type declaration
     * @return declared type parameter names in order, e.g. {@code [T, ID]}
     */
    private static List<String> typeParametersOf(TypeDeclaration<?> td) {
        if (!(td instanceof NodeWithTypeParameters<?> withParameters)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (TypeParameter parameter : withParameters.getTypeParameters()) {
            names.add(parameter.getNameAsString());
        }
        return names;
    }

    private static MethodSummary toMethod(MethodDeclaration md) {
        List<ParameterSummary> params = new ArrayList<>();
        for (Parameter p : md.getParameters()) {
            params.add(new ParameterSummary(p.getNameAsString(), p.getType().asString(), toAnnotations(p)));
        }
        return new MethodSummary(md.getNameAsString(), md.getType().asString(), params, toAnnotations(md), false,
                md.isPublic(), md.isStatic(), lineOf(md), columnOf(md));
    }

    private static MethodSummary toConstructor(ConstructorDeclaration cd, String typeName) {
        List<ParameterSummary> params = new ArrayList<>();
        for (Parameter p : cd.getParameters()) {
            params.add(new ParameterSummary(p.getNameAsString(), p.getType().asString(), toAnnotations(p)));
        }
        return new MethodSummary(typeName, null, params, toAnnotations(cd), true, cd.isPublic(), false, lineOf(cd),
                columnOf(cd));
    }

    private static List<AnnotationUse> toAnnotations(NodeWithAnnotations<?> node) {
        List<AnnotationUse> out = new ArrayList<>();
        for (AnnotationExpr a : node.getAnnotations()) {
            out.add(toAnnotation(a));
        }
        return out;
    }

    private static AnnotationUse toAnnotation(AnnotationExpr a) {
        List<AnnotationArg> args = new ArrayList<>();
        if (a instanceof SingleMemberAnnotationExpr sm) {
            args.add(new AnnotationArg("value", stringifyAll(sm.getMemberValue()), allLiterals(sm.getMemberValue())));
        }
        else if (a instanceof NormalAnnotationExpr na) {
            for (MemberValuePair pair : na.getPairs()) {
                args.add(new AnnotationArg(pair.getNameAsString(), stringifyAll(pair.getValue()),
                        allLiterals(pair.getValue())));
            }
        }
        // MarkerAnnotationExpr carries no members.
        return new AnnotationUse(a.getNameAsString(), lineOf(a), args);
    }

    private static List<String> stringifyAll(Expression value) {
        List<String> out = new ArrayList<>();
        if (value instanceof ArrayInitializerExpr array) {
            for (Expression e : array.getValues()) {
                out.add(stringify(e));
            }
        }
        else {
            out.add(stringify(value));
        }
        return out;
    }

    private static boolean allLiterals(Expression value) {
        if (value instanceof ArrayInitializerExpr array) {
            NodeList<Expression> values = array.getValues();
            if (values.isEmpty()) {
                return false;
            }
            for (Expression e : values) {
                if (!(e instanceof StringLiteralExpr)) {
                    return false;
                }
            }
            return true;
        }
        return value instanceof StringLiteralExpr;
    }

    private static String stringify(Expression e) {
        if (e instanceof StringLiteralExpr s) {
            return s.asString();
        }
        return e.toString();
    }

    private static int lineOf(Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }

    private static int columnOf(Node n) {
        return n.getBegin().map(p -> p.column).orElse(0);
    }
}
