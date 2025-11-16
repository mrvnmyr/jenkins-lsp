package jenkinslsp

/**
 * Centralized logging used by all modules.
 * Keeps the same prefix as your original script: "[groovy-lsp]".
 * Intentionally tiny API to avoid accidental coupling.
 */
class Logging {
    private static final boolean DEBUG_ENABLED

    static {
        String v = System.getenv("DEBUG")
        DEBUG_ENABLED = (v != null && v.trim().length() > 0 && !"0".equals(v.trim()))
    }

    static void log(Object msg) {
        System.err.println("[groovy-lsp] " + String.valueOf(msg))
    }

    static void debug(Object msg) {
        if (!DEBUG_ENABLED) {
            return
        }
        System.err.println("[groovy-lsp] " + String.valueOf(msg))
    }

    static boolean isDebugEnabled() {
        return DEBUG_ENABLED
    }
}
