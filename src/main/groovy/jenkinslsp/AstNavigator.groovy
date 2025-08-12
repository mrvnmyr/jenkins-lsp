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
     * Strategy (robust for Job DSL / script blocks):
     *   1) Bottom-up scan accepting only lines with braceDepth==0 (true file top-level).
     *   2) If nothing found, bottom-up scan using AST top-level as a fallback.
     *   3) If still nothing, bottom-up scan accepting column==0 as a last resort.
     *   4) Final safety net: bottom-up scan that accepts a valid decl/assignment **anywhere**.
     *
     * Extra guard: if a strict/AST/relaxed pass finds a candidate, we still check if there
     * is a *later* valid decl/assignment anywhere in the file and prefer that, to protect
     * against occasional brace-depth misparsing that can skip the true last global.
     *
     * Also recognizes **multi-line** declarations/assignments like:
     *   String
     *   somedir = "..."
     * or
     *   somedir
     *     = "..."
     *
     * Detailed DEBUG logs show why a candidate was accepted or rejected.
     */
    static Map findTopLevelVariableWithType(String word, List<String> lines, SourceUnit unit = null) {
        if (!word) return null
        def braceDepths = StringHeuristics.computeBraceDepths(lines)

        Closure<Boolean> validDeclEdge = { String text, int afterVarIdx ->
            // Skip constructs like "something name(" or "something name {" which are not var decls
            int j = afterVarIdx
            while (j < (text?.length() ?: 0) && Character.isWhitespace(text.charAt(j))) j++
            char next = (j < (text?.length() ?: 0)) ? text.charAt(j) : '\u0000'
            boolean ok = !(next == '(' || next == '{')
            Logging.debug("    DEBUG: validDeclEdge check afterVarIdx=${afterVarIdx} next='${next}' => ${ok}")
            return ok
        }

        // Multi-line helpers (look around the current line)
        Closure<Map> tryMultilineDeclOrAssignWithType = { int i ->
            String text = lines[i] ?: ""

            // Case A: name alone on a line; previous non-empty line defines a type or 'def'
            def nameOnly = (text =~ /(^\s*)\b${java.util.regex.Pattern.quote(word)}\b\s*[,)]?\s*$/)
            if (nameOnly.find()) {
                int j = i - 1
                while (j >= 0 && (lines[j]?.trim()?.length() ?: 0) == 0) j--
                if (j >= 0) {
                    String prev = lines[j]?.trim() ?: ""
                    def prevType = (prev =~ /^(def|[A-Z][A-Za-z0-9_]*(?:\s*<.*?>)?)\s*[\\,]?\s*$/)
                    if (prevType.find()) {
                        String typeName = prevType.group(1) ?: "def"
                        int col = nameOnly.start(0) + nameOnly.group(1).length()
                        Logging.log("  [scan-ML] matched multi-line decl for '${word}' at ${i}:${col} with prev type '${typeName}' from line ${j}")
                        return [line: i, column: col, type: typeName, word: word]
                    }
                }
            }

            // Case B: name at end of line and next non-empty line starts with '=' (split assignment)
            def nameEnd = (text =~ /(^\s*)\b${java.util.regex.Pattern.quote(word)}\b\s*$/)
            if (nameEnd.find()) {
                int k = i + 1
                while (k < lines.size() && (lines[k]?.trim()?.length() ?: 0) == 0) k++
                if (k < lines.size()) {
                    String next = lines[k]?.trim() ?: ""
                    if (next.startsWith("=")) {
                        int col = nameEnd.start(0) + nameEnd.group(1).length()
                        Logging.log("  [scan-ML] matched split assignment for '${word}' at ${i}:${col} (next line ${k} starts with '=')")
                        return [line: i, column: col, type: "def", word: word]
                    }
                }
            }
            return null
        }

        Closure<Map> bottomUpScan = { Closure<Boolean> allow ->
            for (int i = lines.size() - 1; i >= 0; --i) {
                String text = lines[i] ?: ""

                // --- PRIORITY: start-of-line assignment (guards against property writes like x.somedir=) ---
                def mAssignSOL = (text =~ /(^\s*)\b${java.util.regex.Pattern.quote(word)}\b\s*=/)
                if (mAssignSOL.find()) {
                    int col = mAssignSOL.start(0) + mAssignSOL.group(1).length()
                    boolean ok = allow(i, col)
                    Logging.log("  [scan] assign@SOL '${word}' at ${i}:${col} depth=${braceDepths ? braceDepths[i] : 'n/a'} allow=${ok} text='${text.trim()}'")
                    if (ok) return [line: i, column: col, type: "def", word: word]
                }

                // decl with type or 'def'
                def m = (text =~ /\b(def|\w+)\s+${java.util.regex.Pattern.quote(word)}\b/)
                if (m.find()) {
                    int c = m.start() + m.group(0).lastIndexOf(word)
                    String type = "def"
                    def typeMatch = (text =~ /\b(def|\w+)\s+${java.util.regex.Pattern.quote(word)}\b/)
                    if (typeMatch.find()) type = typeMatch.group(1)
                    boolean okEdge = validDeclEdge(text, m.end())
                    boolean ok = allow(i, c) && okEdge
                    Logging.log("  [scan] decl/def '${word}' at ${i}:${c} type=${type} depth=${braceDepths ? braceDepths[i] : 'n/a'} okEdge=${okEdge} allow=${ok}")
                    if (ok) return [line: i, column: c, type: type, word: word]
                }

                // plain assignment without 'def' or type (script binding var)
                def mAssign = (text =~ /\b${java.util.regex.Pattern.quote(word)}\b\s*=/)
                if (mAssign.find()) {
                    int c = mAssign.start()
                    boolean ok = allow(i, c)
                    Logging.log("  [scan] assign '${word}' at ${i}:${c} depth=${braceDepths ? braceDepths[i] : 'n/a'} allow=${ok}")
                    if (ok) return [line: i, column: c, type: "def", word: word]
                }

                // Multi-line decl/assign heuristics (look around this line)
                def ml = tryMultilineDeclOrAssignWithType(i)
                if (ml) {
                    boolean ok = allow(ml.line as int, ml.column as int)
                    Logging.log("  [scan] multi-line resolution '${word}' at ${ml.line}:${ml.column} type=${ml.type} allow=${ok}")
                    if (ok) return ml
                }
            }
            return null
        }

        Closure<Map> preferLatestAnywhereIfLater = { Map current ->
            Logging.log("  [scan] preferLatestAnywhereIfLater: current=${current}")
            def latest = bottomUpScan { int ii, int cc -> true }
            Logging.log("  [scan] preferLatestAnywhereIfLater: latest-anywhere=${latest}")
            if (latest && current && latest.line > current.line) {
                Logging.log("  [scan] overriding match ${current.line}:${current.column} with later ANYWHERE ${latest.line}:${latest.column} to guard against depth/AST misparse")
                return latest
            }
            return current
        }

        // Pass 1: strict true top-level (brace depth == 0)
        Logging.log("findTopLevelVariableWithType('${word}'): strict depth==0 bottom-up scan")
        def res = bottomUpScan { int i, int c -> braceDepths && braceDepths[i] == 0 }
        if (res != null) {
            res = preferLatestAnywhereIfLater(res)
            Logging.log("Top-level '${word}' resolved by strict depth==0 (post-check applied): ${res}")
            return res
        }

        // Pass 2: AST-based top-level (in case depth parsing is confused by exotic strings)
        Logging.log("findTopLevelVariableWithType('${word}'): AST-top-level bottom-up scan (fallback)")
        res = bottomUpScan { int i, int c -> unit ? isTopLevelLine(unit, i) : true }
        if (res != null) {
            res = preferLatestAnywhereIfLater(res)
            Logging.log("Top-level '${word}' resolved by AST-top-level fallback (post-check applied): ${res}")
            return res
        }

        // Pass 3: relaxed last resort (column==0)
        Logging.log("findTopLevelVariableWithType('${word}'): relaxed col==0 bottom-up scan (last resort)")
        res = bottomUpScan { int i, int c -> c == 0 }
        if (res != null) {
            res = preferLatestAnywhereIfLater(res)
            Logging.log("Top-level '${word}' resolved by relaxed col==0 fallback (post-check applied): ${res}")
            return res
        }

        // Pass 4: final safety net — accept any valid decl/assignment anywhere (prefer last)
        Logging.log("findTopLevelVariableWithType('${word}'): ANYWHERE bottom-up scan (safety net)")
        res = bottomUpScan { int i, int c -> true }
        if (res != null) {
            Logging.log("Top-level '${word}' resolved by ANYWHERE safety-net: ${res}")
            return res
        }

        Logging.log("Top-level '${word}' not found by any pass.")
        return null
    }

    /**
     * Same as findTopLevelVariableWithType but without returning a type.
     * Uses the same 4-pass bottom-up strategy plus the "prefer-latest-anywhere"
     * post-check as a guard against depth/AST misclassification.
     * Also supports multi-line decl/assignment shapes (see above).
     */
    static Map findTopLevelVariable(String word, List<String> lines, SourceUnit unit = null) {
        if (!word) return null
        def braceDepths = StringHeuristics.computeBraceDepths(lines)

        Closure<Boolean> validDeclEdge = { String text, int afterVarIdx ->
            int j = afterVarIdx
            while (j < (text?.length() ?: 0) && Character.isWhitespace(text.charAt(j))) j++
            char next = (j < (text?.length() ?: 0)) ? text.charAt(j) : '\u0000'
            boolean ok = !(next == '(' || next == '{')
            Logging.debug("    DEBUG: validDeclEdge(no-type) check afterVarIdx=${afterVarIdx} next='${next}' => ${ok}")
            return ok
        }

        Closure<Map> tryMultilineDeclOrAssignNoType = { int i ->
            String text = lines[i] ?: ""
            def nameOnly = (text =~ /(^\s*)\b${java.util.regex.Pattern.quote(word)}\b\s*[,)]?\s*$/)
            if (nameOnly.find()) {
                int j = i - 1
                while (j >= 0 && (lines[j]?.trim()?.length() ?: 0) == 0) j--
                if (j >= 0) {
                    String prev = lines[j]?.trim() ?: ""
                    def prevType = (prev =~ /^(def|[A-Z][A-Za-z0-9_]*(?:\s*<.*?>)?)\s*[\\,]?\s*$/)
                    if (prevType.find()) {
                        int col = nameOnly.start(0) + nameOnly.group(1).length()
                        Logging.log("  [scan-ML] (no-type) matched multi-line decl for '${word}' at ${i}:${col} (prev line ${j})")
                        return [line: i, column: col, word: word]
                    }
                }
            }
            def nameEnd = (text =~ /(^\s*)\b${java.util.regex.Pattern.quote(word)}\b\s*$/)
            if (nameEnd.find()) {
                int k = i + 1
                while (k < lines.size() && (lines[k]?.trim()?.length() ?: 0) == 0) k++
                if (k < lines.size()) {
                    String next = lines[k]?.trim() ?: ""
                    if (next.startsWith("=")) {
                        int col = nameEnd.start(0) + nameEnd.group(1).length()
                        Logging.log("  [scan-ML] (no-type) matched split assignment for '${word}' at ${i}:${col} (next line ${k} starts with '=')")
                        return [line: i, column: col, word: word]
                    }
                }
            }
            return null
        }

        Closure<Map> bottomUpScan = { Closure<Boolean> allow ->
            for (int i = lines.size() - 1; i >= 0; --i) {
                String text = lines[i] ?: ""

                // --- PRIORITY: start-of-line assignment to avoid matching x.somedir= ---
                def mAssignSOL = (text =~ /(^\s*)\b${java.util.regex.Pattern.quote(word)}\b\s*=/)
                if (mAssignSOL.find()) {
                    int colSOL = mAssignSOL.start(0) + mAssignSOL.group(1).length()
                    boolean okSOL = allow(i, colSOL)
                    Logging.log("  [scan] (no-type) assign@SOL '${word}' at ${i}:${colSOL} depth=${braceDepths ? braceDepths[i] : 'n/a'} allow=${okSOL} text='${text.trim()}'")
                    if (okSOL) return [line: i, column: colSOL, word: word]
                }

                def m = (text =~ /\b(def|\w+)\s+${java.util.regex.Pattern.quote(word)}\b/)
                if (m.find()) {
                    int c = m.start() + m.group(0).lastIndexOf(word)
                    boolean okEdge = validDeclEdge(text, m.end())
                    boolean ok = allow(i, c) && okEdge
                    Logging.log("  [scan] decl '${word}' at ${i}:${c} depth=${braceDepths ? braceDepths[i] : 'n/a'} okEdge=${okEdge} allow=${ok}")
                    if (ok) return [line: i, column: c, word: word]
                }
                def mAssign = (text =~ /\b${java.util.regex.Pattern.quote(word)}\b\s*=/)
                if (mAssign.find()) {
                    int c = mAssign.start()
                    boolean ok = allow(i, c)
                    Logging.log("  [scan] assign '${word}' at ${i}:${c} depth=${braceDepths ? braceDepths[i] : 'n/a'} allow=${ok}")
                    if (ok) return [line: i, column: c, word: word]
                }

                // Multi-line forms
                def ml = tryMultilineDeclOrAssignNoType(i)
                if (ml) {
                    boolean ok = allow(ml.line as int, ml.column as int)
                    Logging.log("  [scan] (no-type) multi-line resolution '${word}' at ${ml.line}:${ml.column} allow=${ok}")
                    if (ok) return ml
                }
            }
            return null
        }

        Closure<Map> preferLatestAnywhereIfLater = { Map current ->
            Logging.log("  [scan] (no-type) preferLatestAnywhereIfLater: current=${current}")
            def latest = bottomUpScan { int ii, int cc -> true }
            Logging.log("  [scan] (no-type) preferLatestAnywhereIfLater: latest-anywhere=${latest}")
            if (latest && current && latest.line > current.line) {
                Logging.log("  [scan] overriding (no-type) match ${current.line}:${current.column} with later ANYWHERE ${latest.line}:${latest.column} to guard against depth/AST misparse")
                return latest
            }
            return current
        }

        // Pass 1: strict true top-level (brace depth == 0)
        Logging.log("findTopLevelVariable('${word}'): strict depth==0 bottom-up scan")
        def res = bottomUpScan { int i, int c -> braceDepths && braceDepths[i] == 0 }
        if (res != null) {
            res = preferLatestAnywhereIfLater(res)
            Logging.log("Top-level '${word}' (no-type) resolved by strict depth==0 (post-check applied): ${res}")
            return res
        }

        // Pass 2: AST-based top-level
        Logging.log("findTopLevelVariable('${word}'): AST-top-level bottom-up scan (fallback)")
        res = bottomUpScan { int i, int c -> unit ? isTopLevelLine(unit, i) : true }
        if (res != null) {
            res = preferLatestAnywhereIfLater(res)
            Logging.log("Top-level '${word}' (no-type) resolved by AST-top-level fallback (post-check applied): ${res}")
            return res
        }

        // Pass 3: relaxed (column==0)
        Logging.log("findTopLevelVariable('${word}'): relaxed col==0 bottom-up scan (last resort)")
        res = bottomUpScan { int i, int c -> c == 0 }
        if (res != null) {
            res = preferLatestAnywhereIfLater(res)
            Logging.log("Top-level '${word}' (no-type) resolved by relaxed col==0 fallback (post-check applied): ${res}")
            return res
        }

        // Pass 4: final safety net — accept any valid decl/assignment anywhere (prefer last)
        Logging.log("findTopLevelVariable('${word}'): ANYWHERE bottom-up scan (safety net)")
        res = bottomUpScan { int i, int c -> true }
        if (res != null) {
            Logging.log("Top-level '${word}' (no-type) resolved by ANYWHERE safety-net: ${res}")
            return res
        }

        Logging.log("Top-level '${word}' (no-type) not found by any pass.")
        return null
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
