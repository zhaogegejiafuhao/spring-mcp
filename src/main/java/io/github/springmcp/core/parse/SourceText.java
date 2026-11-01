package io.github.springmcp.core.parse;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Reads a source file as text, coping with the encodings a real project contains.
 *
 * <p>
 * Java source is supposed to be UTF-8 since Java 18, but code written before that — and code written
 * in China on a Windows machine — is very often GBK. A strict UTF-8 read of such a file throws, and
 * the old behaviour turned that into "this file has a problem", which for one project in the
 * regression corpus meant 57 of its 60 files silently left the index. A tool that answers "no
 * endpoints" because it could not read the files is worse than one that admits the encoding.
 * </p>
 *
 * <p>
 * So: strict UTF-8 first; if that fails, strict GB18030 (a superset of GBK, which is what those
 * files actually are); if even that fails, UTF-8 with replacement, flagged as lossy. The chosen
 * encoding travels with the text so the index can report how many files needed a fallback instead of
 * quietly doing it.
 * </p>
 *
 * @param text decoded content, byte order mark removed
 * @param encoding name of the encoding that decoded it, {@code "UTF-8"} in the normal case
 * @param lossy whether characters had to be replaced, which means the text is not exactly the file
 */
public record SourceText(String text, String encoding, boolean lossy) {

    /** Encoding assumed for the overwhelming majority of files. */
    public static final String UTF_8 = "UTF-8";

    private static final String GB18030 = "GB18030";

    /**
     * @param text decoded text
     * @return whether the file was not plain UTF-8, so the caller can report the deviation
     */
    public boolean nonUtf8() {
        return lossy || !UTF_8.equals(encoding);
    }

    /**
     * Reads a file, choosing an encoding by trying them in order.
     *
     * @param file source file
     * @return decoded text plus the encoding used, never {@code null}
     * @throws IOException when the file cannot be read at all
     */
    public static SourceText read(Path file) throws IOException {
        return decode(Files.readAllBytes(file));
    }

    /**
     * Decodes bytes, honouring a byte order mark when there is one.
     *
     * @param bytes file content
     * @return decoded text plus the encoding used, never {@code null}
     */
    public static SourceText decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new SourceText("", UTF_8, false);
        }
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            SourceText rest = decodeWithoutBom(bytes, 3);
            return new SourceText(rest.text(), rest.encoding(), rest.lossy());
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return strict(bytes, 2, StandardCharsets.UTF_16LE, "UTF-16LE");
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return strict(bytes, 2, StandardCharsets.UTF_16BE, "UTF-16BE");
        }
        return decodeWithoutBom(bytes, 0);
    }

    private static SourceText decodeWithoutBom(byte[] bytes, int offset) {
        SourceText utf8 = strict(bytes, offset, StandardCharsets.UTF_8, UTF_8);
        if (utf8 != null) {
            return utf8;
        }
        // GB18030 covers GBK and GB2312; a file that decodes cleanly as it is almost certainly one of
        // those, and treating it as such is a better answer than dropping the file.
        try {
            SourceText gb = strict(bytes, offset, Charset.forName(GB18030), GB18030);
            if (gb != null) {
                return gb;
            }
        }
        catch (RuntimeException e) {
            // Charset not available in this JVM: fall through to the lossy read.
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
            return new SourceText(chars.toString(), UTF_8, true);
        }
        catch (java.nio.charset.CharacterCodingException e) {
            return new SourceText(new String(bytes, offset, bytes.length - offset, StandardCharsets.ISO_8859_1),
                    "ISO-8859-1", true);
        }
    }

    /**
     * @return the strict decode, or {@code null} when the bytes are not valid in that encoding
     */
    private static SourceText strict(byte[] bytes, int offset, Charset charset, String name) {
        CharsetDecoder decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
            return new SourceText(chars.toString(), name, false);
        }
        catch (java.nio.charset.CharacterCodingException e) {
            return null;
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the encoding name in a form suitable for a warning, e.g. {@code "GB18030"}
     */
    public String displayEncoding() {
        return encoding.toUpperCase(Locale.ROOT);
    }
}
