package jenkinslsp

import groovy.json.JsonOutput
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.stmt.*
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.CompilerConfiguration
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.control.messages.SyntaxErrorMessage

import java.util.Collections

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

    private static class FlowResult {
        final boolean alwaysReturns
        final boolean canCompleteNormally
        FlowResult(boolean alwaysReturns, boolean canCompleteNormally) {
            this.alwaysReturns = alwaysReturns
            this.canCompleteNormally = canCompleteNormally
        }
        String toString() {
            return "FlowResult(alwaysReturns=${alwaysReturns}, canCompleteNormally=${canCompleteNormally})"
        }
    }

    static ParseResult parseGroovy(String sourceText) {
        Logging.debug("Parsing Groovy source: ${sourceText}")

        // --- PATCH: make parser tolerant to trailing '.' so AST is still built ---
        // We create a *patched* copy of the text for parsing only (keeps original
        // text for position math and logging). This mirrors the trailing-dot
        // diagnostic heuristic in LspServer, but additionally guarantees unit.AST.
        String effectiveText = sourceText ?: ""
        try {
            List<String> lines = effectiveText.readLines()
            int idx = lines.size() - 1
            while (idx >= 0 && (lines[idx]?.trim()?.length() ?: 0) == 0) idx--
            boolean trailingDot = (idx >= 0) && (lines[idx]?.trim()?.endsWith("."))
            if (trailingDot) {
                Logging.debug("Parser: detected trailing '.' at line ${idx}; patching for AST build.")
                // Append a harmless identifier after the '.' so the source remains parseable.
                // Example: 'Bar.' -> 'Bar.__LSP_STUB__'
                lines[idx] = lines[idx] + "__LSP_STUB__"
                effectiveText = lines.join("\n")
            }
        } catch (Throwable t) {
            // Non-fatal; continue with original text if anything goes wrong.
            Logging.log("Parser: trailing-dot patch failed: ${t.class.name}: ${t.message}")
        }
        // --- END PATCH ---

        def diagnostics = []
        def config = new CompilerConfiguration()
        def loader = new GroovyClassLoader()
        def unit = new SourceUnit("Script.groovy", effectiveText, config, loader, null)
        try {
            unit.parse()
            unit.completePhase()
            unit.nextPhase()
            unit.convert()
            unit.nextPhase()
            unit.nextPhase()
            // Simple return-type check for methods (non-void must return on all paths)
            for (MethodNode method : unit.AST.methods) {
                if (method.returnType?.name != 'void' && !methodHasReturn(method)) {
                    diagnostics << [
                        message: "Method '${method.name}' declares return type '${method.returnType.name}' but does not return anything",
                        line: method.lineNumber - 1,
                        column: 0
                    ]
                    Logging.debug("    DEBUG: missing-return diagnostic emitted for method '${method.name}'")
                } else {
                    Logging.debug("    DEBUG: method '${method.name}' considered as having a return on all paths or being void")
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
        // Return the *original* source text so cursor math & logs remain exact.
        return new ParseResult(unit, sourceText, diagnostics)
    }

    /**
     * Heuristic "all paths return" check for non-void methods.
     * We do a small AST walk that understands straight-line code, if/else
     * and switch with a default block. Everything else is treated
     * conservatively as "may fall through".
     */
    private static boolean methodHasReturn(MethodNode method) {
        if (method == null || method.code == null) {
            Logging.debug("    DEBUG: methodHasReturn(<null>): no code; assuming no guaranteed return")
            return false
        }
        FlowResult r = analyseStatement(method.code)
        Logging.debug("    DEBUG: methodHasReturn(${method.name}): ${r}")
        return r.alwaysReturns
    }

    private static FlowResult analyseStatement(Statement stmt) {
        if (stmt == null) {
            return new FlowResult(false, true)
        }
        // Direct returns / throws
        if (stmt instanceof ReturnStatement) {
            return new FlowResult(true, false)
        }
        if (stmt instanceof ThrowStatement) {
            return new FlowResult(true, false)
        }
        // Block: sequence of statements
        if (stmt instanceof BlockStatement) {
            List<Statement> stmts = ((BlockStatement) stmt).statements ?: Collections.emptyList()
            if (stmts.isEmpty()) {
                return new FlowResult(false, true)
            }
            boolean currentCanReachNext = true
            boolean sawReturn = false
            for (Statement s : stmts) {
                if (!currentCanReachNext) {
                    break
                }
                FlowResult r = analyseStatement(s)
                if (r.alwaysReturns) {
                    sawReturn = true
                    currentCanReachNext = false
                } else if (!r.canCompleteNormally) {
                    // e.g. infinite loop or throw without return
                    currentCanReachNext = false
                } else {
                    // Some path continues past this statement.
                    currentCanReachNext = true
                }
            }
            boolean alwaysReturns = !currentCanReachNext && sawReturn
            boolean canComplete = currentCanReachNext
            return new FlowResult(alwaysReturns, canComplete)
        }
        // If/else: all paths return only when both branches do.
        if (stmt instanceof IfStatement) {
            IfStatement ifs = (IfStatement) stmt
            FlowResult thenRes = analyseStatement(ifs.ifBlock)
            Statement elseStmt = ifs.elseBlock
            if (elseStmt == null || elseStmt instanceof EmptyStatement) {
                // There is a path where the condition is false and execution falls through.
                return new FlowResult(false, true)
            }
            FlowResult elseRes = analyseStatement(elseStmt)
            boolean alwaysReturns = thenRes.alwaysReturns && elseRes.alwaysReturns
            boolean canComplete = thenRes.canCompleteNormally || elseRes.canCompleteNormally
            return new FlowResult(alwaysReturns, canComplete)
        }
        // Switch: approximate by requiring a default and all branches to return.
        if (stmt instanceof SwitchStatement) {
            SwitchStatement sw = (SwitchStatement) stmt
            List<CaseStatement> cases = sw.caseStatements ?: Collections.emptyList()
            Statement defaultStmt = sw.defaultStatement
            boolean hasDefault = !(defaultStmt == null || defaultStmt instanceof EmptyStatement)
            if (!hasDefault) {
                // Without default we can't guarantee coverage of all inputs.
                return new FlowResult(false, true)
            }
            boolean allReturn = true
            for (CaseStatement cs : cases) {
                FlowResult br = analyseStatement(cs.code)
                if (!br.alwaysReturns) {
                    allReturn = false
                    break
                }
            }
            if (allReturn) {
                FlowResult defRes = analyseStatement(defaultStmt)
                allReturn = defRes.alwaysReturns
            }
            if (allReturn) {
                return new FlowResult(true, false)
            } else {
                return new FlowResult(false, true)
            }
        }
        // Try/catch: require try and all catches to always return.
        if (stmt instanceof TryCatchStatement) {
            TryCatchStatement tcs = (TryCatchStatement) stmt
            FlowResult tryRes = analyseStatement(tcs.tryStatement)
            boolean allCatchesReturn = true
            List<CatchStatement> catches = tcs.catchStatements ?: Collections.emptyList()
            for (CatchStatement cs : catches) {
                FlowResult cr = analyseStatement(cs.code)
                if (!cr.alwaysReturns) {
                    allCatchesReturn = false
                    break
                }
            }
            boolean alwaysReturns = tryRes.alwaysReturns && allCatchesReturn
            boolean canComplete = !alwaysReturns
            return new FlowResult(alwaysReturns, canComplete)
        }
        // Synchronized just wraps a body.
        if (stmt instanceof SynchronizedStatement) {
            return analyseStatement(((SynchronizedStatement) stmt).code)
        }
        // Loops are conservatively treated as "may not execute" so they don't
        // contribute to an all-paths-return proof.
        if (stmt instanceof WhileStatement ||
            stmt instanceof DoWhileStatement ||
            stmt instanceof ForStatement) {
            return new FlowResult(false, true)
        }
        // Any other statement (expression, declaration, etc.) is treated as
        // completing normally without forcing a return.
        return new FlowResult(false, true)
    }
}
