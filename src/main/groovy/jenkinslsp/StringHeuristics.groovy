package jenkinslsp

/**
 * Heuristics that operate purely on strings/regex, intentionally separated from
 * AST traversal. These are used for column estimation, keyword checks, and
 * argument-kind detection from callsites.
 */
class StringHeuristics {
    static int smartVarColumn(List<String> lines, int line, String name) {
        if (name == null) {
            Logging.debug("    DEBUG: smartVarColumn called with null name, returning 0")
            return 0
        }
        String text = (line >= 0 && line < lines.size()) ? (lines[line] ?: "") : ""
        def patterns = [
            ~/\bdef\s+${java.util.regex.Pattern.quote(name)}\b/,
            ~/\b\w+\s+${java.util.regex.Pattern.quote(name)}\b/,
            ~/\b\w+\s*<.*?>\s+${java.util.regex.Pattern.quote(name)}\b/,
        ]
        for (p in patterns) {
            def m = p.matcher(text)
            if (m.find()) {
                int idx = m.start() + m.group(0).lastIndexOf(name)
                Logging.debug("    DEBUG: smartVarColumn matched '${name}' in: '${text}' => col ${idx}")
                return idx
            }
        }
        int idx = text.indexOf(name)
        Logging.debug("    DEBUG: smartVarColumn fallback '${name}' in: '${text}' => col ${idx}")
        // IMPORTANT: return -1 when not found so callers can trigger multi-line scanning
        return idx >= 0 ? idx : -1
    }

    /**
     * Scan a few following lines for a variable name when type/name are split across lines.
     * Example: "Float\n what = 90"
     * Returns [line, col] or null.
     */
    static List<Integer> scanMultiLineVar(List<String> lines, int startLine, String name) {
        for (int i = startLine + 1; i < Math.min(lines.size(), startLine + 4); ++i) {
            String txt = lines[i]
            if (!txt) continue
            def m = (txt =~ /(^\s*)${java.util.regex.Pattern.quote(name)}\b/)
            if (m.find()) {
                int col = m.start(0) + m.group(1).length()
                Logging.debug("    DEBUG: scanMultiLineVar: '${name}' found on line ${i} col ${col}: '${txt}'")
                return [i, col]
            }
            Logging.debug("    DEBUG: scanMultiLineVar: '${name}' NOT on line ${i}: '${txt}'")
        }
        return null
    }

    static boolean isGroovyKeyword(String w) {
        if (!w) return false
        def kws = [
            'as','assert','break','case','catch','class','const','continue','def','default','do','else','enum','extends','false','final','finally','for','goto','if','implements','import','in','instanceof','interface','new','null','package','private','protected','public','return','static','super','switch','this','throw','throws','trait','true','try','var','void','while'
        ] as Set
        return kws.contains(w)
    }

    /** Is the position inside a double-quoted string on this line? */
    static boolean isInsideDoubleQuotedString(String line, int pos) {
        boolean inside = false
        boolean escape = false
        for (int i = 0; i < Math.min(pos, (line?.length() ?: 0)); i++) {
            char ch = line.charAt(i)
            if (escape) { escape = false; continue }
            if (ch == '\\') { escape = true; continue }
            if (ch == '"') inside = !inside
        }
        return inside
    }

    /** Is the position inside a ${...} placeholder on this line? */
    static boolean isInsideGStringPlaceholder(String line, int pos) {
        if (line == null) return false
        int open = line.lastIndexOf('\u0024{', Math.min(pos, line.length())) // '${'
        if (open < 0) open = line.lastIndexOf('${', Math.min(pos, line.length()))
        if (open < 0) return false
        int close = line.indexOf('}', open)
        if (close < 0) return false
        return pos > open && pos < close
    }

    /**
     * If cursor is inside a $var (no braces) within a GString, return the var name.
     * Otherwise return null.
     *
     * NOTE: Treat the '$' itself as a valid hit location to be more forgiving.
     */
    static String gstringVarAt(String line, int pos) {
        if (line == null) return null
        def m = (line =~ /\$[A-Za-z_][A-Za-z0-9_]*/)
        while (m.find()) {
            int start = m.start()
            int end = m.end()
            // accept when cursor is on the '$' or anywhere within the identifier
            if (pos >= start && pos <= end) {
                String var = line.substring(start + 1, end)
                Logging.debug("    DEBUG: gstringVarAt matched \$${var} at range ${start}-${end}, pos=${pos}")
                return var
            }
        }
        return null
    }

    /**
     * Attempts to heuristically extract method argument "types" from the call line text.
     * Returns a list of string kinds such as ["Map", "Closure"], ["String"], etc.
     *
     * Rules (kept simple for our current tests):
     *  - Count **Map** if there is at least one `key: value` pair inside the parentheses.
     *  - Count **Closure** ONLY if there is a trailing closure after `)` or if a positional
     *    closure (a `{...}` argument) appears as its own argument (i.e., not a map value).
     *  - Count **String** only for positional string arguments (not for map values).
     */
    static List<String> extractGroovyCallArgKinds(String line, int callNameLastColumnIndex) {
        def argKinds = [] as List<String>
        if (line == null) return argKinds
        int idx = Math.max(0, Math.min(callNameLastColumnIndex + 1, line.length()))
        String afterCall = line.substring(idx)
        int parenIdx = afterCall.indexOf('(')
        if (parenIdx < 0) return argKinds
        String callArgsText = afterCall.substring(parenIdx + 1)
        boolean hasTrailingClosure = callArgsText.contains(') {') || (line =~ /\)\s*\{/)
        int closeParen = callArgsText.indexOf(')')
        if (closeParen < 0) closeParen = callArgsText.length()
        String argString = callArgsText.substring(0, closeParen)

        boolean sawMap = false

        // naive split is fine for our controlled test cases
        def parts = argString.split(',')
        for (String raw in parts) {
            String p = raw.trim()
            if (p == "") continue
            boolean isMapEntry = (p =~ /^\s*\w+\s*:/).find()
            if (isMapEntry) {
                sawMap = true
                // Map values may contain strings/closures; we must NOT treat them as positional args
                continue
            }
            // positional closure argument (NOT a map value)
            if (!isMapEntry && p.contains("{")) {
                argKinds << "Closure"
                continue
            }
            // positional string
            if (p =~ /"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'/) {
                argKinds << "String"
                continue
            }
            // fallback positional object
            if (p) argKinds << "Object"
        }

        if (sawMap) argKinds.add(0, "Map") // ensure Map is first if present
        if (hasTrailingClosure) argKinds << "Closure"

        Logging.debug("    DEBUG: extractGroovyCallArgKinds => ${argKinds} from line='${line}', idx=${callNameLastColumnIndex}")
        return argKinds
    }

    /**
     * Compute the curly-brace depth at the *start* of each line, ignoring braces
     * inside string literals and single-line comments. Useful to detect lines that
     * are physically at the top-level of a script (depth == 0), even inside DSLs.
     */
    static List<Integer> computeBraceDepths(List<String> lines) {
        def depths = []
        int depth = 0
        boolean inDq = false
        boolean inSq = false
        boolean escape = false

        for (int i = 0; i < (lines?.size() ?: 0); i++) {
            String line = lines[i] ?: ""
            depths << Math.max(depth, 0) // depth at *start* of this line
            for (int j = 0; j < line.length(); j++) {
                char ch = line.charAt(j)
                // handle string escapes only when inside a string
                if (escape) { escape = false; continue }
                if (inDq || inSq) {
                    if (ch == '\\') { escape = true; continue }
                    if (inDq && ch == '"') { inDq = false; continue }
                    if (inSq && ch == '\'') { inSq = false; continue }
                    continue
                }
                // not in a string here
                if (ch == '"') { inDq = true; continue }
                if (ch == '\'') { inSq = true; continue }

                // stop at '//' comments
                if (ch == '/' && j + 1 < line.length() && line.charAt(j + 1) == '/') {
                    break
                }

                if (ch == '{') depth++
                else if (ch == '}') depth = Math.max(0, depth - 1)
            }
        }
        Logging.debug("    DEBUG: computeBraceDepths => ${depths}")
        return depths
    }
}
