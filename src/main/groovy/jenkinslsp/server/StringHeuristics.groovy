package example

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
        return idx >= 0 ? idx : 0
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
     * Attempts to heuristically extract method argument "types" from the call line text.
     * Returns a list of string kinds such as ["Map", "Closure"], ["String"], etc.
     */
    static List<String> extractGroovyCallArgKinds(String line, int callNameLastColumnIndex) {
        def argKinds = [] as List<String>
        if (line == null) return argKinds
        int idx = Math.max(0, Math.min(callNameLastColumnIndex + 1, line.length()))
        String afterCall = line.substring(idx)
        int parenIdx = afterCall.indexOf('(')
        if (parenIdx < 0) return argKinds
        String callArgsText = afterCall.substring(parenIdx + 1)
        boolean hasClosure = callArgsText.contains(') {')
        int closeParen = callArgsText.indexOf(')')
        if (closeParen < 0) closeParen = callArgsText.length()
        String argString = callArgsText.substring(0, closeParen)
        if (argString =~ /\w+\s*:/) {
            argKinds << "Map"
        }
        if (argString =~ /"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'/) {
            argKinds << "String"
        }
        if (hasClosure || line =~ /\)\s*\{/) {
            argKinds << "Closure"
        }
        def parts = argString.split(',')
        for (p in parts) {
            p = p.trim()
            if (p == "") continue
            if (p =~ /\w+\s*:/ && !argKinds.contains("Map")) argKinds << "Map"
            else if (p =~ /"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'/ && !argKinds.contains("String")) argKinds << "String"
            else if (p =~ /\{/ && !argKinds.contains("Closure")) argKinds << "Closure"
        }
        Logging.debug("    DEBUG: extractGroovyCallArgKinds => ${argKinds} from line='${line}', idx=${callNameLastColumnIndex}")
        return argKinds
    }
}
