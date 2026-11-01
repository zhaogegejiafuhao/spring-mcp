package io.github.springmcp.core.index;

/**
 * What one walk saw of a project's Kotlin half.
 *
 * <p>
 * Kotlin sources are indexed — a controller written in Kotlin shows up in {@code list-controllers}
 * exactly like a Java one — but the coverage is narrower and the caller deserves to know where.
 * This record carries the two numbers that make the boundary visible: how many files were taken in,
 * and how many were dropped for being too large to be hand-written code.
 * </p>
 *
 * @param files number of {@code .kt} files included in the index
 * @param skippedLarge number of {@code .kt} files skipped for exceeding the per-file size cap
 */
public record KotlinUsage(int files, int skippedLarge) {

    /** A project with no Kotlin at all. */
    public static final KotlinUsage NONE = new KotlinUsage(0, 0);
}
