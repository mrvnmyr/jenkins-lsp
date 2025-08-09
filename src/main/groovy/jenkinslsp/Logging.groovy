package jenkinslsp

/**
 * Centralized logging used by all modules.
 * Keeps the same prefix as your original script: "[groovy-lsp]".
 * Intentionally tiny API to avoid accidental coupling.
 */
class Logging {
    static void log(Object msg) {
        System.err.println("[groovy-lsp] ${String.valueOf(msg)}")
    }

    static void debug(Object msg) {
        // Same channel; distinct helper if you ever want to gate verbosity
        System.err.println("[groovy-lsp] ${String.valueOf(msg)}")
    }
}
