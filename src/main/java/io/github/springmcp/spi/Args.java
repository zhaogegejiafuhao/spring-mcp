package io.github.springmcp.spi;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Typed, validating view over raw tool arguments.
 *
 * <p>
 * JSON numbers arrive as {@code Integer}, {@code Long} or {@code Double} depending on size and
 * parser, and clients sometimes send booleans as strings. Doing that coercion by hand in every
 * tool is where argument bugs come from, so it lives here once.
 * </p>
 *
 * <p>
 * Every getter throws {@link ToolFailure} with {@link ErrorCode#INVALID_ARGUMENT} on a shape
 * mismatch, and the message names the offending argument — an LLM reading the error can fix
 * its call immediately.
 * </p>
 */
public final class Args {

    private final Map<String, Object> raw;

    /**
     * @param raw arguments as supplied by the MCP client, may be {@code null}
     */
    public Args(Map<String, Object> raw) {
        this.raw = raw == null ? Map.of() : raw;
    }

    /** @return the underlying immutable map */
    public Map<String, Object> raw() {
        return raw;
    }

    /**
     * @param key argument name
     * @return whether the argument is present and not blank
     */
    public boolean has(String key) {
        Object v = raw.get(key);
        return v != null && !(v instanceof String s && s.isBlank());
    }

    /**
     * Reads a required non-blank string.
     * @param key argument name
     * @return trimmed value
     * @throws ToolFailure when missing or blank
     */
    public String requireString(String key) throws ToolFailure {
        Object v = raw.get(key);
        if (v == null) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "缺少必填参数 '" + key + "'",
                    "请提供非空的 '" + key + "' 字符串");
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "参数 '" + key + "' 不能为空字符串", null);
        }
        return s;
    }

    /**
     * Reads an optional string.
     * @param key argument name
     * @param fallback value returned when absent or blank
     * @return trimmed value or {@code fallback}
     */
    public String optionalString(String key, String fallback) {
        Object v = raw.get(key);
        if (v == null) {
            return fallback;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? fallback : s;
    }

    /**
     * Reads an optional boolean, accepting {@code "true"}/{@code "false"} strings as well.
     * @param key argument name
     * @param fallback value returned when absent
     * @return parsed value or {@code fallback}
     * @throws ToolFailure when the value is neither boolean nor "true"/"false"
     */
    public boolean optionalBoolean(String key, boolean fallback) throws ToolFailure {
        Object v = raw.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s)) {
            return true;
        }
        if ("false".equals(s)) {
            return false;
        }
        throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT,
                "参数 '" + key + "' 必须是布尔值，收到: " + v, null);
    }

    /**
     * Reads an optional integer clamped to a range.
     * @param key argument name
     * @param fallback value returned when absent
     * @param min inclusive lower bound
     * @param max inclusive upper bound
     * @return parsed, clamped value
     * @throws ToolFailure when the value is not a number
     */
    public int optionalInt(String key, int fallback, int min, int max) throws ToolFailure {
        Object v = raw.get(key);
        if (v == null) {
            return fallback;
        }
        long parsed;
        if (v instanceof Number n) {
            parsed = n.longValue();
        }
        else {
            try {
                parsed = Long.parseLong(String.valueOf(v).trim());
            }
            catch (NumberFormatException e) {
                throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT,
                        "参数 '" + key + "' 必须是整数，收到: " + v, null);
            }
        }
        return (int) Math.max(min, Math.min(max, parsed));
    }

    /**
     * Reads an optional array of strings. A single string is accepted and promoted to a
     * one-element list, because clients frequently send one value where an array is expected.
     * @param key argument name
     * @return trimmed, non-blank values; empty list when absent
     * @throws ToolFailure when the value is neither a string nor a collection
     */
    public List<String> optionalStringList(String key) throws ToolFailure {
        Object v = raw.get(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof String s) {
            return s.isBlank() ? List.of() : List.of(s.trim());
        }
        if (v instanceof Collection<?> c) {
            List<String> out = new ArrayList<>(c.size());
            for (Object o : c) {
                if (o != null) {
                    String s = String.valueOf(o).trim();
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                }
            }
            return List.copyOf(out);
        }
        throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT,
                "参数 '" + key + "' 必须是字符串数组，收到: " + v, null);
    }

    /**
     * Reads a required path that must exist and be a directory.
     * @param key argument name
     * @return normalised absolute path
     * @throws ToolFailure when missing, malformed, absent from disk or not a directory
     */
    public Path requireExistingDirectory(String key) throws ToolFailure {
        Path p = toPath(requireString(key), key);
        if (!Files.exists(p)) {
            throw ToolFailure.of(ErrorCode.PATH_NOT_FOUND, "目录不存在: " + p,
                    "请传工程根目录的绝对路径，或检查路径拼写");
        }
        if (!Files.isDirectory(p)) {
            throw ToolFailure.of(ErrorCode.PATH_NOT_DIRECTORY, "不是目录: " + p,
                    "projectRoot 需要指向工程根目录；若目标是单个文件，请用 get-file-dependencies");
        }
        if (!Files.isReadable(p)) {
            throw ToolFailure.of(ErrorCode.PATH_NOT_READABLE, "目录不可读: " + p, null);
        }
        return p.toAbsolutePath().normalize();
    }

    /**
     * Reads an optional path that, when supplied, must exist and be a directory.
     * @param key argument name
     * @param fallback value returned when absent
     * @return path or {@code fallback}
     * @throws ToolFailure when supplied but invalid
     */
    public Path optionalExistingDirectory(String key, Path fallback) throws ToolFailure {
        if (!has(key)) {
            return fallback;
        }
        return requireExistingDirectory(key);
    }

    /**
     * Reads a required path that must exist and be a regular file.
     * @param key argument name
     * @return normalised absolute path
     * @throws ToolFailure when missing, malformed, absent from disk or not a regular file
     */
    public Path requireExistingFile(String key) throws ToolFailure {
        Path p = toPath(requireString(key), key);
        if (!Files.exists(p)) {
            throw ToolFailure.of(ErrorCode.PATH_NOT_FOUND, "文件不存在: " + p, null);
        }
        if (!Files.isRegularFile(p)) {
            throw ToolFailure.of(ErrorCode.PATH_NOT_DIRECTORY, "不是普通文件: " + p,
                    "该参数需要一个文件路径");
        }
        if (!Files.isReadable(p)) {
            throw ToolFailure.of(ErrorCode.PATH_NOT_READABLE, "文件不可读: " + p, null);
        }
        return p.toAbsolutePath().normalize();
    }

    private static Path toPath(String value, String key) throws ToolFailure {
        try {
            return Path.of(value);
        }
        catch (InvalidPathException e) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT,
                    "参数 '" + key + "' 不是合法路径: " + value, "检查是否包含非法字符", e);
        }
    }
}
