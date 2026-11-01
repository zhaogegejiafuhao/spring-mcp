package io.github.springmcp.core.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the on-disk contract.
 *
 * <p>
 * This test exists for one failure mode only: someone adds, removes or retypes a field on
 * {@code CuSummary} and does not bump {@link IndexCacheFormat#VERSION}. Without this check, every
 * existing cache file would deserialize with that field silently missing, and the analysers would
 * answer from half-empty summaries — a wrong answer with no error anywhere.
 * </p>
 */
class IndexCacheFormatTest {

    @Test
    @DisplayName("the serialised record shape matches the declared cache format version")
    void recordShapeMatchesVersion() {
        String expected = "CuSummary(file:java.nio.file.Path,module:java.lang.String,"
                + "packageName:java.lang.String,imports:java.util.List<io.github.springmcp.core.index.ImportInfo>,"
                + "types:java.util.List<io.github.springmcp.core.index.TypeSummary>,"
                + "calledMethodNames:java.util.Set<java.lang.String>,"
                + "referencedSimpleTypeNames:java.util.Set<java.lang.String>,"
                + "problems:java.util.List<java.lang.String>,lastModifiedMillis:long,size:long)"
                + "|TypeSummary(fqn:java.lang.String,simpleName:java.lang.String,packageName:java.lang.String,"
                + "kind:io.github.springmcp.core.index.TypeKind,abstractType:boolean,"
                + "typeParameters:java.util.List<java.lang.String>,"
                + "rawSuperTypes:java.util.List<java.lang.String>,"
                + "annotations:java.util.List<io.github.springmcp.core.index.AnnotationUse>,"
                + "fields:java.util.List<io.github.springmcp.core.index.FieldSummary>,"
                + "methods:java.util.List<io.github.springmcp.core.index.MethodSummary>,file:java.nio.file.Path,"
                + "line:int,column:int)"
                + "|FieldSummary(name:java.lang.String,typeRaw:java.lang.String,"
                + "annotations:java.util.List<io.github.springmcp.core.index.AnnotationUse>,isStatic:boolean,"
                + "constantInitializer:java.lang.String,line:int,column:int)";

        assertThat(IndexCacheFormat.componentFingerprint())
            .as("CuSummary / TypeSummary changed shape: bump IndexCacheFormat.VERSION and update this literal")
            .isEqualTo(expected);
        assertThat(IndexCacheFormat.VERSION).isEqualTo(3);
    }

    @Test
    @DisplayName("the code stamp identifies the build, not a fixed constant")
    void codeStampIsStableWithinOneBuild() {
        String first = IndexCacheFormat.codeStamp();
        String second = IndexCacheFormat.codeStamp();

        assertThat(first).isNotBlank().isEqualTo(second);
        assertThat(IndexCacheFormat.FILE_NAME).isEqualTo("index.json");
    }
}
