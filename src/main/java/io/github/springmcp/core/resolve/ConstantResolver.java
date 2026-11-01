package io.github.springmcp.core.resolve;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.springmcp.core.index.CuSummary;
import io.github.springmcp.core.index.FieldSummary;
import io.github.springmcp.core.index.ImportInfo;
import io.github.springmcp.core.index.TypeSummary;

/**
 * Evaluates the compile-time string constants that appear in annotation arguments.
 *
 * <p>
 * {@code @RequestMapping(SwaggerTagConst.URL_PREFIX)} is not an opaque expression: the value is
 * written three files away as {@code static final String URL_PREFIX = "/support"}. Returning the
 * variable name as the path is technically honest and practically useless — in one project of the
 * regression corpus it hides 46% of the endpoints. So the value is folded when, and only when, it is
 * genuinely known:
 * </p>
 *
 * <ul>
 * <li>a string literal, or {@code +} of literals and known constants;</li>
 * <li>a {@code static final String} / interface constant / {@code const val} found in the index —
 * a field that can be reassigned is never treated as a constant, because its value at the call site
 * is not knowable from the declaration;</li>
 * <li>a reference qualified by a type name resolved in the file's own import context, so
 * {@code Constants.BASE} resolves to the {@code Constants} this file imported rather than to a
 * same-named class in another module;</li>
 * <li>a bare name, resolved first against the type that declares the annotation (sibling constant)
 * and then against the file's static imports ({@code import static com.acme.Api.PREFIX;}).</li>
 * </ul>
 *
 * <p>
 * Constants written in terms of other constants are followed — {@code API_FRONT = API + "/front"} is
 * one of the most common ways to build a prefix — with a depth bound and a visited set so a cyclic
 * pair terminates.
 * </p>
 *
 * <p>
 * Everything else returns {@code null}, which callers report as unresolved: a {@code ${...}}
 * placeholder, an injected value, a method call. Ambiguity is treated the same way: a name that
 * resolves to two types means the expression cannot be resolved, and a wrong path is worse than an
 * unread one.
 * </p>
 *
 * <p>
 * Immutable and thread-safe.
 * </p>
 */
public final class ConstantResolver {

    private static final char KEY_SEPARATOR = '#';

    /** Bound on how many constants one expression may be folded through. */
    private static final int MAX_DEPTH = 8;

    /** {@code ownerFqn#FIELD} to its initialiser. */
    private final Map<String, ConstantRef> constants;

    private final TypeNameResolver types;

    private final SuperTypeGraph superTypes;

    /**
     * @param types name resolver, used to turn the qualifier of a reference into a fully qualified
     * owner
     * @param superTypes used to find a constant declared in a supertype, which is where a Feign
     * interface usually keeps its prefix
     * @param allTypes every type in the index
     * @param byFile file summaries, so a constant's initialiser can be folded in the import context
     * it was written in
     */
    public ConstantResolver(TypeNameResolver types, SuperTypeGraph superTypes, List<TypeSummary> allTypes,
            Map<Path, CuSummary> byFile) {
        this.types = types;
        this.superTypes = superTypes;
        Map<String, ConstantRef> found = new LinkedHashMap<>();
        for (TypeSummary type : allTypes) {
            CuSummary context = byFile == null ? null : byFile.get(type.file());
            for (FieldSummary field : type.fields()) {
                if (field.isStringConstant()) {
                    found.putIfAbsent(key(type.fqn(), field.name()),
                            new ConstantRef(field.constantInitializer(), context));
                }
            }
        }
        this.constants = Map.copyOf(found);
    }

    /**
     * Evaluates an expression to the string it denotes at compile time.
     *
     * @param expression raw annotation argument as written, e.g. {@code Api.BASE + "/list"}
     * @param context file the annotation appears in, used to resolve imports; may be {@code null}
     * @return the folded value, or {@code null} when any part of it is not a compile-time constant
     */
    public String evaluate(String expression, CuSummary context) {
        return evaluate(expression, context, null);
    }

    /**
     * Evaluates an expression in the scope of the type that declares it.
     *
     * <p>
     * The scope matters for the unqualified form: {@code @GetMapping(API_PREFIX)} inside an interface
     * that declares {@code String API_PREFIX = "/api"} refers to the sibling constant, which is
     * neither an import nor a static import.
     * </p>
     *
     * @param expression raw annotation argument as written
     * @param context file the annotation appears in, may be {@code null}
     * @param ownerFqn type declaring the annotation, may be {@code null}
     * @return the folded value, or {@code null} when any part of it is not a compile-time constant
     */
    public String evaluate(String expression, CuSummary context, String ownerFqn) {
        return evaluate(expression, context, ownerFqn, new HashSet<>(), 0);
    }

    /**
     * @return number of string constants known to the index, exposed for diagnostics
     */
    public int size() {
        return constants.size();
    }

    private String evaluate(String expression, CuSummary context, String ownerFqn, Set<String> seen, int depth) {
        if (expression == null || depth > MAX_DEPTH) {
            return null;
        }
        String text = expression.trim();
        if (text.isEmpty()) {
            return null;
        }
        List<String> parts = splitTopLevel(text);
        if (parts.size() > 1) {
            StringBuilder joined = new StringBuilder();
            for (String part : parts) {
                String value = single(part.trim(), context, ownerFqn, seen, depth);
                if (value == null) {
                    return null;
                }
                joined.append(value);
            }
            return joined.toString();
        }
        return single(text, context, ownerFqn, seen, depth);
    }

    private String single(String expression, CuSummary context, String ownerFqn, Set<String> seen, int depth) {
        String text = expression.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            String content = text.startsWith("\"\"\"") && text.length() >= 6 ? text.substring(3, text.length() - 3)
                    : text.substring(1, text.length() - 1);
            return unescape(content);
        }
        if (text.length() >= 2 && text.charAt(0) == '(' && text.charAt(text.length() - 1) == ')') {
            return single(text.substring(1, text.length() - 1), context, ownerFqn, seen, depth);
        }
        int dot = text.lastIndexOf('.');
        if (dot < 0) {
            if (ownerFqn != null) {
                String sibling = resolveInHierarchy(ownerFqn, text, seen, depth);
                if (sibling != null) {
                    return sibling;
                }
            }
            return staticallyImported(text, context, seen, depth);
        }
        String qualifier = text.substring(0, dot).trim();
        String field = text.substring(dot + 1).trim();
        String resolved = owner(qualifier, context);
        return resolved == null ? null : resolve(resolved, field, seen, depth);
    }

    /**
     * Folds one constant reference, following chains.
     *
     * @param ownerFqn declaring type
     * @param field field name
     * @param seen constants already being folded, so a cycle terminates
     * @param depth current depth
     * @return the value, or {@code null}
     */
    private String resolve(String ownerFqn, String field, Set<String> seen, int depth) {
        String key = key(ownerFqn, field);
        ConstantRef ref = constants.get(key);
        if (ref == null) {
            // A Kotlin companion object's members are written as Enclosing.CONST, so the value lives
            // one nested type deeper than the qualifier says.
            key = key(ownerFqn + ".Companion", field);
            ref = constants.get(key);
        }
        if (ref == null || !seen.add(key)) {
            return null;
        }
        int separator = key.lastIndexOf(KEY_SEPARATOR);
        String declaring = separator < 0 ? ownerFqn : key.substring(0, separator);
        return evaluate(ref.expression(), ref.context(), declaring, seen, depth + 1);
    }

    /**
     * Looks a constant up on a type and its supertypes.
     *
     * <p>
     * An unqualified name inside a class sees its inherited constants, and a Feign interface that
     * extends a shared contract declares its prefix exactly that way — the implementation class writes
     * {@code @GetMapping(API_PREFIX + /top)} while {@code API_PREFIX} lives on the interface.
     * </p>
     *
     * @param ownerFqn type declaring the annotation
     * @param field constant name as written
     * @param seen constants already being folded
     * @param depth current depth
     * @return the value, or {@code null}
     */
    private String resolveInHierarchy(String ownerFqn, String field, Set<String> seen, int depth) {
        if (superTypes == null) {
            return resolve(ownerFqn, field, seen, depth);
        }
        for (String candidate : superTypes.closureOf(ownerFqn)) {
            String value = resolve(candidate, field, seen, depth);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Turns a qualifier into a fully qualified type name.
     *
     * <p>
     * {@code SwaggerTagConst.Support} is a nested type: the first segment is the class as the file
     * knows it, the rest is nesting. Resolving only the first segment keeps the import rules in one
     * place instead of re-implementing them for dotted qualifiers.
     * </p>
     *
     * @param qualifier text before the final dot
     * @param context file the reference appears in
     * @return fully qualified owner, or {@code null} when it cannot be resolved
     */
    private String owner(String qualifier, CuSummary context) {
        int dot = qualifier.indexOf('.');
        String head = dot < 0 ? qualifier : qualifier.substring(0, dot);
        String tail = dot < 0 ? "" : qualifier.substring(dot);
        Resolution resolution = types.resolve(head, context);
        if (resolution.hasFqn()) {
            return resolution.fqn() + tail;
        }
        // No class by that simple name: the qualifier may already be fully qualified.
        return dot < 0 ? null : qualifier;
    }

    /**
     * Resolves a bare constant name against the file's static imports.
     *
     * @param name constant name as written
     * @param context file the reference appears in
     * @param seen constants already being folded
     * @param depth current depth
     * @return the value, or {@code null}
     */
    private String staticallyImported(String name, CuSummary context, Set<String> seen, int depth) {
        if (context == null) {
            return null;
        }
        for (ImportInfo imp : context.imports()) {
            if (!imp.isStatic() || imp.isWildcard()) {
                continue;
            }
            if (name.equals(imp.memberName())) {
                return resolve(imp.ownerType(), name, seen, depth);
            }
        }
        return null;
    }

    private static String key(String ownerFqn, String field) {
        return ownerFqn + KEY_SEPARATOR + field;
    }

    /**
     * Splits on {@code +} outside strings and brackets.
     *
     * @param text expression as written
     * @return one entry per operand, always at least one
     */
    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < text.length() && text.charAt(i) != quote) {
                    if (text.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                continue;
            }
            if (c == '(' || c == '[' || c == '{' || c == '<') {
                depth++;
            }
            else if (c == ')' || c == ']' || c == '}' || c == '>') {
                depth = Math.max(0, depth - 1);
            }
            else if (c == '+' && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    /**
     * @param raw literal content with the quotes removed
     * @return the characters the JVM would see, with the common escapes applied
     */
    private static String unescape(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                out.append(c);
                continue;
            }
            char next = raw.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case '0' -> out.append('\0');
                case 'u' -> {
                    if (i + 4 < raw.length()) {
                        try {
                            out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                        catch (NumberFormatException e) {
                            out.append('u');
                        }
                    }
                    else {
                        out.append('u');
                    }
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    /**
     * A constant's initialiser together with the file context it was written in.
     *
     * @param expression initialiser as written, quotes included
     * @param context file summary of the declaring type, which supplies the import context needed to
     * fold a reference to another class
     */
    private record ConstantRef(String expression, CuSummary context) {
    }
}
