package jenkinslsp

import org.codehaus.groovy.ast.MethodNode
import java.io.IOException

/**
 * Small helper that indexes Groovy scripts that live inside a Jenkins
 * Shared Library "vars" directory. The index captures the script entry point
 * (preferring the 'call' method when present) and the top-level methods that
 * can be invoked via cross-file references (e.g., foo.helper()).
 */
class VarsSymbolIndex {
    static class VarsFileSymbols {
        final String scriptName
        final File file
        final String uri
        final Map entryPoint        // [line, column]
        final Map<String, Map> methodsByName // method -> [line, column]

        VarsFileSymbols(String scriptName, File file, String uri, Map entryPoint, Map<String, Map> methodsByName) {
            this.scriptName = scriptName
            this.file = file
            this.uri = uri
            this.entryPoint = entryPoint ?: [line: 0, column: 0]
            this.methodsByName = methodsByName ?: [:]
        }
    }

    private final Map<String, VarsFileSymbols> scripts

    private VarsSymbolIndex(Map<String, VarsFileSymbols> scripts) {
        this.scripts = scripts ?: [:]
    }

    VarsFileSymbols getScript(String name) {
        if (!name) return null
        return scripts[name]
    }

    boolean hasScript(String name) {
        return scripts.containsKey(name)
    }

    static VarsSymbolIndex empty() {
        return new VarsSymbolIndex([:])
    }

    static VarsSymbolIndex build(File varsDir, File openFile, String openFileContents) {
        if (!varsDir || !varsDir.isDirectory()) {
            return VarsSymbolIndex.empty()
        }
        File canonicalDir = canonical(varsDir)
        if (!canonicalDir) {
            return VarsSymbolIndex.empty()
        }
        File canonicalOpen = canonical(openFile)
        Map<String, VarsFileSymbols> entries = [:]
        File[] files = canonicalDir.listFiles()
        if (!files) {
            return VarsSymbolIndex.empty()
        }
        files.findAll { it.isFile() && it.name.toLowerCase().endsWith(".groovy") }.each { File f ->
            try {
                String scriptName = stripExtension(f.name)
                if (!scriptName) return
                String text
                File canonicalCurrent = canonical(f)
                if (canonicalOpen && canonicalCurrent && canonicalCurrent.absolutePath == canonicalOpen.absolutePath) {
                    text = openFileContents ?: ""
                } else {
                    text = f.getText("UTF-8")
                }
                List<String> lines = (text ?: "").readLines()
                Parser.ParseResult pr = Parser.parseGroovy(text ?: "")
                Map entryPoint = findEntryPoint(pr, lines)
                Map<String, Map> methodMap = collectTopLevelMethods(pr, lines)
                String uri = f.toURI().toString()
                entries[scriptName] = new VarsFileSymbols(scriptName, f, uri, entryPoint, methodMap)
            } catch (Throwable t) {
                Logging.log("Failed to index vars file '${f?.name}': ${t.class.name}: ${t.message}")
            }
        }
        return new VarsSymbolIndex(entries)
    }

    private static Map findEntryPoint(Parser.ParseResult pr, List<String> lines) {
        Map callMethod = AstNavigator.findTopLevelClassOrMethod(pr?.unit, "call", lines)
        if (callMethod) return callMethod
        for (int i = 0; i < lines.size(); ++i) {
            String text = lines[i]
            if (text && text.trim().length() > 0) {
                int col = Math.max(0, text.indexOf(text.trim()))
                return [line: i, column: col]
            }
        }
        return [line: 0, column: 0]
    }

    private static Map<String, Map> collectTopLevelMethods(Parser.ParseResult pr, List<String> lines) {
        Map<String, Map> res = [:]
        try {
            for (MethodNode method : pr?.unit?.AST?.methods ?: []) {
                String name = method?.name
                if (!name) continue
                int l = (method.lineNumber ?: 1) - 1
                if (l < 0) l = 0
                int c = StringHeuristics.smartVarColumn(lines, l, name)
                if (c < 0) c = 0
                res[name] = [line: l, column: c]
            }
        } catch (Throwable ignore) {}
        return res
    }

    private static File canonical(File f) {
        if (!f) return null
        try {
            return f.getCanonicalFile()
        } catch (IOException ignore) {
            return null
        }
    }

    private static String stripExtension(String name) {
        if (!name) return ""
        int idx = name.lastIndexOf('.')
        return idx >= 0 ? name.substring(0, idx) : name
    }
}
