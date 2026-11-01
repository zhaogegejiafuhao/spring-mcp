package io.github.springmcp.spi;

/**
 * Machine readable failure categories.
 *
 * <p>
 * Every failed tool call is reported to the MCP client as {@code isError=true} plus a
 * {@code structuredContent} body carrying the code. Clients (and LLMs) can therefore branch
 * on the code instead of parsing an English message.
 * </p>
 *
 * <p>
 * The set is intentionally small. Add a new constant only when callers would genuinely act
 * differently; otherwise reuse an existing one.
 * </p>
 */
public enum ErrorCode {

    /** A required argument was missing, blank, of the wrong type, or an unknown key was supplied. */
    INVALID_ARGUMENT("参数无效"),

    /** The given path does not exist on disk. */
    PATH_NOT_FOUND("路径不存在"),

    /** A directory was required but a regular file was supplied (or vice versa). */
    PATH_NOT_DIRECTORY("路径不是目录"),

    /** The path exists but could not be read (permissions, locked file, encoding). */
    PATH_NOT_READABLE("路径不可读"),

    /** A single file was expected to be a Java source file but its name does not end with {@code .java}. */
    NOT_A_SOURCE_FILE("不是 Java 源文件"),

    /** The requested class or method could not be located in the analysed project. */
    CLASS_NOT_FOUND("类未找到"),

    /** The given simple name matches several classes; the caller must retry with a fully qualified name. */
    AMBIGUOUS_CLASS("类名有歧义"),

    /** The source index could not be built (I/O failure while walking the project). */
    INDEX_BUILD_FAILED("索引构建失败"),

    /** The project is larger than the configured {@code maxIndexedFiles} limit. */
    INDEX_TOO_LARGE("工程规模超过上限"),

    /** A Java source file could not be parsed at all (no usable partial AST). */
    PARSE_ERROR("源码解析失败"),

    /** A MyBatis XML mapper could not be parsed. */
    XML_PARSE_ERROR("XML 解析失败"),

    /** The analysis did not finish within the configured budget. */
    TIMEOUT("执行超时"),

    /** A plugin threw an unexpected exception. */
    PLUGIN_ERROR("插件执行失败"),

    /** Anything that is a defect in spring-mcp itself. */
    INTERNAL_ERROR("内部错误");

    private final String description;

    ErrorCode(String description) {
        this.description = description;
    }

    /** Short Chinese description used when rendering a human readable error message. */
    public String description() {
        return description;
    }
}
