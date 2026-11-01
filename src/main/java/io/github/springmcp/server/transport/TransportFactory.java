package io.github.springmcp.server.transport;

import io.modelcontextprotocol.spec.McpServerTransportProvider;

/**
 * Abstraction over the MCP transport so the protocol wiring does not care how bytes arrive.
 *
 * <p>
 * Only STDIO is implemented today. Adding Streamable HTTP later means writing one more
 * implementation and one more branch in the launcher — nothing else in the server changes, which is
 * the whole point of keeping this seam.
 * </p>
 */
public interface TransportFactory {

    /**
     * @return short name used in logs, e.g. {@code "stdio"}
     */
    String name();

    /**
     * Creates the transport provider handed to {@code McpServer.sync(...)}.
     * @return a new provider
     */
    McpServerTransportProvider create();

}
