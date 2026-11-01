package io.github.springmcp.spi;

/**
 * One callable tool.
 *
 * <p>
 * Implementations should be stateless or thread-safe: the MCP server may invoke the same
 * provider concurrently.
 * </p>
 *
 * <p>
 * {@link #invoke(ToolInvocation)} deliberately has no {@code throws} clause. Returning
 * {@link ToolOutcome.Failure} is the intended way to report a problem; keeping the signature
 * exception-free makes it impossible to forget the happy/failure split. The server still
 * guards the call site, so even an accidental {@code RuntimeException} is converted into a
 * well-formed error result rather than killing the request.
 * </p>
 */
public interface ToolProvider {

    /**
     * Descriptor including the JSON Schema of the accepted arguments. Called once at
     * registration time and possibly again on {@code tools/list}.
     * @return descriptor, never {@code null}
     */
    ToolDescriptor descriptor();

    /**
     * Runs the tool.
     * @param invocation arguments plus context, never {@code null}
     * @return success or failure, never {@code null}
     */
    ToolOutcome invoke(ToolInvocation invocation);
}
