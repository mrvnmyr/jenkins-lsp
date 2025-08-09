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
        def m = (lineText =~ /(\b\w+)\.(\w+)\b/)
        boolean foundAtCursor = false
        int foundStart = -1
        int foundEnd = -1
        String varName = null, memberName = null
        int memberStart = -1
        while (m.find()) {
            int propStart = m.start(2)   // start of member name
            int propEnd = m.end(2)       // end of member name (exclusive)
            if (charNum >= propStart && charNum < propEnd) {
                foundAtCursor = true
                foundStart = m.start()
                foundEnd = m.end()
                memberStart = propStart
                varName = m.group(1)
                memberName = m.group(2)
                break
            }
        }
        if (!foundAtCursor) return null

        boolean isMethodCall = false
        if (lineText.length() > foundEnd && lineText.charAt(foundEnd) == '(') isMethodCall = true
        Logging.log("Detected qualified property/member lookup at cursor: ${varName}.${memberName} (isMethodCall: ${isMethodCall})")

        // Handle "this"
        if (varName == "this") {
            ClassNode classNode = AstNavigator.findClassForLine(unit, lineNum + 1)
            if (classNode) {
                List callArgs = isMethodCall ? StringHeuristics.extractGroovyCallArgKinds(lineText, memberStart + memberName.length() - 1) : null
                def res = AstNavigator.findFieldOrPropertyInHierarchy(classNode, memberName, lines, isMethodCall ? "preferMethod" : "preferField", callArgs)
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
            def topVar = AstNavigator.findTopLevelVariableWithType(varName, lines)
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
            }
        }

        if (type) {
            def classNode = null
            for (cls in unit.AST.classes) {
                if (cls.nameWithoutPackage == type) { classNode = cls; break }
            }
            if (classNode) {
                List callArgs = isMethodCall ? StringHeuristics.extractGroovyCallArgKinds(lineText, memberStart + memberName.length() - 1) : null
                def res = AstNavigator.findFieldOrPropertyInHierarchy(classNode, memberName, lines, isMethodCall ? "preferMethod" : "preferField", callArgs)
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
            Logging.log("No type found for ${varName}, cannot resolve ${varName}.${memberName} as a property/method")
            return [found: false, matchAtCursor: true, debug: "no type for ${varName}"]
        }
    }
}
