package jenkinslsp

import org.codehaus.groovy.ast.*
import org.codehaus.groovy.control.SourceUnit

/**
 * Very small completion engine focused on qualified member completion.
 * Example supported scenario:
 *   def f = new Foo()
 *   f.<CTRL+SPACE>    -> offers Foo methods/fields/properties
 *   this.<CTRL+SPACE> -> offers members of the enclosing class
 *   Bar.<CTRL+SPACE>  -> treat 'Bar' (a class in this unit) as a qualifier
 */
class CompletionEngine {

    /**
     * Produces LSP CompletionItem maps.
     * Only triggers on a qualified access pattern before the cursor:   <ident>.<prefix?>
     */
    static List<Map> suggest(SourceUnit unit, String sourceText, int lineNum, int charNum) {
        if (unit == null || sourceText == null) return []
        List<String> lines = sourceText.readLines()
        if (lineNum < 0 || lineNum >= lines.size()) return []

        String line = lines[lineNum] ?: ""
        String before = line.substring(0, Math.min(charNum, line.length()))

        def m = (before =~ /(\b\w+)\.\s*([A-Za-z_][A-Za-z0-9_]*)?$/)
        if (!m.find()) {
            // Only provide completions after a qualifier + dot (or when user typed a prefix after the dot)
            return []
        }

        String qualifier = m.group(1)
        String prefix = m.groupCount() >= 2 ? (m.group(2) ?: "") : ""
        int replaceStartCol = charNum - prefix.length()
        int replaceEndCol = charNum

        Logging.log("Completion request on qualifier='${qualifier}', prefix='${prefix}' at ${lineNum}:${charNum}")

        // Determine context class & method (for local variable inference)
        ClassNode contextClass = AstNavigator.findClassForLine(unit, lineNum + 1)
        MethodNode contextMethod = contextClass ? AstNavigator.findMethodForLine(contextClass, lineNum + 1) : AstNavigator.findTopLevelMethodForLine(unit, lineNum + 1)
        Map<String, Map> locals = contextMethod ? AstNavigator.collectLocalVariables(contextMethod, lines) : [:]

        ClassNode targetClass = null

        if ("this".equals(qualifier)) {
            targetClass = contextClass
        } else {
            // Try local variables first
            def localInfo = locals[qualifier]
            String type = localInfo?.type
            if ((type == "java.lang.Object" || type == "def") && localInfo) {
                // try: x = new Foo()
                String declLine = (localInfo.line >= 0 && localInfo.line < lines.size()) ? (lines[localInfo.line] ?: "") : ""
                def assignMatch = (declLine =~ /${java.util.regex.Pattern.quote(qualifier)}\s*=\s*new\s+(\w+)/)
                if (assignMatch.find()) type = assignMatch.group(1)
            }
            if (!type) {
                // top-level variable
                def topVar = AstNavigator.findTopLevelVariableWithType(qualifier, lines, unit)
                if (topVar) {
                    String decl = lines[topVar.line] ?: ""
                    def assignMatch = (decl =~ /${java.util.regex.Pattern.quote(qualifier)}\s*=\s*new\s+(\w+)/)
                    if (assignMatch.find()) type = assignMatch.group(1)
                    else type = topVar.type
                }
            }
            if (type && unit?.AST != null) {
                for (ClassNode c in unit.AST.classes) {
                    if (c.nameWithoutPackage == type) { targetClass = c; break }
                }
            }
        }

        // Heuristic: if still unknown, treat the qualifier itself as a class name declared in this SourceUnit
        if (targetClass == null && unit?.AST != null) {
            for (ClassNode c in unit.AST.classes) {
                if (c.nameWithoutPackage == qualifier) { targetClass = c; break }
            }
            if (targetClass != null) {
                Logging.log("Completion: qualifier '${qualifier}' matched class name; using it for member suggestions.")
            }
        }

        if (targetClass == null) {
            Logging.log("Completion: could not infer class for qualifier '${qualifier}'")
            return []
        }

        String pfxLower = prefix?.toLowerCase() ?: ""
        def items = [] as List<Map>
        def seenNames = new HashSet<String>()

        // Accumulate members through the hierarchy
        def visited = new HashSet<String>()
        Closure<ClassNode> rebindToUnit = { ClassNode c ->
            if (!c || !unit || unit.AST == null) return c
            def exact = unit.AST.classes.find { it.name == c.name }
            return exact ?: c
        }

        ClassNode cls = targetClass
        while (cls != null) {
            cls = rebindToUnit(cls)
            String key = cls?.name ?: "<null>"
            if (visited.contains(key)) break
            visited.add(key)
            Logging.log("Completion: scanning class ${key} for members (prefix='${prefix}')")

            // Methods
            List<MethodNode> methods = []
            try { methods = cls.getMethods() ?: [] } catch (Throwable t) {}
            Map<String, List<MethodNode>> byName = [:].withDefault { [] }
            for (MethodNode mn : methods) {
                String name = mn?.name ?: ""
                if (name == "") continue
                if (pfxLower == "" || name.toLowerCase().startsWith(pfxLower)) {
                    byName[name] << mn
                }
            }
            byName.each { String name, List<MethodNode> overloads ->
                if (seenNames.add(name)) {
                    String sig = buildMethodDetail(overloads, cls)
                    items << completionItem(name + "()", 2 /*Method*/, sig, lineNum, replaceStartCol, replaceEndCol, name)
                }
            }

            // Properties
            List<PropertyNode> props = []
            try { props = cls.getProperties() ?: [] } catch (Throwable t) {}
            for (PropertyNode pn : props) {
                String name = pn?.name ?: ""
                if (name == "") continue
                if (!seenNames.contains(name) && (pfxLower == "" || name.toLowerCase().startsWith(pfxLower))) {
                    seenNames.add(name)
                    String detail = "${cls.nameWithoutPackage} property"
                    items << completionItem(name, 10 /*Property*/, detail, lineNum, replaceStartCol, replaceEndCol, name)
                }
            }

            // Fields
            List<FieldNode> fields = []
            try { fields = cls.getFields() ?: [] } catch (Throwable t) {}
            for (FieldNode fn : fields) {
                String name = fn?.name ?: ""
                if (name == "") continue
                if (!seenNames.contains(name) && (pfxLower == "" || name.toLowerCase().startsWith(pfxLower))) {
                    seenNames.add(name)
                    String detail = "${cls.nameWithoutPackage} field : ${(fn.type?.nameWithoutPackage) ?: 'def'}"
                    items << completionItem(name, 5 /*Field*/, detail, lineNum, replaceStartCol, replaceEndCol, name)
                }
            }

            cls = cls.superClass
        }

        Logging.log("Completion: built ${items.size()} items for qualifier '${qualifier}'")
        return items
    }

    private static Map completionItem(String label, int kind, String detail, int line, int startCol, int endCol, String insertText = null) {
        def text = insertText ?: label
        return [
            label     : label,
            kind      : kind,
            detail    : detail,
            sortText  : String.format("%02d_%s", kind, label),
            insertText: text,
            textEdit  : [
                range  : [
                    start: [ line: line, character: Math.max(0, startCol) ],
                    end  : [ line: line, character: Math.max(Math.max(0, startCol), endCol) ]
                ],
                newText: text
            ]
        ]
    }

    private static String buildMethodDetail(List<MethodNode> overloads, ClassNode owner) {
        if (!overloads) return "${owner?.nameWithoutPackage ?: ''} method"
        def parts = []
        int shown = 0
        for (MethodNode m : overloads) {
            if (shown >= 3) break
            def params = (m.parameters ?: []).collect { it?.type?.nameWithoutPackage ?: 'def' }.join(", ")
            def ret = m?.returnType?.nameWithoutPackage ?: 'def'
            parts << "(${params}) : ${ret}"
            shown++
        }
        String extra = overloads.size() > shown ? " +${overloads.size() - shown} overloads" : ""
        return "${owner?.nameWithoutPackage ?: ''}#${overloads[0].name}${parts ? ' ' + parts.join(' | ') : ''}${extra}"
    }
}
