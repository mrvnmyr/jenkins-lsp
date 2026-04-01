package jenkinslsp.support

/**
 * Fixture class used to verify vars-to-src go-to-definition through imports.
 */
class ImportedSupport {
    // Keep the method simple; the test only needs a stable class reference.
    static String renderLabel(String label) {
        return label?.trim() ?: "fallback"
    }
}
