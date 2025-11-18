package jenkinslsp

import groovy.json.JsonOutput
import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.*
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage

import java.util.Collections
import java.util.LinkedHashMap

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
            addMissingArgumentDiagnostics(unit, diagnostics)
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

    private static void addMissingArgumentDiagnostics(SourceUnit unit, List<Map> diagnostics) {
        if (!unit?.AST) return
        Map<String, List<MethodSignature>> scriptSignatures = buildSignatureMap(unit.AST.methods ?: Collections.emptyList())
        if (!scriptSignatures.isEmpty()) {
            MethodCallArityVisitor scriptVisitor = new MethodCallArityVisitor(scriptSignatures, diagnostics)
            try {
                unit.AST.statementBlock?.visit(scriptVisitor)
            } catch (Throwable ignore) {}
            for (MethodNode method : unit.AST.methods ?: Collections.emptyList()) {
                if (method?.isScriptBody()) continue
                try {
                    method.code?.visit(scriptVisitor)
                } catch (Throwable ignore) {}
            }
        }
        for (ClassNode cls : unit.AST.classes ?: Collections.emptyList()) {
            if (cls?.isScript()) continue
            Map<String, List<MethodSignature>> classSignatures = buildSignatureMap(cls?.methods ?: Collections.emptyList())
            if (classSignatures.isEmpty()) continue
            MethodCallArityVisitor classVisitor = new MethodCallArityVisitor(classSignatures, diagnostics)
            for (MethodNode method : cls.methods ?: Collections.emptyList()) {
                try {
                    method.code?.visit(classVisitor)
                } catch (Throwable ignore) {}
            }
        }
    }

    private static Map<String, List<MethodSignature>> buildSignatureMap(List<MethodNode> methods) {
        Map<String, List<MethodSignature>> map = new LinkedHashMap<>()
        for (MethodNode method : methods ?: Collections.emptyList()) {
            if (!method?.name) continue
            int line = method.lineNumber ?: -1
            if (line < 0) continue
            map.computeIfAbsent(method.name) { [] as List<MethodSignature> } << new MethodSignature(method)
        }
        return map
    }

    private static class MethodSignature {
        final int requiredArgs

        MethodSignature(MethodNode node) {
            Parameter[] params = node?.parameters ?: Parameter.EMPTY_ARRAY
            int required = 0
            for (Parameter p : params) {
                if (p == null) continue
                boolean optional = p.hasInitialExpression()
                if (!optional) {
                    required++
                }
            }
            this.requiredArgs = required
        }

        boolean accepts(int actual) {
            return actual >= requiredArgs
        }
    }

    private static class MethodCallArityVisitor extends CodeVisitorSupport {
        private final Map<String, List<MethodSignature>> methodSignatures
        private final List<Map> diagnostics

        MethodCallArityVisitor(Map<String, List<MethodSignature>> methodSignatures, List<Map> diagnostics) {
            this.methodSignatures = methodSignatures ?: Collections.emptyMap()
            this.diagnostics = diagnostics ?: Collections.emptyList()
        }

        @Override
        void visitMethodCallExpression(MethodCallExpression call) {
            try {
                checkCall(call)
            } catch (Throwable ignore) {}
            super.visitMethodCallExpression(call)
        }

        private void checkCall(MethodCallExpression call) {
            if (!call) return
            if (methodSignatures.isEmpty()) return
            if (!isImplicitReceiver(call)) return
            String methodName = call.methodAsString
            if (!methodName) return
            List<MethodSignature> signatures = methodSignatures.get(methodName)
            if (!signatures) return
            int actual = countArguments(call.arguments)
            if (actual < 0) return
            boolean ok = signatures.any { sig -> sig.accepts(actual) }
            if (ok) return
            int required = signatures.collect { it.requiredArgs }.min() ?: 0
            diagnostics << [
                message: buildMessage(methodName, required, actual),
                line: Math.max(0, (call.lineNumber ?: 1) - 1),
                column: Math.max(0, (call.columnNumber ?: 1) - 1)
            ]
        }

        private static int countArguments(Expression args) {
            if (args == null || args == MethodCallExpression.NO_ARGUMENTS) return 0
            if (args instanceof ArgumentListExpression) {
                return ((ArgumentListExpression) args).expressions?.size() ?: 0
            }
            if (args instanceof TupleExpression) {
                return ((TupleExpression) args).expressions?.size() ?: 0
            }
            return 1
        }

        private static boolean isImplicitReceiver(MethodCallExpression call) {
            if (call.isImplicitThis()) return true
            Expression obj = call.objectExpression
            if (obj instanceof VariableExpression) {
                VariableExpression var = (VariableExpression) obj
                if (var.isThisExpression()) return true
                return var.name == "this"
            }
            return false
        }

        private static String buildMessage(String methodName, int required, int actual) {
            String reqText = "${required} argument" + (required == 1 ? "" : "s")
            String actualText = (actual == 1 ? "1 was" : "${actual} were")
            return "Method '${methodName}' requires at least ${reqText} but ${actualText} provided"
        }

    }
}
