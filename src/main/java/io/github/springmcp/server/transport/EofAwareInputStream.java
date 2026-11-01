package io.github.springmcp.server.transport;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An input stream that reports end-of-stream once, to whoever needs to shut down.
 *
 * <p>
 * The server parks its main thread on a latch until the JVM receives a signal, which is correct for
 * {@code Ctrl-C} but wrong for the normal end of a session: an MCP client shuts a server down by
 * closing the pipe, not by sending a signal. Without this, every finished session leaves a JVM
 * resident — a few hundred megabytes each, invisible until the machine is out of memory.
 * </p>
 *
 * <p>
 * The wrapper is installed on the stream the transport itself reads, so it observes the real EOF
 * without competing for bytes. Both {@code read()} overloads are covered because
 * {@code BufferedReader} uses the array form.
 * </p>
 */
public final class EofAwareInputStream extends FilterInputStream {

    private final Runnable onEof;

    private final AtomicBoolean fired = new AtomicBoolean();

    /**
     * @param delegate the real stdin, or any stream whose end means the peer is gone
     * @param onEof callback invoked at most once, on the reading thread
     */
    public EofAwareInputStream(InputStream delegate, Runnable onEof) {
        super(delegate);
        this.onEof = onEof;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value < 0) {
            fire();
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int count = super.read(buffer, offset, length);
        if (count < 0) {
            fire();
        }
        return count;
    }

    private void fire() {
        if (fired.compareAndSet(false, true)) {
            onEof.run();
        }
    }
}
