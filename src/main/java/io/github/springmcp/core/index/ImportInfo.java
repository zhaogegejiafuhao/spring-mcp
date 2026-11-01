package io.github.springmcp.core.index;

/**
 * One {@code import} statement, kept in its raw form.
 *
 * <p>
 * The name is stored exactly as written, including the member part of a static import, because
 * {@code import static com.acme.Util.parse;} is the only place where a bare {@code parse(...)} call
 * can be attributed to a type.
 * </p>
 *
 * @param name imported name as written: {@code java.util.List},
 * {@code com.acme.model} for {@code import com.acme.model.*;},
 * {@code com.acme.Util.parse} for {@code import static com.acme.Util.parse;}
 * @param isStatic whether {@code static} was present
 * @param isWildcard whether the import ends with {@code *}
 * @param line 1-based line of the import statement
 */
public record ImportInfo(String name, boolean isStatic, boolean isWildcard, int line) {

    /**
     * The type part of the import: the whole name for normal and wildcard imports, the owning type
     * for a static member import.
     * @return declared type name
     */
    public String ownerType() {
        if (!isStatic || isWildcard) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * The imported member of a static import.
     * @return member name, or {@code null} for non-static imports
     */
    public String memberName() {
        if (!isStatic || isWildcard) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : name;
    }
}
