package io.github.springmcp.core.cache;

import java.io.IOException;
import java.nio.file.Path;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * Jackson configuration for the cache file.
 *
 * <p>
 * A private mapper rather than the server's: the cache format has to be able to evolve — renaming a
 * field in a tool's JSON output must not invalidate every cache, and a cache field must never leak
 * into what a client sees. The only custom piece is {@link Path}, which Jackson has no default
 * binding for.
 * </p>
 */
final class CacheMapper {

    private CacheMapper() {
    }

    /**
     * @return a mapper that reads and writes {@link Path} as a plain string and tolerates unknown
     * fields, so an older file with fewer fields still loads
     */
    static ObjectMapper create() {
        SimpleModule paths = new SimpleModule("spring-mcp-cache-paths");
        paths.addSerializer(Path.class, new PathSerializer());
        paths.addDeserializer(Path.class, new PathDeserializer());

        return new ObjectMapper()
            .registerModule(paths)
            // Forward compatibility: a field added by a newer build is ignored by an older one instead
            // of failing the read. The format version and the component fingerprint still decide
            // whether the payload is trustworthy.
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** Writes {@link Path} as its platform string; no normalisation, the values come from the walker. */
    private static final class PathSerializer extends JsonSerializer<Path> {

        @Override
        public void serialize(Path value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeString(value.toString());
        }
    }

    /** Reads a string back into a {@link Path}, letting an invalid value fail the load loudly. */
    private static final class PathDeserializer extends JsonDeserializer<Path> {

        @Override
        public Path deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return Path.of(parser.getText());
        }
    }
}
