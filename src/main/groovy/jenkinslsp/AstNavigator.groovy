package jenkinslsp

import groovy.json.JsonOutput
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.stmt.*
import org.codehaus.groovy.control.SourceUnit

/**
 * AST-centric utilities: finding classes/methods/fields, collecting locals,
 * and resolving properties/fields/method signatures in a class hierarchy.
 *
 * Note: String/regex helpers (e.g., column guessing, arg-kind extraction)
 * live in StringHeuristics and are only *used* here.
 */
class AstNavigator {

    // ----------------------------- Locals & Parameters -----------------------------
    static Map<String, Map> collectLocalVariables(MethodNode method, List<String> lines) {
        def locals = [:]
        Logging.log("Collecting local variables for method: ${method?.name} [${method?.lineNumber}-${method?.lastLineNumber}]")
        if (method == null) return locals

        for (Parameter p : method.parameters) {
            int l = (p.lineNumber ?: 1) - 1
            int c = StringHeuristics.smartVarColumn(lines, l, p.name)
            if (c < 0) c = 0
            locals[p.name] = [type: (p.type?.name ?: "def"), line: l, column: c, kind: "param"]
            Logging.log("  Found parameter: ${p.name} at ${l}:${c} type: ${p.type?.name}")
        }
        if (method.code instanceof BlockStatement) {
            def prevTypeName = null
            int prevTypeLine = -1
            for (stmt in (method.code as BlockStatement).statements) {
                Logging.log("  Statement type: ${stmt.getClass().name} lines: ${stmt.lineNumber}-${stmt.lastLineNumber}")
                if (stmt instanceof ExpressionStatement) {
                    def expr = stmt.expression
                    if (expr instanceof DeclarationExpression && expr.leftExpression instanceof VariableExpression) {
                        String name = expr.leftExpression.name
                        int l = (stmt.lineNumber ?: 1) - 1
                        int c = StringHeuristics.smartVarColumn(lines, l, name)
                        if (c < 0) {
                            def ml = StringHeuristics.scanMultiLineVar(lines, l, name)
                            if (ml) { l = ml[0] as int; c = ml[1] as int }
                            else { c = 0 }
                        }
                        String typeName = expr.leftExpression.type?.name ?: "def"
                        if (typeName == "java.lang.Object" && prevTypeName && prevTypeLine == l - 1) {
                            typeName = prevTypeName
                            Logging.debug("    DEBUG: multi-line variable ${name}: using prevTypeName ${prevTypeName}")
                        }
                        def lineText = (l >= 0 && l < lines.size()) ? (lines[l]?.trim()) : null
                        if (lineText && lineText.matches(/^([A-Z][A-Za-z0-9_]*)\s*[\\,]?$/)) {
                            prevTypeName = lineText.replaceAll(/[\s\\,]/, "")
                            prevTypeLine = l
                            Logging.debug("    DEBUG: potential multi-line type: ${prevTypeName} at ${l}")
                        }
                        locals[name] = [type: typeName, line: l, column: c, kind: "local"]
                        Logging.log("    Found local variable: ${name} at ${l}:${c} type: ${typeName}")
                    }
                }
            }
        }
        Logging.log("Final locals map: " + JsonOutput.toJson(locals))
        return locals
    }

    // ----------------------------- Top-level helpers -----------------------------
    /**
     * Find a *top-level* variable decl/assignment (optionally capturing its "type"/def).
     * Prefers the **last** top-level occurrence.
     *
     * A line is treated as "top-level" if any of the following holds:
     *  - AST says it's not inside a class/method (for non-script files)
     *  - the brace depth at the start of the line is 0
     *  - the matched identifier starts at column 0 (defensive fallback for odd brace parsing)
     */
    static Map findTopLevelVariableWithType(String word, List<String> lines, SourceUnit unit = null) {
        if (!word) return null
        Map best = null
        def braceDepths = StringHeuristics.computeBraceDepths(lines)
        for (int i = 0; i < lines.size(); ++i) {
            boolean topByAst = (unit == null) ? true : isTopLevelLine(unit, i)
            boolean topByBraces = (braceDepths == null) ? true : (braceDepths[i] == 0)
            String text = lines[i] ?: ""

            // decl with type or 'def'
            def m = (text =~ /\b(def|\w+)\s+${java.util.regex.Pattern.quote(word)}\b/)
            if (m.find()) {
                int c = m.start() + m.group(0).lastIndexOf(word)
                String type = "def"
                def typeMatch = (text =~ /\b(def|\w+)\s+${java.util.regex.Pattern.quote(word)}\b/)
                if (typeMatch.find()) type = typeMatch.group(1)
                boolean allow = topByAst || topByBraces || (c == 0)
                Logging.log("Top-level typed/def decl candidate for '${word}' at line ${i}, col ${c} (topByAst=${topByAst}, depth=${braceDepths ? braceDepths[i] : 'n/a'}, allow=${allow}, type=${type})")
                if (allow) {
                    best = [line: i, column: c, type: type, word: word]
                    continue
                }
            }

            // fallback: plain assignment without 'def' or type (script binding var)
            def mAssign = (text =~ /\b${java.util.regex.Pattern.quote(word)}\b\s*=/)
            if (mAssign.find()) {
                int c = mAssign.start()
                boolean allow = topByAst || topByBraces || (c == 0)
                Logging.log("Top-level assignment candidate for '${word}' at line ${i}, col ${c} (topByAst=${topByAst}, depth=${braceDepths ? braceDepths[i] : 'n/a'}, allow=${allow})")
                if (allow) {
                    best = [line: i, column: c, type: "def", word: word]
                    continue
                }
            }
        }
        if (best != null) {
            Logging.log("Top-level '${word}' picked FINAL candidate (prefer last occurrence): ${best}")
            return best
        }

        // Fallback: if filtered scan found nothing, try a second pass that only
        // requires column==0 OR braceDepth==0 (still prefers last).
        if (unit != null) {
            Logging.log("Top-level '${word}' not found with primary pass; attempting relaxed fallback (braceDepth==0 OR col==0).")
            for (int i = 0; i < lines.size(); ++i) {
                String text = lines[i] ?: ""
                def m = (text =~ /\b(def|\w+)\s+${java.util.regex.Pattern.quote(word)}\b/)
                if (m.find()) {
                    int c = m.start() + m.group(0).lastIndexOf(word)
                    String type = "def"
                    def typeMatch = (text =~ /\b(def|\w+)\s+${java.util.regex.Pattern.quote(word)}\b/)
                    if (typeMatch.find()) type = typeMatch.group(1)
                    boolean allow = (!braceDepths || braceDepths[i] == 0) || (c == 0)
                    if (allow) {
                        best = [line: i, column: c, type: type, word: word]
                        Logging.log("  [fallback] decl/def candidate for '${word}' at ${i} (depth=${braceDepths ? braceDepths[i] : 'n/a'}, col=${c})")
                        continue
                    }
                }
                def mAssign = (text =~ /\b${java.util.regex.Pattern.quote(word)}\b\s*=/)
                if (mAssign.find()) {
                    int c = mAssign.start()
                    boolean allow = (!braceDepths || braceDepths[i] == 0) || (c == 0)
                    if (allow) {
                        best = [line: i, column: c, type: "def", word: word]
                        Logging.log("  [fallback] assignment candidate for '${word}' at ${i} (depth=${braceDepths ? braceDepths[i] : 'n/a'}, col=${c})")
                        continue
                    }
                }
            }
            if (best != null) Logging.log("  [fallback] picked FINAL candidate: ${best}")
            else Logging.log("  [fallback] still not found for '${word}'")
        } else {
            Logging.log("Top-level '${word}' not found (with type or assignment).")
        }
        return best
    }

    /**
     * Same as findTopLevelVariableWithType but without returning a type.
     */
    static Map findTopLevelVariable(String word, List<String> lines, SourceUnit unit = null) {
        if (!word) return null
        Map best = null
        def braceDepths = StringHeuristics.computeBraceDepths(lines)
        for (int i = 0; i < lines.size(); ++i) {
            boolean topByAst = (unit == null) ? true : isTopLevelLine(unit, i)
            boolean topByBraces = (braceDepths == null) ? true : (braceDepths[i] == 0)
            String text = lines[i] ?: ""

            def m = (text =~ /\b(def|\w+)\s+${java.util.regex.Pattern.quote(word)}\b/)
            if (m.find()) {
                int c = m.start() + m.group(0).lastIndexOf(word)
                boolean allow = topByAst || topByBraces || (c == 0)
                Logging.log("Top-level decl candidate for '${word}' at line ${i}, col ${c} (topByAst=${topByAst}, depth=${braceDepths ? braceDepths[i] : 'n/a'}, allow=${allow})")
                if (allow) {
                    best = [line: i, column: c, word: word]
                    continue
                }
            }
            // fallback: detect simple assignment (no type/def)
            def mAssign = (text =~ /\b${java.util.regex.Pattern.quote(word)}\b\s*=/)
            if (mAssign.find()) {
                int c = mAssign.start()
                boolean allow = topByAst || topByBraces || (c == 0)
                Logging.log("Top-level assignment candidate for '${word}' at line ${i}, col ${c} (topByAst=${topByAst}, depth=${braceDepths ? braceDepths[i] : 'n/a'}, allow=${allow})")
                if (allow) {
                    best = [line: i, column: c, word: word]
                    continue
                }
            }
        }
        if (best != null) {
            Logging.log("Top-level '${word}' picked FINAL candidate (prefer last occurrence): ${best}")
            return best
        }
        // Fallback: relaxed pass honoring braceDepth==0 OR col==0 (still prefer last)
        if (unit != null) {
            Logging.log("Top-level '${word}' not found with AST/brace filters; attempting relaxed fallback (no-type, depth==0 OR col==0).")
            for (int i = 0; i < lines.size(); ++i) {
                String text = lines[i] ?: ""
                def m = (text =~ /\b(def|\w+)\s+${java.util.regex.Pattern.quote(word)}\b/)
                if (m.find()) {
                    int c = m.start() + m.group(0).lastIndexOf(word)
                    boolean allow = (!braceDepths || braceDepths[i] == 0) || (c == 0)
                    if (allow) {
                        best = [line: i, column: c, word: word]
                        Logging.log("  [fallback] decl candidate for '${word}' at ${i} (depth=${braceDepths ? braceDepths[i] : 'n/a'}, col=${c})")
                        continue
                    }
                }
                def mAssign = (text =~ /\b${java.util.regex.Pattern.quote(word)}\b\s*=/)
                if (mAssign.find()) {
                    int c = mAssign.start()
                    boolean allow = (!braceDepths || braceDepths[i] == 0) || (c == 0)
                    if (allow) {
                        best = [line: i, column: c, word: word]
                        Logging.log("  [fallback] assignment candidate for '${word}' at ${i} (depth=${braceDepths ? braceDepths[i] : 'n/a'}, col=${c})")
                        continue
                    }
                }
            }
            if (best != null) Logging.log("  [fallback] picked FINAL candidate: ${best}")
            else Logging.log("  [fallback] still not found for '${word}'")
        } else {
            Logging.log("Top-level '${word}' not found.")
        }
        return best
    }

    static Map findTopLevelClassOrMethod(SourceUnit unit, String word, List<String> lines) {
        if (!word) return null
        if (unit == null || unit.AST == null) {
            Logging.log("findTopLevelClassOrMethod: unit or unit.AST is null; returning null for '${word}'")
            return null
        }
        for (ClassNode cls in unit.AST.classes) {
            if (cls.nameWithoutPackage == word) {
                int l = cls.lineNumber > 0 ? cls.lineNumber - 1 : 0
                int c = StringHeuristics.smartVarColumn(lines, l, word)
                if (c < 0) c = 0
                Logging.log("Found class '${word}' at ${l}:${c}")
                return [line: l, column: c, word: word]
            }
            for (MethodNode method : cls.methods) {
                if (method.name == word) {
                    int l = method.lineNumber > 0 ? method.lineNumber - 1 : 0
                    int c = StringHeuristics.smartVarColumn(lines, l, word)
                    if (c < 0) c = 0
                    Logging.log("Found class method '${word}' at ${l}:${c}")
                    return [line: l, column: c, word: word]
                }
            }
        }
        for (MethodNode method : unit.AST.methods) {
            if (method.name == word) {
                int l = method.lineNumber > 0 ? method.lineNumber - 1 : 0
                int c = StringHeuristics.smartVarColumn(lines, l, word)
                if (c < 0) c = 0
                Logging.log("Found top-level method '${word}' at ${l}:${c}")
                return [line: l, column: c, word: word]
            }
        }
        return null
    }

    // ----------------------------- Context lookup -----------------------------
    static ClassNode findClassForLine(SourceUnit unit, int line) {
        if (unit == null || unit.AST == null) {
            Logging.log("findClassForLine: unit or unit.AST is null (line=${line}); returning null")
            return null
        }
        ClassNode found = null
        for (ClassNode cls in unit.AST.classes) {
            if ((cls.lineNumber ?: 1) <= line && (cls.lastLineNumber ?: 1000000) >= line) {
                found = cls
            }
        }
        return found
    }

    static MethodNode findMethodForLine(ClassNode cls, int line) {
        if (!cls) return null
        for (MethodNode m in cls.methods) {
            if ((m.lineNumber ?: 1) <= line && (m.lastLineNumber ?: 1000000) >= line) return m
        }
        return null
    }

    static MethodNode findTopLevelMethodForLine(SourceUnit unit, int line) {
        if (unit == null || unit.AST == null) {
            Logging.log("findTopLevelMethodForLine: unit or unit.AST is null (line=${line}); returning null")
            return null
        }
        for (MethodNode m in unit.AST.methods) {
            if ((m.lineNumber ?: 1) <= line && (m.lastLineNumber ?: 1000000) >= line) return m
        }
        return null
    }

    // ----------------------------- Member/property resolution -----------------------------
    /**
     * Enhanced: Find property, field, or method in the class hierarchy. Includes a scoring/arity
     * heuristic for selecting the best method overload.
     * Returns [type, node, line, column, word] or null
     *
     * NOTE: 'unit' is used to rebind superClass placeholders to actual AST class declarations from this file,
     * which fixes inherited-member lookups like resolving Bar->Foo#foo.
     */
    static Map findFieldOrPropertyInHierarchy(ClassNode cls, String name, List<String> lines, String mode = "any", List methodArgs = null, SourceUnit unit = null) {
        def orig = cls

        // Guard against cycles across Script <-> groovy.lang.Script etc.
        def visited = new HashSet<String>()

        // Helper to map by exact FQN to the SourceUnit's actual class node (if present)
        Closure<ClassNode> rebindToUnit = { ClassNode c ->
            if (!c || !unit || unit.AST == null) return c
            def exact = unit.AST.classes.find { it.name == c.name }
            return exact ?: c
        }

        while (cls != null) {
            cls = rebindToUnit(cls)

            String k = cls?.name ?: "<null>"
            if (visited.contains(k)) {
                Logging.log("      DEBUG: Breaking hierarchy search due to cycle at ${k}")
                break
            }
            visited.add(k)

            Logging.log("Searching for property/method '${name}' in class: ${cls.name} (mode: ${mode}, methodArgs: ${methodArgs})")
            // 1) Properties
            def properties = []
            try { properties = cls.getProperties() ?: [] } catch (Throwable t) {}
            def prop = properties.find { it.name == name }
            if (prop && (mode == "any" || mode == "preferField" || mode == "preferMethod")) {
                def l = prop.field?.lineNumber ?: prop.getterBlock?.lineNumber ?: cls.lineNumber ?: 1
                l = l > 0 ? l - 1 : 0
                def c = StringHeuristics.smartVarColumn(lines, l, name)
                if (c < 0) c = 0
                Logging.log("      DEBUG: found property ${name} at ${l}:${c} [fieldPresent: ${prop.field!=null}]")
                return [type: 'property', node: prop, line: l, column: c, word: name]
            }
            // 2) Fields
            def fields = []
            try { fields = cls.getFields() ?: [] } catch (Throwable t) {}
            def foundField = fields.find { it.name == name }
            if (foundField && (mode == "any" || mode == "preferField")) {
                def l = foundField.lineNumber > 0 ? foundField.lineNumber - 1 : 0
                def c = StringHeuristics.smartVarColumn(lines, l, name)
                if (c < 0) c = 0
                Logging.log("      DEBUG: found field ${name} at ${l}:${c}")
                return [type: 'field', node: foundField, line: l, column: c, word: name]
            }
            // 3) Methods (with argument signature resolution)
            def methods = []
            try { methods = cls.getMethods() ?: [] } catch (Throwable t) {}
            def candidateMethods = methods.findAll { it.name == name }
            if (candidateMethods && (mode == "any" || mode == "preferMethod")) {
                if (!methodArgs) {
                    def foundMethod = candidateMethods[0]
                    def l = foundMethod.lineNumber > 0 ? foundMethod.lineNumber - 1 : 0
                    def c = StringHeuristics.smartVarColumn(lines, l, name)
                    if (c < 0) c = 0
                    Logging.log("      DEBUG: found method ${name} at ${l}:${c}")
                    return [type: 'method', node: foundMethod, line: l, column: c, word: name]
                }
                // Score each candidate
                int bestScore = Integer.MIN_VALUE
                def bestMethod = null
                def bestMatchInfo = null
                for (m in candidateMethods) {
                    def params = (m.parameters as List)
                    def argKinds = methodArgs.collect { a ->
                        if (a instanceof String) return a
                        if (a instanceof Map) return "Map"
                        if (a instanceof Closure) return "Closure"
                        return a?.getClass()?.simpleName ?: "Object"
                    }
                    int reqParamCount = params.count { !it.hasInitialExpression() }
                    int maxParamCount = params.size()
                    int callArgCount = methodArgs.size()
                    boolean arityMatch = (callArgCount >= reqParamCount && callArgCount <= maxParamCount)
                    int score = 0
                    for (int i = 0; i < Math.min(params.size(), callArgCount); ++i) {
                        def p = params[i]
                        def callKind = argKinds[i]
                        def paramType = p.type?.name ?: "Object"
                        if (callKind == paramType || paramType == "java.lang.Object" || paramType == "def") score += 2
                        else if ((paramType.contains("Map") && callKind == "Map") || (paramType.contains("Closure") && callKind == "Closure")) score += 2
                        else if (paramType == "java.lang.String" && callKind == "String") score += 2
                        else score -= 1
                        if (callKind == p.name) score += 1
                    }
                    if (!arityMatch) score -= 10 * Math.abs(callArgCount - params.size())
                    if ((arityMatch && score > bestScore) || (score > bestScore && bestMethod == null)) {
                        bestScore = score
                        bestMethod = m
                        bestMatchInfo = [params: params.collect { it.type?.name }, score: score]
                    }
                }
                if (bestMethod) {
                    def l = bestMethod.lineNumber > 0 ? bestMethod.lineNumber - 1 : 0
                    def c = StringHeuristics.smartVarColumn(lines, l, name)
                    if (c < 0) c = 0
                    Logging.log("      DEBUG: matched method ${name} at ${l}:${c} (params: ${bestMatchInfo.params}, score: ${bestMatchInfo.score})")
                    return [type: 'method', node: bestMethod, line: l, column: c, word: name]
                } else {
                    Logging.log("      DEBUG: No method candidate for ${name} matches provided args: ${methodArgs}")
                }
            }
            // Superclass fallback
            cls = cls.superClass
        }
        Logging.log("Property/Field/Method '${name}' not found in hierarchy for starting class ${orig?.name}")
        return null
    }

    /**
     * Helper: a line index is "top-level" if it's not inside any class or method in the unit.
     */
    private static boolean isTopLevelLine(SourceUnit unit, int zeroBasedLineIndex) {
        if (unit == null || unit.AST == null) return true
        int line = zeroBasedLineIndex + 1 // AST uses 1-based
        // inside any class?
        for (ClassNode cls in unit.AST.classes) {
            int s = (cls.lineNumber ?: Integer.MAX_VALUE)
            int e = (cls.lastLineNumber ?: Integer.MIN_VALUE)
            if (s <= line && line <= e) return false
            for (MethodNode m in cls.methods) {
                int ms = (m.lineNumber ?: Integer.MAX_VALUE)
                int me = (m.lastLineNumber ?: Integer.MIN_VALUE)
                if (ms <= line && line <= me) return false
            }
        }
        // inside any top-level method?
        for (MethodNode m in unit.AST.methods) {
            int ms = (m.lineNumber ?: Integer.MAX_VALUE)
            int me = (m.lastLineNumber ?: Integer.MIN_VALUE)
            if (ms <= line && line <= me) return false
        }
        return true
    }
}
