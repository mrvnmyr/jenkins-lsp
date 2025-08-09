package example

import groovy.json.JsonOutput
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.CompilerConfiguration
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.control.messages.SyntaxErrorMessage

/**
 * Parses Groovy source text with Groovy 2.5.x APIs and produces diagnostics.
 * Returns a ParseResult that includes the SourceUnit for later AST queries.
 */
class Parser {
    static class ParseResult {
        final SourceUnit unit
        final String sourceText
        final List<Map> diagnostics
        ParseResult(SourceUnit unit, String sourceText, List<Map> diagnostics) {
            this.unit = unit
            this.sourceText = sourceText
            this.diagnostics = diagnostics
        }
    }

    static ParseResult parseGroovy(String sourceText) {
        Logging.log("Parsing Groovy source: ${sourceText}")
        def diagnostics = []
        def config = new CompilerConfiguration()
        def loader = new GroovyClassLoader()
        def unit = new SourceUnit("Script.groovy", sourceText, config, loader, null)
        try {
            unit.parse()
            unit.completePhase()
            unit.nextPhase()
            unit.convert()
            unit.nextPhase()
            unit.nextPhase()
            // Simple return-type check for methods (non-void must return)
            for (MethodNode method : unit.AST.methods) {
                if (method.returnType?.name != 'void' && !methodHasReturn(method)) {
                    diagnostics << [
                        message: "Method '${method.name}' declares return type '${method.returnType.name}' but does not return anything",
                        line: method.lineNumber - 1,
                        column: 0
                    ]
                }
            }
        } catch (CompilationFailedException e) {
            def messages = e.getErrorCollector().getErrors()
            for (msg in messages) {
                if (msg instanceof SyntaxErrorMessage) {
                    def syntax = msg.getCause()
                    diagnostics << [
                        message: syntax?.getMessage() ?: msg.toString(),
                        line: (syntax?.line ?: 1) - 1,
                        column: (syntax?.startColumn ?: 1) - 1
                    ]
                } else {
                    diagnostics << [
                        message: msg.toString(),
                        line: 0,
                        column: 0
                    ]
                }
            }
        }
        return new ParseResult(unit, sourceText, diagnostics)
    }

    private static boolean methodHasReturn(MethodNode method) {
        def code = method.code?.text ?: ""
        return (code =~ /\breturn\b/).find()
    }
}
