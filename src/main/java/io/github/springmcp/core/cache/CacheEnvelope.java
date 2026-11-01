package io.github.springmcp.core.cache;

import java.util.List;

import io.github.springmcp.core.index.CuSummary;
import io.github.springmcp.spi.model.IndexMetadata;

/**
 * What is written to disk for one project.
 *
 * <p>
 * Deliberately not a {@link io.github.springmcp.core.index.SourceIndex}: a snapshot holds analysers,
 * resolvers and a live AST cache, none of which should be serialised. This keeps the persisted part
 * to plain data — the per-file summaries plus the metadata needed to answer exactly like a fresh
 * build would.
 * </p>
 *
 * @param format cache format version, checked before anything else
 * @param codeStamp build stamp of the running jar, see {@link IndexCacheFormat#codeStamp()}
 * @param root absolute project root the file belongs to
 * @param optionsKey canonical form of the build options
 * @param digest digest of the file fingerprints at build time
 * @param fileCount number of files, kept for a cheap sanity check before deserialising the payload
 * @param builtAtEpochMillis when the cached build happened, informational only
 * @param problems parse problems recorded at build time; replayed so warnings stay identical
 * @param warnings index warnings recorded at build time
 * @param summaries the payload
 */
public record CacheEnvelope(int format, String codeStamp, String root, String optionsKey, String digest,
        int fileCount, long builtAtEpochMillis, List<IndexMetadata.ParseProblem> problems, List<String> warnings,
        List<CuSummary> summaries) {

    public CacheEnvelope {
        problems = problems == null ? List.of() : List.copyOf(problems);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        summaries = summaries == null ? List.of() : List.copyOf(summaries);
    }
}
