package io.github.springmcp.spi;

import java.util.Objects;

/**
 * A domain level, recoverable failure.
 *
 * <p>
 * This is a <em>checked</em> exception on purpose: a plugin author is forced to decide what
 * to do with a failure at the point where it happens, instead of letting a
 * {@code RuntimeException} bubble up and turn into an opaque {@code INTERNAL_ERROR}.
 * </p>
 *
 * <p>
 * It never reaches the MCP client as an exception. The server converts it into a
 * {@code CallToolResult} with {@code isError=true}, the error code, the message and the hint.
 * </p>
 */
public class ToolFailure extends Exception {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    /** Actionable next step for the caller (usually the LLM). May be {@code null}. */
    private final String hint;

    private ToolFailure(ErrorCode code, String message, String hint, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.hint = hint;
    }

    /**
     * Creates a failure without a hint.
     * @param code category, never {@code null}
     * @param message human readable detail, never {@code null}
     * @return a new failure
     */
    public static ToolFailure of(ErrorCode code, String message) {
        return new ToolFailure(code, message, null, null);
    }

    /**
     * Creates a failure with a hint describing how to fix the call.
     * @param code category, never {@code null}
     * @param message human readable detail, never {@code null}
     * @param hint suggested next step, may be {@code null}
     * @return a new failure
     */
    public static ToolFailure of(ErrorCode code, String message, String hint) {
        return new ToolFailure(code, message, hint, null);
    }

    /**
     * Creates a failure that keeps the original cause for logging.
     * @param code category, never {@code null}
     * @param message human readable detail, never {@code null}
     * @param hint suggested next step, may be {@code null}
     * @param cause underlying exception, may be {@code null}
     * @return a new failure
     */
    public static ToolFailure of(ErrorCode code, String message, String hint, Throwable cause) {
        return new ToolFailure(code, message, hint, cause);
    }

    /** Failure category. */
    public ErrorCode code() {
        return code;
    }

    /** Suggested next step, or {@code null}. */
    public String hint() {
        return hint;
    }

    /**
     * Renders the failure in a form suitable for {@code CallToolResult.content}.
     * @return e.g. {@code "[PATH_NOT_FOUND] 路径不存在: /tmp/x\n提示: 检查 projectRoot 是否为工程根目录"}
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(code.name()).append("] ").append(getMessage());
        if (hint != null && !hint.isBlank()) {
            sb.append('\n').append("提示: ").append(hint);
        }
        return sb.toString();
    }
}
