package example

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
    static Map findTopLevelVariableWithType(String word, List<String> lines) {
        if (!word) return null
        for (int i = 0; i < lines.size(); ++i) {
            def m = (lines[i] =~ /\b(def|\w+)\s+${java.util.regex.Pattern.quote(word)}\b/)
            if (m.find()) {
                int c = m.start() + m.group(0).lastIndexOf(word)
                String type = "def"
                String text = lines[i]
                def typeMatch = (text =~ /\b(def|\w+)\s+${java.util.regex.Pattern.quote(word)}\b/)
                if (typeMatch.find()) type = typeMatch.group(1)
                Logging.log("Found top-level variable '${word}' at ${i}:${c} type: ${type}")
                return [line: i, column: c, type: type, word: word]
            }
        }
        return null
    }

    static Map findTopLevelVariable(String word, List<String> lines) {
        if (!word) return null
        for (int i = 0; i < lines.size(); ++i) {
            def m = (lines[i] =~ /\b(def|\w+)\s+${java.util.regex.Pattern.quote(word)}\b/)
            if (m.find()) {
                int c = m.start() + m.group(0).lastIndexOf(word)
                Logging.log("Found top-level variable '${word}' at ${i}:${c}")
                return [line: i, column: c, word: word]
            }
        }
        return null
    }

    static Map findTopLevelClassOrMethod(SourceUnit unit, String word, List<String> lines) {
        if (!word) return null
        for (ClassNode cls in unit.AST.classes) {
            if (cls.nameWithoutPackage == word) {
                int l = cls.lineNumber > 0 ? cls.lineNumber - 1 : 0
                int c = StringHeuristics.smartVarColumn(lines, l, word)
                Logging.log("Found class '${word}' at ${l}:${c}")
                return [line: l, column: c, word: word]
            }
            for (MethodNode method : cls.methods) {
                if (method.name == word) {
                    int l = method.lineNumber > 0 ? method.lineNumber - 1 : 0
                    int c = StringHeuristics.smartVarColumn(lines, l, word)
                    Logging.log("Found class method '${word}' at ${l}:${c}")
                    return [line: l, column: c, word: word]
                }
            }
        }
        for (MethodNode method : unit.AST.methods) {
            if (method.name == word) {
                int l = method.lineNumber > 0 ? method.lineNumber - 1 : 0
                int c = StringHeuristics.smartVarColumn(lines, l, word)
                Logging.log("Found top-level method '${word}' at ${l}:${c}")
                return [line: l, column: c, word: word]
            }
        }
        return null
    }

    // ----------------------------- Context lookup -----------------------------
    static ClassNode findClassForLine(SourceUnit unit, int line) {
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
     */
    static Map findFieldOrPropertyInHierarchy(ClassNode cls, String name, List<String> lines, String mode = "any", List methodArgs = null) {
        def orig = cls
        while (cls != null) {
            Logging.log("Searching for property/method '${name}' in class: ${cls.name} (mode: ${mode}, methodArgs: ${methodArgs})")
            // 1) Properties
            def properties = []
            try { properties = cls.getProperties() ?: [] } catch (Throwable t) {}
            def prop = properties.find { it.name == name }
            if (prop && (mode == "any" || mode == "preferField" || mode == "preferMethod")) {
                def l = prop.field?.lineNumber ?: prop.getterBlock?.lineNumber ?: cls.lineNumber ?: 1
                l = l > 0 ? l - 1 : 0
                def c = StringHeuristics.smartVarColumn(lines, l, name)
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
}
