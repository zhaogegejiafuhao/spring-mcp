package io.github.springmcp.core;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Locates the fixture projects shipped in {@code src/test/resources/fixtures}.
 *
 * <p>
 * Resolved through the classpath rather than a relative path so the tests behave the same whether
 * they run from the module directory, the reactor root or an IDE.
 * </p>
 */
public final class Fixtures {

    private Fixtures() {
    }

    /**
     * @param name fixture directory name, e.g. {@code demo-web}
     * @return absolute path of the fixture root
     */
    public static Path path(String name) {
        var url = Fixtures.class.getResource("/fixtures/" + name);
        if (url == null) {
            throw new IllegalStateException("fixture not found on classpath: " + name);
        }
        try {
            return Path.of(url.toURI());
        }
        catch (URISyntaxException e) {
            throw new IllegalStateException("fixture path is not a valid URI: " + url, e);
        }
    }

    /**
     * @param name fixture directory name
     * @param relative path inside the fixture
     * @return absolute path of that file
     */
    public static Path file(String name, String relative) {
        return Objects.requireNonNull(path(name).resolve(relative).normalize());
    }
}
