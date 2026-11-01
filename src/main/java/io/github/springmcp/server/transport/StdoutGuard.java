package io.github.springmcp.server.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Guards the STDIO protocol channel against accidental stdout writes.
 *
 * <p>
 * Under the STDIO transport, stdout <em>is</em> the JSON-RPC stream. A single {@code
 * System.out.println} anywhere in the process — a plugin's debug output, a library banner, a
 * dependency's version message — interleaves garbage into the stream and the client fails to parse
 * a response. The failure then surfaces far away from its cause and is genuinely painful to debug.
 * </p>
 *
 * <p>
 * So {@code System.out} is replaced with a stream that forwards everything to stderr with a marker,
 * and the real stdout is handed to the transport explicitly. Writes that would have broken the
 * session become a harmless log line.
 * </p>
 */
public final class StdoutGuard {

    private StdoutGuard() {
    }

    /**
     * Installs the guard.
     *
     * <p>
     * Call this once, as the very first statement of {@code main}, and keep the returned stream — it
     * is the only handle to the real stdout.
     * </p>
     *
     * @return the original stdout
     */
    public static PrintStream install() {
        PrintStream realOut = System.out;
        System.setOut(new PrintStream(new StderrForwardingStream(), true, StandardCharsets.UTF_8));
        return realOut;
    }

    /** Stream that prefixes each write and sends it to {@code System.err}. */
    private static final class StderrForwardingStream extends OutputStream {

        @Override
        public void write(int b) {
            System.err.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            String text = new String(b, off, len, StandardCharsets.UTF_8);
            if (!text.isBlank()) {
                System.err.print("[stdout] " + text);
                if (!text.endsWith("\n")) {
                    System.err.println();
                }
            }
        }

        @Override
        public void flush() {
            System.err.flush();
        }

        @Override
        public void close() throws IOException {
            System.err.flush();
        }
    }
}
