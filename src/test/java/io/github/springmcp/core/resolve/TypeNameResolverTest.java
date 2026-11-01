package io.github.springmcp.core.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.springmcp.core.index.CuSummary;
import io.github.springmcp.core.index.ImportInfo;
import io.github.springmcp.core.index.TypeKind;
import io.github.springmcp.core.index.TypeSummary;

/**
 * Unit tests for the name resolution order. These run without any file I/O, which keeps the
 * trickiest logic in the project cheap to test.
 */
class TypeNameResolverTest {

    private static TypeSummary type(String fqn) {
        int dot = fqn.lastIndexOf('.');
        String pkg = dot < 0 ? "" : fqn.substring(0, dot);
        String simple = dot < 0 ? fqn : fqn.substring(dot + 1);
        return new TypeSummary(fqn, simple, pkg, TypeKind.CLASS, false, List.of(), List.of(), List.of(), List.of(),
                List.of(), null, 0, 0);
    }

    private static TypeNameResolver resolver(String... fqns) {
        Map<String, TypeSummary> byFqn = new LinkedHashMap<>();
        Map<String, List<TypeSummary>> bySimple = new LinkedHashMap<>();
        for (String fqn : fqns) {
            TypeSummary t = type(fqn);
            byFqn.put(fqn, t);
            bySimple.computeIfAbsent(t.simpleName(), k -> new ArrayList<>()).add(t);
        }
        return new TypeNameResolver(Map.copyOf(byFqn), Map.copyOf(bySimple));
    }

    private static CuSummary context(String pkg, List<ImportInfo> imports) {
        return new CuSummary(null, "", pkg, imports, List.of(), Set.of(), Set.of(), List.of(), 0, 0);
    }

    private static ImportInfo imp(String name) {
        return new ImportInfo(name, false, false, 1);
    }

    @Test
    @DisplayName("fully qualified name wins immediately")
    void fullyQualified() {
        var r = resolver("com.a.Order", "com.b.Order");
        var res = r.resolve("com.a.Order", null);
        assertThat(res.status()).isEqualTo(Resolution.Status.RESOLVED);
        assertThat(res.fqn()).isEqualTo("com.a.Order");
        assertThat(res.rule()).isEqualTo(Resolution.Rule.FULLY_QUALIFIED);
    }

    @Test
    @DisplayName("explicit import beats same package")
    void explicitImportWins() {
        var r = resolver("com.a.Order", "com.b.Order");
        var ctx = context("com.a", List.of(imp("com.b.Order")));
        var res = r.resolve("Order", ctx);
        assertThat(res.fqn()).isEqualTo("com.b.Order");
        assertThat(res.rule()).isEqualTo(Resolution.Rule.EXPLICIT_IMPORT);
    }

    @Test
    @DisplayName("same package is used when nothing is imported")
    void samePackage() {
        var r = resolver("com.a.Order", "com.b.Order");
        var res = r.resolve("Order", context("com.a", List.of()));
        assertThat(res.fqn()).isEqualTo("com.a.Order");
        assertThat(res.rule()).isEqualTo(Resolution.Rule.SAME_PACKAGE);
    }

    @Test
    @DisplayName("wildcard import resolves to the single project type in that package")
    void wildcardImport() {
        var r = resolver("com.a.Order", "com.b.Other");
        var ctx = context("com.z", List.of(new ImportInfo("com.a", false, true, 1)));
        var res = r.resolve("Order", ctx);
        assertThat(res.fqn()).isEqualTo("com.a.Order");
        assertThat(res.rule()).isEqualTo(Resolution.Rule.WILDCARD_IMPORT);
    }

    @Test
    @DisplayName("two wildcard matches are reported as ambiguous, never guessed")
    void wildcardAmbiguous() {
        var r = resolver("com.a.Order", "com.b.Order");
        var ctx = context("com.z", List.of(new ImportInfo("com.a", false, true, 1),
                new ImportInfo("com.b", false, true, 2)));
        var res = r.resolve("Order", ctx);
        assertThat(res.status()).isEqualTo(Resolution.Status.AMBIGUOUS);
        assertThat(res.candidates()).containsExactlyInAnyOrder("com.a.Order", "com.b.Order");
    }

    @Test
    @DisplayName("a globally unique simple name resolves even without an import")
    void globalUnique() {
        var r = resolver("com.a.OnlyOne", "com.b.Other");
        var res = r.resolve("OnlyOne", context("com.z", List.of()));
        assertThat(res.fqn()).isEqualTo("com.a.OnlyOne");
        assertThat(res.rule()).isEqualTo(Resolution.Rule.GLOBAL_UNIQUE);
    }

    @Test
    @DisplayName("an unknown simple name stays unknown")
    void unknown() {
        var r = resolver("com.a.Order");
        var res = r.resolve("Missing", context("com.z", List.of()));
        assertThat(res.status()).isEqualTo(Resolution.Status.UNKNOWN);
        assertThat(res.hasFqn()).isFalse();
    }

    @Test
    @DisplayName("an imported type outside the project is EXTERNAL but still qualified")
    void externalType() {
        var r = resolver("com.a.Order");
        var ctx = context("com.z", List.of(imp("java.util.List")));
        var res = r.resolve("List", ctx);
        assertThat(res.status()).isEqualTo(Resolution.Status.EXTERNAL);
        assertThat(res.fqn()).isEqualTo("java.util.List");
    }

    @Test
    @DisplayName("java.lang names are recognised without an import")
    void javaLang() {
        var r = resolver("com.a.Order");
        var res = r.resolve("String", context("com.z", List.of()));
        assertThat(res.status()).isEqualTo(Resolution.Status.EXTERNAL);
        assertThat(res.fqn()).isEqualTo("java.lang.String");
    }

    @Test
    @DisplayName("generic arguments and arrays are stripped before lookup")
    void stripsGenericsAndArrays() {
        assertThat(TypeNameResolver.stripTypeArguments("List<Order>")).isEqualTo("List");
        assertThat(TypeNameResolver.stripTypeArguments("Order[]")).isEqualTo("Order");
        assertThat(TypeNameResolver.stripTypeArguments("Order...")).isEqualTo("Order");
        assertThat(TypeNameResolver.stripTypeArguments("Map<String, List<Order>>")).isEqualTo("Map");
    }

    @Test
    @DisplayName("generic arguments are extracted positionally")
    void extractsTypeArguments() {
        assertThat(TypeNameResolver.typeArgument("JpaRepository<Order, Long>", 0)).isEqualTo("Order");
        assertThat(TypeNameResolver.typeArgument("JpaRepository<Order, Long>", 1)).isEqualTo("Long");
        assertThat(TypeNameResolver.typeArgument("CrudRepository<Order, Long>", 2)).isNull();
        assertThat(TypeNameResolver.typeArgument("Order", 0)).isNull();
        assertThat(TypeNameResolver.typeArgument("Repo<Map<String, Order>, Long>", 0))
            .isEqualTo("Map<String, Order>");
    }
}
