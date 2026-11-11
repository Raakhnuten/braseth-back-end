package com.braseth.ecommerce.modules.design.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The file types BRASETH accepts, and how each is recognised.
 *
 * A file is accepted by its content, not by what the browser claims: the
 * first bytes must match the extension (a PNG must start like a PNG). The
 * stored type is ours, taken from the extension once the content agrees.
 */
final class ArtworkTypes {

    record Kind(String mimeType, boolean previewable) {
    }

    static final Map<String, Kind> BY_EXTENSION = Map.of(
            ".png", new Kind("image/png", true),
            ".jpg", new Kind("image/jpeg", true),
            ".jpeg", new Kind("image/jpeg", true),
            ".pdf", new Kind("application/pdf", false),
            ".svg", new Kind("image/svg+xml", false),
            ".ai", new Kind("application/postscript", false),
            ".eps", new Kind("application/postscript", false));

    static final List<String> EXTENSIONS = List.of(".png", ".jpg", ".jpeg", ".pdf", ".svg", ".ai", ".eps");
    static final List<String> MIME_TYPES = List.of(
            "image/png", "image/jpeg", "application/pdf", "image/svg+xml", "application/postscript");

    private ArtworkTypes() {
    }

    static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    /** Whether the file's first bytes are what its extension says. */
    static boolean contentMatches(String extension, byte[] head) {
        return switch (extension) {
            case ".png" -> startsWith(head, 0x89, 'P', 'N', 'G');
            case ".jpg", ".jpeg" -> startsWith(head, 0xFF, 0xD8, 0xFF);
            case ".pdf" -> startsWith(head, '%', 'P', 'D', 'F');
            // Illustrator files are PDF-based (modern) or PostScript (older).
            case ".ai" -> startsWith(head, '%', 'P', 'D', 'F') || startsWith(head, '%', '!', 'P', 'S');
            // EPS: PostScript text, or the binary DOS EPS header.
            case ".eps" -> startsWith(head, '%', '!', 'P', 'S') || startsWith(head, 0xC5, 0xD0, 0xD3, 0xC6);
            case ".svg" -> new String(head, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).contains("<svg");
            default -> false;
        };
    }

    private static boolean startsWith(byte[] head, int... expected) {
        if (head.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((head[i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
