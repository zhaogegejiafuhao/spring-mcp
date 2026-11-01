package io.github.springmcp.core.cache;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.Instant;
import java.util.StringJoiner;

import io.github.springmcp.core.index.CuSummary;
import io.github.springmcp.core.index.FieldSummary;
import io.github.springmcp.core.index.TypeSummary;
import io.github.springmcp.spi.SpiVersion;

/**
 * On-disk contract of the index cache.
 *
 * <p>
 * Two independent facts have to change before an old cache file may be read:
 * {@link #VERSION} (the shape of what is stored) and {@link #componentFingerprint()} (which fields
 * the stored records have). A guard test compares the fingerprint against the actual record
 * components, so adding a field to {@link CuSummary} fails the build with an instruction to bump
 * {@code VERSION} — instead of silently deserialising a file where that field is missing.
 * </p>
 *
 * <p>
 * The cache lives in {@code core}, never in {@code spi}: the on-disk format must be free to change
 * without touching the plugin ABI, and {@code spi} is not allowed to depend on Jackson at all.
 * </p>
 */
public final class IndexCacheFormat {

    /** Bump whenever the stored record shape or its meaning changes. */
    public static final int VERSION = 3;

    /** File name inside the per-project cache directory. */
    public static final String FILE_NAME = "index.json";

    private IndexCacheFormat() {
    }

    /**
     * Field names of every record written to the cache, in declaration order.
     *
     * <p>
     * Derived by reflection rather than typed by hand, so it is only a real guard if a test compares
     * this string with a literal that a human must update together with {@link #VERSION}.
     * </p>
     *
     * @return a stable description of the serialised record components
     */
    public static String componentFingerprint() {
        StringJoiner joiner = new StringJoiner("|");
        joiner.add(describe(CuSummary.class));
        joiner.add(describe(TypeSummary.class));
        // FieldSummary is nested inside TypeSummary, and its type name alone would not change when a
        // component is added to it — which is exactly how a cache full of half-empty summaries slips
        // through. Describing it here makes the guard cover the nesting.
        joiner.add(describe(FieldSummary.class));
        return joiner.toString();
    }

    private static String describe(Class<?> recordType) {
        StringJoiner joiner = new StringJoiner(",", recordType.getSimpleName() + "(", ")");
        for (var component : recordType.getRecordComponents()) {
            joiner.add(component.getName() + ":" + component.getGenericType().getTypeName());
        }
        return joiner.toString();
    }

    /**
     * Identifies the running build, so a cache written by an older jar is not trusted.
     *
     * <p>
     * Uses the modification time of the jar (or classes directory) the code was loaded from. A rebuilt
     * jar has a new timestamp, which invalidates every cache automatically; without this, upgrading
     * spring-mcp could serve results produced by the previous analyser, and nothing in the fingerprint
     * of the sources would reveal it.
     * </p>
     *
     * @return a short stamp, {@code "unknown"} when the code source cannot be inspected
     */
    public static String codeStamp() {
        try {
            CodeSource source = CuSummary.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return "unknown";
            }
            Path location = Path.of(source.getLocation().toURI());
            long modified = Files.getLastModifiedTime(location).toMillis();
            return location.getFileName() + "@" + Instant.ofEpochMilli(modified);
        }
        catch (Exception e) {
            // A security manager, a weird class loader or a non-file classpath entry: fall back to the
            // version constants, which still catch explicit format changes.
            return "unknown@" + VERSION + "/" + SpiVersion.VERSION;
        }
    }
}
