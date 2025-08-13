package jenkinslsp

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.control.SourceUnit

/**
 * Resolves qualified member accesses like `obj.prop` or `obj.method(...)` at the cursor,
 * including support for `this.prop` and local/top-level type inference like
 * `def p = new Foo()` => infer `p` as `Foo`.
 */
class MemberResolver {

    /**
     * Returns a map like:
     *  [found: true, matchAtCursor: true, line: L, column: C, word: memberName, debug: "..."]
     * or null, or {found:false,matchAtCursor:true,...} when the pattern is at cursor but couldn't resolve.
     */
    static Map resolveQualifiedProperty(SourceUnit unit, List<String> lines, int lineNum, int charNum, String lineText) {
        // Allow optional whitespace both BEFORE and AFTER the dot so "ctx .  foo" counts.
        def m = (lineText =~ /(\b\w+)\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)\b/)
        boolean foundAtCursor = false
        int foundStart = -1
        int foundEnd = -1
        String varName = null, memberName = null
        int memberStart = -1
        while (m.find()) {
            int propStart = m.start(2)   // start of member name
            int propEnd = m.end(2)       // end of member name (exclusive)

            // index of '.' between qualifier and member; account for optional whitespace before '.'
            int qStart = m.start(1)
            int qEnd   = m.end(1)
            int dotIndex = lineText.indexOf('.', qEnd) // first '.' after qualifier

            // Treat cursor as "on this qualified access" when:
            //   - it's on any character of the member token, OR
            //   - it's exactly on the '.', OR
            //   - it's in the whitespace region between '.' and the member start.
            boolean onMember = (charNum >= propStart && charNum < propEnd)
            boolean onDot = (dotIndex >= 0 && charNum == dotIndex)
            boolean inWsAfterDot = (dotIndex >= 0 && charNum > dotIndex && charNum < propStart)

            if (onMember || onDot || inWsAfterDot) {
                foundAtCursor = true
                foundStart = m.start()
                foundEnd = m.end()
                memberStart = propStart
                varName = m.group(1)
                memberName = m.group(2)
                Logging.log("Qualified pattern at cursor: '${varName}.${memberName}'  dotIndex=${dotIndex} " +
                            "propStart=${propStart} propEnd=${propEnd} charNum=${charNum} " +
                            "flags(onMember=${onMember},onDot=${onDot},inWs=${inWsAfterDot})")
                break
            } else {
                Logging.debug("    DEBUG: skip qualified '${m.group(1)}.${m.group(2)}' for cursor ${charNum} " +
                              "(member ${propStart}-${propEnd}, dot=${dotIndex}, qualifier ${qStart}-${qEnd})")
            }
        }
        if (!foundAtCursor) {
            Logging.debug("    DEBUG: resolveQualifiedProperty: no qualified pattern at cursor on line ${lineNum}")
            return null
        }

        boolean isMethodCall = false
        // consider whitespace between name and '('
        int scan = Math.min(foundEnd, lineText.length())
        while (scan < lineText.length() && Character.isWhitespace(lineText.charAt(scan))) scan++
        if (scan < lineText.length() && lineText.charAt(scan) == '(') isMethodCall = true
        Logging.log("Detected qualified property/member lookup at cursor: ${varName}.${memberName} (isMethodCall: ${isMethodCall})")

        // Handle "this"
        if (varName == "this") {
            ClassNode classNode = AstNavigator.findClassForLine(unit, lineNum + 1)
            if (classNode) {
                List callArgs = isMethodCall ? StringHeuristics.extractGroovyCallArgKinds(lineText, memberStart + memberName.length() - 1) : null
                // use 'any' when not a call so we can still hit methods if user omitted parentheses
                String mode = isMethodCall ? "preferMethod" : "any"
                def res = AstNavigator.findFieldOrPropertyInHierarchy(classNode, memberName, lines, mode, callArgs, unit)
                if (res) {
                    Logging.log("Resolved this.${memberName} to line ${res.line}, column ${res.column}")
                    return [found: true, matchAtCursor: true, debug: "this.${memberName}->${classNode.name}", line: res.line, column: res.column, word: memberName]
                } else {
                    Logging.log("this.${memberName} could not be resolved on class ${classNode.name}")
                    return [found: false, matchAtCursor: true, debug: "this.${memberName} not found"]
                }
            } else {
                Logging.log("this reference but classNode not found")
                return [found: false, matchAtCursor: true, debug: "this but no classNode"]
            }
        }

        // Try to infer type of the qualifier (local variable, top-level var, or new Foo())
        def contextClass = AstNavigator.findClassForLine(unit, lineNum + 1)
        def contextMethod = contextClass ? AstNavigator.findMethodForLine(contextClass, lineNum + 1) : AstNavigator.findTopLevelMethodForLine(unit, lineNum + 1)
        def locals = contextMethod ? AstNavigator.collectLocalVariables(contextMethod, lines) : [:]
        def localInfo = locals[varName]
        def type = localInfo?.type

        // For 'def f = new Foo()', set type to 'Foo'
        if ((type == "java.lang.Object" || type == "def") && localInfo) {
            def assignLine = lines[(localInfo?.line ?: 0)]
            def assignMatch = assignLine =~ /${java.util.regex.Pattern.quote(varName)}\s*=\s*new\s+(\w+)/
            if (assignMatch.find()) {
                type = assignMatch.group(1)
                Logging.log("    Type of ${varName} inferred from assignment: ${type}")
            }
        }

        // Top-level variable type inference if not a local
        if (!type) {
            def topVar = AstNavigator.findTopLevelVariableWithType(varName, lines, unit)
            if (topVar) {
                def assignLine = lines[topVar.line]
                def assignMatch = assignLine =~ /${java.util.regex.Pattern.quote(varName)}\s*=\s*new\s+(\w+)/
                if (assignMatch.find()) {
                    type = assignMatch.group(1)
                    Logging.log("    Type of top-level ${varName} inferred from assignment: ${type}")
                } else {
                    type = topVar.type
                    Logging.log("    Type of top-level ${varName} from decl: ${type}")
                }
            } else {
                Logging.log("    Top-level variable '${varName}' not found by AST/heuristics; will try dynamic map/property scans.")
            }
        }

        if (type) {
            def classNode = null
            for (cls in unit.AST.classes) {
                if (cls.nameWithoutPackage == type) { classNode = cls; break }
            }
            if (classNode) {
                List callArgs = isMethodCall ? StringHeuristics.extractGroovyCallArgKinds(lineText, memberStart + memberName.length() - 1) : null
                // use 'any' when not a call so we can still return a method symbol
                String mode = isMethodCall ? "preferMethod" : "any"
                def res = AstNavigator.findFieldOrPropertyInHierarchy(classNode, memberName, lines, mode, callArgs, unit)
                if (res) {
                    Logging.log("Resolved ${varName}.${memberName} to line ${res.line}, column ${res.column}")
                    return [found: true, matchAtCursor: true, debug: "${varName}.${memberName}->${type}", line: res.line, column: res.column, word: memberName]
                } else {
                    Logging.log("${varName}.${memberName} could not be resolved on class ${type}")
                    return [found: false, matchAtCursor: true, debug: "${varName}.${memberName} not found in ${type}"]
                }
            } else {
                Logging.log("No classNode found for ${type} (${varName})")
                return [found: false, matchAtCursor: true, debug: "${varName} (${type}) no classNode"]
            }
        } else {
            // --- Map-style & dynamic heuristics ---
            Logging.log("No static type for '${varName}'. Trying map-literal key resolution for '${varName}.${memberName}'")
            def mapKey = resolveMapKeyFromAssignment(unit, lines, varName, memberName)
            if (mapKey) {
                Logging.log("Map-literal key resolution succeeded for ${varName}.${memberName} at ${mapKey.line}:${mapKey.column}")
                return [found: true, matchAtCursor: true, debug: "map ${varName}[${memberName}]", line: mapKey.line, column: mapKey.column, word: memberName]
            }
            Logging.log("Map-literal key resolution failed for ${varName}.${memberName}; trying property assignment scan")
            def propAssign = resolveTopLevelPropertyAssignment(unit, lines, varName, memberName)
            if (propAssign) {
                Logging.log("Property-assignment resolution succeeded for ${varName}.${memberName} at ${propAssign.line}:${propAssign.column}")
                return [found: true, matchAtCursor: true, debug: "assign ${varName}.${memberName}", line: propAssign.line, column: propAssign.column, word: memberName]
            }

            // Final ultra-relaxed fallback: if we still didn't find a key, try locating the
            // last bare top-level `memberName:` that appears within any `varName = [ ... ]`
            // region, even if earlier scans missed due to unusual spacing.
            try {
                def top = AstNavigator.findTopLevelVariable(varName, lines, unit)
                if (top) {
                    // Search a bounded window (next 200 lines) after the assignment for a bare key:
                    int start = Math.max(0, top.line)
                    int end = Math.min(lines.size() - 1, start + 200)
                    int depth = 0
                    boolean seenBracket = false
                    for (int r = start; r <= end; r++) {
                        String txt = lines[r] ?: ""
                        for (int c = 0; c < txt.length(); c++) {
                            char ch = txt.charAt(c)
                            if (ch == '[') { depth++; seenBracket = true }
                            else if (ch == ']') { depth = Math.max(0, depth - 1) }
                            if (seenBracket && depth == 1) {
                                def pat = (txt =~ /(^\s*)(${java.util.regex.Pattern.quote(memberName)})\s*:/)
                                if (pat.find()) {
                                    int col = (pat.start(2))
                                    Logging.log("Relaxed map-key scan matched '${memberName}:' at ${r}:${col}")
                                    return [found: true, matchAtCursor: true, debug: "relaxed map ${varName}[${memberName}]", line: r, column: col, word: memberName]
                                }
                            }
                        }
                        if (seenBracket && depth == 0) break
                    }
                }
            } catch (Throwable t) {
                Logging.log("Relaxed map-key fallback failed: ${t.class.name}: ${t.message}")
            }

            Logging.log("Dynamic resolution failed for ${varName}.${memberName}")
            return [found: false, matchAtCursor: true, debug: "no type for ${varName}"]
        }
    }

    /**
     * Heuristic: find `varName = [ ... key: ... ]` (possibly multi-line) and return the position
     * of `key` within that literal. We prefer the **last** top-level occurrence of the variable.
     */
    private static Map resolveMapKeyFromAssignment(SourceUnit unit, List<String> lines, String varName, String key) {
        if (!lines || !varName || !key) return null

        // Prefer top-level variable occurrence when present
        def top = AstNavigator.findTopLevelVariable(varName, lines, unit)
        if (top) {
            Logging.log("MapKey: inspecting top-level occurrence of '${varName}' at ${top.line}:${top.column}")
            def hit = findKeyInsideMapLiteral(lines, top.line, varName, key)
            if (hit) return hit
        }

        // Fallback: search entire file for assignments of the form `varName = ...`
        Logging.log("MapKey: scanning entire file for '${varName} = [' to resolve key '${key}'")
        for (int i = lines.size() - 1; i >= 0; --i) {
            def ln = lines[i] ?: ""
            def assign = (ln =~ /(^|\b)${java.util.regex.Pattern.quote(varName)}\b\s*=/)
            if (assign.find()) {
                Logging.debug("    DEBUG: MapKey: candidate assignment line ${i}: '${ln.trim()}'")
                def hit = findKeyInsideMapLiteral(lines, i, varName, key)
                if (hit) return hit
            }
        }
        return null
    }

    /**
     * From a candidate assignment line, try to locate a map literal region starting with '[' and
     * search for `key:` (or '"key":' / "'key':") at top level of that literal. Returns [line,col] or null.
     *
     * NOTE: This scanner is quote-aware and will detect keys **anywhere** on the line while
     * depth==1, not only when the key starts at the line's beginning. This fixes cases like:
     *   def ctx = [foo: 1, bar: 2]
     */
    private static Map findKeyInsideMapLiteral(List<String> lines, int startLine, String varName, String key) {
        int i = Math.max(0, Math.min(startLine, lines.size() - 1))
        int j = i
        int eqLine = -1
        int eqCol = -1
        int bracketStartLine = -1
        int bracketStartCol = -1

        // 1) Find '=' token from the assignment line forward
        while (j < lines.size()) {
            String txt = lines[j] ?: ""
            int scanFrom = 0
            if (j == i) {
                // Avoid matching property like x.varName = ; insist on standalone varName before '='
                def m = (txt =~ /(^|\s)${java.util.regex.Pattern.quote(varName)}\b/)
                if (!m.find()) {
                    j++
                    continue
                }
                scanFrom = m.end()
            }
            int eq = txt.indexOf('=', scanFrom)
            if (eq >= 0) { eqLine = j; eqCol = eq; break }
            j++
        }
        if (eqLine < 0) {
            Logging.log("MapKey: no '=' found after ${varName} on/after line ${i}")
            return null
        }
        Logging.log("MapKey: '=' for ${varName} at ${eqLine}:${eqCol}")

        // 2) From after '=', find first '[' starting the literal (may be same or next lines)
        int kLine = eqLine
        int kColFrom = Math.max(0, eqCol + 1)
        int sqLine = -1, sqCol = -1
        while (kLine < lines.size()) {
            String txt = lines[kLine] ?: ""
            int idx = (kLine == eqLine) ? kColFrom : 0
            int cand = txt.indexOf('[', idx)
            if (cand >= 0) { sqLine = kLine; sqCol = cand; break }
            // stop if we hit a ';' or another assignment before finding a '[' on the same line
            if (kLine == eqLine && txt.indexOf(';', kColFrom) >= 0) break
            kLine++
        }
        if (sqLine < 0) {
            Logging.log("MapKey: no '[' map-literal start found after '=' for ${varName} (from line ${eqLine})")
            return null
        }
        bracketStartLine = sqLine
        bracketStartCol = sqCol
        Logging.log("MapKey: found '[' starting map for ${varName} at ${bracketStartLine}:${bracketStartCol}")

        // 3) Walk forward to find matching ']' and track depth to stay at top-level (depth==1)
        int depth = 0
        boolean started = false
        boolean inDq = false
        boolean inSq = false
        boolean escape = false

        for (int r = bracketStartLine; r < lines.size(); r++) {
            String txt = lines[r] ?: ""
            for (int c = (r == bracketStartLine ? bracketStartCol : 0); c < txt.length(); c++) {
                char ch = txt.charAt(c)
                char n1 = (c + 1 < txt.length()) ? txt.charAt(c + 1) : '\u0000'

                // handle quotes for colon/key detection
                if (!escape && !inSq && ch == '"') { inDq = !inDq; continue }
                if (!escape && !inDq && ch == '\'') { inSq = !inSq; continue }
                escape = (!escape && ch == '\\')
                if (inDq || inSq) continue

                if (ch == '[') { depth++; started = true; continue }
                if (ch == ']') {
                    depth--
                    if (started && depth <= 0) {
                        Logging.log("MapKey: end ']' for ${varName} at ${r}:${c}")
                        return null
                    }
                    continue
                }

                // At top level of the map (depth==1), look for ... key ... :
                if (started && depth == 1 && ch == ':') {
                    // Scan backward to find the token (quoted or bare) preceding ':'
                    int endIdx = c - 1
                    while (endIdx >= 0 && Character.isWhitespace(txt.charAt(endIdx))) endIdx--
                    if (endIdx < 0) continue

                    int startIdx = endIdx
                    boolean quoted = false
                    if (txt.charAt(startIdx) == '"' || txt.charAt(startIdx) == '\'') {
                        quoted = true
                        char quote = txt.charAt(startIdx)
                        startIdx--
                        while (startIdx >= 0 && txt.charAt(startIdx) != quote) startIdx--
                        if (startIdx >= 0) {
                            // token spans (startIdx+1 .. endIdx-1) when quoted
                            String token = txt.substring(startIdx + 1, endIdx).trim()
                            Logging.log("MapKey: depth1 ':' backscan (quoted) token='${token}' at ${r}:${startIdx + 1}")
                            if (token == key) {
                                int col = startIdx + 1
                                Logging.log("MapKey: matched quoted key '${key}' at ${r}:${col}")
                                return [line: r, column: col]
                            }
                        }
                    } else {
                        while (startIdx >= 0 && (Character.isJavaIdentifierPart(txt.charAt(startIdx)))) startIdx--
                        int tokenStart = startIdx + 1
                        if (tokenStart <= endIdx) {
                            String token = txt.substring(tokenStart, endIdx + 1)
                            Logging.log("MapKey: depth1 ':' backscan (bare) token='${token}' at ${r}:${tokenStart}")
                            if (token == key) {
                                int col = tokenStart
                                Logging.log("MapKey: matched unquoted key '${key}' at ${r}:${col}")
                                return [line: r, column: col]
                            }
                        }
                    }
                    // continue scanning this line
                }

                // --- legacy regex fallback (also works when key is at start of line) ---
                if (started && depth == 1) {
                    def patA = (txt =~ /(^\s*)(${java.util.regex.Pattern.quote(key)})\s*:/)
                    if (patA.find()) {
                        int indentLen = (patA.group(1) ?: "").length()
                        int col = indentLen
                        Logging.log("MapKey: fallback matched unquoted key '${key}' at ${r}:${col}  line='${txt}' (indent=${indentLen})")
                        return [line: r, column: col]
                    }
                    def patB = (txt =~ /(^\s*)["'](${java.util.regex.Pattern.quote(key)})["']\s*:/)
                    if (patB.find()) {
                        int indentLen = (patB.group(1) ?: "").length()
                        int col = indentLen + 1 /*skip opening quote*/
                        Logging.log("MapKey: fallback matched quoted key '${key}' at ${r}:${col}  line='${txt}' (indent=${indentLen})")
                        return [line: r, column: col]
                    }
                }
            }
        }
        Logging.log("MapKey: key '${key}' not found inside map-literal assigned to ${varName} starting at ${bracketStartLine}:${bracketStartCol}")
        return null
    }

    /**
     * Heuristic: find the last (preferably top-level) assignment `var.prop = ...`
     * and return the position of the `prop` token.
     */
    private static Map resolveTopLevelPropertyAssignment(SourceUnit unit, List<String> lines, String varName, String prop) {
        if (!lines || !varName || !prop) return null
        def braceDepths = StringHeuristics.computeBraceDepths(lines)

        Closure<Map> scan = { Closure<Boolean> allow ->
            for (int i = lines.size() - 1; i >= 0; --i) {
                String txt = lines[i] ?: ""
                def rx = (~/(^\s*)\b${java.util.regex.Pattern.quote(varName)}\b\s*\.\s*(${java.util.regex.Pattern.quote(prop)})\b\s*=/)
                def m = rx.matcher(txt)
                if (m.find()) {
                    int col = m.start(2) // start of the property token
                    boolean ok = allow(i, col)
                    Logging.log("PropAssign: matched '${varName}.${prop} = ...' at ${i}:${col} depth=${braceDepths ? braceDepths[i] : 'n/a'} allow=${ok} txt='${txt.trim()}'")
                    if (ok) return [line: i, column: col]
                }
            }
            return null
        }

        // Pass 1: strict top-level (brace depth == 0)
        def res = scan { int i, int c -> braceDepths && braceDepths[i] == 0 }
        if (res) return res

        // Pass 2: anywhere (safety net)
        res = scan { int i, int c -> true }
        return res
    }
}
