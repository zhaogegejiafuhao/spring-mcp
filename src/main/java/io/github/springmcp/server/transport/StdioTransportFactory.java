package io.github.springmcp.server.transport;

import java.io.InputStream;
import java.io.OutputStream;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

/**
 * STDIO transport: JSON-RPC over stdin and stdout of the child process the client launched.
 *
 * <p>
 * The stream pair is injected rather than taken from {@code System.in}/{@code System.out}. That is
 * not a stylistic choice: the launcher replaces {@code System.out} with a stderr redirect so that a
 * stray {@code println} in a plugin cannot corrupt the protocol. If this class read
 * {@code System.out} at construction time it would capture the <em>redirected</em> stream and every
 * response would vanish into the log.
 * </p>
 *
 * <p>
 * The injectable streams also make the protocol testable in-process, without spawning a JVM.
 * </p>
 */
public final class StdioTransportFactory implements TransportFactory {

    private final McpJsonMapper jsonMapper;

    private final InputStream in;

    private final OutputStream out;

    /**
     * @param jsonMapper JSON binding, normally the Jackson 2 implementation
     * @param in real stdin
     * @param out real stdout, captured before any redirection
     */
    public StdioTransportFactory(McpJsonMapper jsonMapper, InputStream in, OutputStream out) {
        this.jsonMapper = jsonMapper;
        this.in = in;
        this.out = out;
    }

    @Override
    public String name() {
        return "stdio";
    }

    @Override
    public McpServerTransportProvider create() {
        return new StdioServerTransportProvider(jsonMapper, in, out);
    }
}
