package jenkinslsp

import groovy.json.JsonOutput
import org.codehaus.groovy.control.SourceUnit

/**
 * Minimal LSP server loop + JSON-RPC handling.
 */
class LspServer {
    private final JsonRpcTransport transport
    private SourceUnit lastParsedUnit
    private String lastSourceText = ""

    LspServer(JsonRpcTransport transport) {
        this.transport = transport
    }

    static void main(String[] args) {
        new LspServer(new JsonRpcTransport()).run()
    }

    void run() {
        while (true) {
            def message = transport.readMessage()
            Logging.log("Received message: " + JsonOutput.toJson(message))
            if (!message) continue
            try {
                switch (message.method) {
                    case 'initialize':
                        transport.sendMessage([
                            jsonrpc: "2.0",
                            id: message.id,
                            result: [
                                capabilities: [
                                    textDocumentSync : 1,
                                    definitionProvider: true,
                                    completionProvider: [
                                        triggerCharacters: ['.'],
                                        resolveProvider  : false
                                    ]
                                ]
                            ]
                        ])
                        break

                    case 'textDocument/didOpen':
                    case 'textDocument/didChange':
                        handleDidOpenOrChange(message)
                        break

                    case 'textDocument/definition':
                        handleDefinition(message)
                        break

                    case 'textDocument/completion':
                        handleCompletion(message)
                        break

                    default:
                        Logging.log("Ignored method: ${message.method}")
                        break
                }
            } catch (Throwable t) {
                Logging.log("FATAL: Exception while handling '${message?.method}': ${t.class.name}: ${t.message}")
                t.printStackTrace(System.err)
                if (message?.id != null) {
                    transport.sendMessage([jsonrpc: "2.0", id: message.id, error: [code: -32603, message: t.toString()]])
                }
                // Keep the server alive
            }
        }
    }

    private void handleDidOpenOrChange(Object message) {
        def uri = message.params?.textDocument?.uri
        def content = message.method == 'textDocument/didOpen' ?
            message.params?.textDocument?.text :
            message.params?.contentChanges?.getAt(0)?.text

        Logging.log("Changed document: ${uri}")
        Logging.log("Full content received:")
        Logging.log(content ?: "<empty>")

        def pr = Parser.parseGroovy(content ?: "")
        this.lastParsedUnit = pr.unit
        this.lastSourceText = pr.sourceText

        def diagnostics = []
        for (error in pr.diagnostics) {
            diagnostics << [
                range: [
                    start: [line: error.line, character: error.column],
                    end:   [line: error.line, character: (error.column ?: 0) + 1]
                ],
                severity: 1,
                message: error.message,
                source: "groovy-lsp"
            ]
            Logging.log("Diagnostic: ${error.message} at ${error.line}:${error.column}")
        }

        // Heuristic: suppress the single trailing '.' parse error so completion tests
        // don't trip on it while the user is mid-typing "Bar."
        try {
            List<String> lines = (content ?: "").readLines()
            int idx = lines.size() - 1
            while (idx >= 0 && (lines[idx]?.trim()?.length() ?: 0) == 0) idx--
            boolean trailingDot = (idx >= 0) && (lines[idx]?.trim()?.endsWith("."))
            if (trailingDot) {
                int before = diagnostics.size()
                diagnostics = diagnostics.findAll { d ->
                    String msg = String.valueOf(d.message)
                    boolean isDot = msg.toLowerCase().startsWith("unexpected token: .")
                    if (isDot) {
                        Logging.log("Suppressing diagnostic for trailing '.' on line ${d.range?.start?.line}")
                    }
                    return !isDot
                }
                Logging.log("Trailing-dot heuristic filtered ${before - diagnostics.size()} diagnostics")
            }
        } catch (Throwable t) {
            Logging.log("Trailing-dot filter failed: ${t.class.name}: ${t.message}")
        }

        transport.sendMessage([
            jsonrpc: "2.0",
            method: "textDocument/publishDiagnostics",
            params: [ uri: uri, diagnostics: diagnostics ]
        ])
    }

    private void handleDefinition(Object message) {
        try {
            if (!lastParsedUnit) {
                transport.sendMessage([jsonrpc: "2.0", id: message.id, result: null])
                return
            }
            def position = message.params?.position
            def lines = lastSourceText.readLines()
            if (position.line >= lines.size()) {
                Logging.log("Invalid line position: ${position.line}")
                transport.sendMessage([jsonrpc: "2.0", id: message.id, result: null])
                return
            }
            def lineText = lines[position.line]
            def commentIndex = lineText.indexOf("//")
            if (commentIndex >= 0 && position.character > commentIndex) {
                Logging.log("Position is inside an inline comment, skipping definition lookup.")
                transport.sendMessage([jsonrpc: "2.0", id: message.id, result: null])
                return
            }

            // Allow GString $var lookups even when NOT using ${...}
            String forcedWordFromGString = null
            boolean insideDq = StringHeuristics.isInsideDoubleQuotedString(lineText, position.character)
            boolean insidePlaceholder = StringHeuristics.isInsideGStringPlaceholder(lineText, position.character)
            if (insideDq && !insidePlaceholder) {
                forcedWordFromGString = StringHeuristics.gstringVarAt(lineText, position.character)
                if (forcedWordFromGString) {
                    Logging.log("Inside GString without braces; treating '\$${forcedWordFromGString}' as identifier at cursor.")
                } else {
                    Logging.log('Position is inside a string literal (no $var at cursor); skipping definition lookup.')
                    transport.sendMessage([jsonrpc: "2.0", id: message.id, result: null])
                    return
                }
            }

            // Strict qualified property/member lookup at the cursor
            def qualifiedAttempt = MemberResolver.resolveQualifiedProperty(lastParsedUnit, lines, position.line, position.character, lineText)
            if (qualifiedAttempt?.matchAtCursor) {
                if (qualifiedAttempt.found) {
                    Logging.log("Resolved qualified property/member at cursor for definition: ${qualifiedAttempt.debug ?: ''}")
                    transport.sendMessage([
                        jsonrpc: "2.0",
                        id: message.id,
                        result: [
                            uri: message.params.textDocument.uri,
                            range: [
                                start: [line: qualifiedAttempt.line, character: qualifiedAttempt.column],
                                end:   [line: qualifiedAttempt.line, character: qualifiedAttempt.column + qualifiedAttempt.word.length()]
                            ]
                        ]
                    ])
                } else {
                    Logging.log("Qualified property/member lookup at cursor failed, returning null (no fallback)")
                    transport.sendMessage([jsonrpc: "2.0", id: message.id, result: null])
                }
                return
            }

            // Fallback: identifier under cursor (or forced from $var)
            def wordMatcher = (lineText =~ /\b\w+\b/)
            def word = forcedWordFromGString
            int wordStart = -1
            int wordEnd = -1
            if (!word) {
                while (wordMatcher.find()) {
                    if (wordMatcher.start() <= position.character && position.character < wordMatcher.end()) {
                        word = wordMatcher.group()
                        wordStart = wordMatcher.start()
                        wordEnd = wordMatcher.end()
                        break
                    }
                }
            } else {
                // best-effort to set bounds for downstream unqualified-call/type checks
                def m2 = (lineText =~ /\$${java.util.regex.Pattern.quote(word)}\b/)
                if (m2.find()) {
                    wordStart = m2.start() + 1 // skip '$'
                    wordEnd = m2.end()
                }
            }

            if (!word && position.character < lineText.length() && lineText.charAt(position.character) == '.') {
                def leftMatcher = (lineText =~ /[A-Za-z_][A-Za-z0-9_]*/)
                while (leftMatcher.find()) {
                    if (leftMatcher.end() == position.character) {
                        word = leftMatcher.group()
                        wordStart = leftMatcher.start()
                        wordEnd = leftMatcher.end()
                    }
                }
                if (word) Logging.log("Cursor on '.', using left qualifier word '${word}'")
            }
            Logging.log("Looking for definition of word: '${word}' at ${position.line}:${position.character}")
            if (!word) {
                Logging.log("No word found at cursor (may be operator or whitespace), skipping.")
                transport.sendMessage([jsonrpc: "2.0", id: message.id, result: null])
                return
            }
            if (StringHeuristics.isGroovyKeyword(word)) {
                Logging.log("Identifier '${word}' is a Groovy keyword; skipping GoTo.")
                transport.sendMessage([jsonrpc: "2.0", id: message.id, result: null])
                return
            }
            if (!word.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                Logging.log("Not an identifier: '${word}', skipping GoTo")
                transport.sendMessage([jsonrpc: "2.0", id: message.id, result: null])
                return
            }

            // Context walk for locals/params
            def contextClass = null
            def contextMethod = null
            for (cls in lastParsedUnit.AST.classes) {
                Logging.log("  Checking class '${cls.name}' lines: ${cls.lineNumber}-${cls.lastLineNumber}")
                if ((cls.lineNumber ?: 1) <= position.line + 1 && (cls.lastLineNumber ?: 1000000) >= position.line + 1) {
                    contextClass = cls
                    for (method in cls.methods) {
                        Logging.log("    Checking class method '${method.name}' lines: ${method.lineNumber}-${method.lastLineNumber}")
                        if ((method.lineNumber ?: 1) <= position.line + 1 && (method.lastLineNumber ?: 1000000) >= position.line + 1) {
                            contextMethod = method
                            break
                        }
                    }
                    break
                }
            }
            if (!contextMethod) {
                Logging.log("No context method found via class walk; checking all top-level AST methods...")
                for (method in lastParsedUnit.AST.methods) {
                    Logging.log("  Top-level method '${method.name}' lines: ${method.lineNumber}-${method.lastLineNumber}")
                    if ((method.lineNumber ?: 1) <= position.line + 1 && (method.lastLineNumber ?: 1000000) >= position.line + 1) {
                        contextMethod = method
                        break
                    }
                }
            }
            Logging.log("All top-level AST.methods:")
            for (method in lastParsedUnit.AST.methods) {
                Logging.log("  Method: ${method.name} [${method.lineNumber}-${method.lastLineNumber}]")
            }

            def locals = [:]
            if (contextMethod) {
                Logging.log("Using method for local/param lookup: ${contextMethod.name} [${contextMethod.lineNumber}-${contextMethod.lastLineNumber}]")
                locals = AstNavigator.collectLocalVariables(contextMethod, lines)
            } else {
                Logging.log("No contextMethod found for position ${position.line}, can't search for local variables.")
            }
            def info = locals[word]
            if (info) {
                Logging.log("Found ${info.kind} variable '${word}' at ${info.line}:${info.column}")
                transport.sendMessage([
                    jsonrpc: "2.0",
                    id: message.id,
                    result: [
                        uri: message.params.textDocument.uri,
                        range: [
                            start: [line: info.line, character: info.column],
                            end:   [line: info.line, character: info.column + word.length()]
                        ]
                    ]
                ])
                return
            } else {
                Logging.log("Variable/parameter '${word}' NOT found in locals for method: " + (contextMethod ? "${contextMethod.name} [${contextMethod.lineNumber}-${contextMethod.lastLineNumber}]" : "<none>"))
            }

            // Compute generic prev/next tokens for context-sensitive decisions
            String prevWordGeneric = null
            char prevNonSpaceChar = '\u0000'
            if (wordStart > 0) {
                int i = wordStart - 1
                while (i >= 0 && Character.isWhitespace(lineText.charAt(i))) i--
                if (i >= 0) {
                    prevNonSpaceChar = lineText.charAt(i)
                    int end = i
                    while (i >= 0 && Character.isJavaIdentifierPart(lineText.charAt(i))) i--
                    if (end >= 0 && i < end) {
                        prevWordGeneric = lineText.substring(i + 1, end + 1)
                    }
                }
            }
            int k = Math.max(0, wordEnd)
            while (k < lineText.length() && Character.isWhitespace(lineText.charAt(k))) k++
            char nextNonSpaceChar = (k < lineText.length()) ? lineText.charAt(k) : '\u0000'

            // If looks like an unqualified method call at this cursor, try overload resolution on the enclosing class (e.g., Script)
            boolean nextCharIsParen = (wordEnd >= 0 && wordEnd < (lineText?.length() ?: 0) && lineText.charAt(wordEnd) == '(')
            // Guard: don't treat "new Foo(" (constructor) as an unqualified method call
            boolean precededByNew = false
            if (wordStart > 0) {
                int i = wordStart - 1
                while (i >= 0 && Character.isWhitespace(lineText.charAt(i))) i--
                int end = i
                while (i >= 0 && Character.isJavaIdentifierPart(lineText.charAt(i))) i--
                String prevWordCalc = (end >= 0 && i < end) ? lineText.substring(i + 1, end + 1) : ""
                precededByNew = "new".equals(prevWordCalc)
                Logging.log("Unqualified-call/type check: prevWord='${prevWordCalc}', precededByNew=${precededByNew}, nextCharIsParen=${nextCharIsParen}")
            }
            boolean isUnqualifiedCall = nextCharIsParen && !precededByNew

            if (contextClass && isUnqualifiedCall) {
                def callArgs = StringHeuristics.extractGroovyCallArgKinds(lineText, wordEnd - 1)
                Logging.log("Unqualified call detected for '${word}' with arg kinds: ${callArgs}")
                def mr = AstNavigator.findFieldOrPropertyInHierarchy(contextClass, word, lines, "preferMethod", callArgs, lastParsedUnit)
                if (mr) {
                    Logging.log("Resolved unqualified call '${word}' to ${mr.line}:${mr.column}")
                    transport.sendMessage([
                        jsonrpc: "2.0",
                        id: message.id,
                        result: [
                            uri: message.params.textDocument.uri,
                            range: [
                                start: [line: mr.line, character: mr.column],
                                end:   [line: mr.line, character: mr.column + word.length()]
                            ]
                        ]
                    ])
                    return
                } else {
                    Logging.log("Unqualified call overload resolution for '${word}' failed; will continue with generic search.")
                }
            }

            if (contextClass) {
                def res = AstNavigator.findFieldOrPropertyInHierarchy(contextClass, word, lines, "any", null, lastParsedUnit)
                if (res) {
                    Logging.log("Resolved class property/field/method '${word}' to line ${res.line} column ${res.column}")
                    transport.sendMessage([
                        jsonrpc: "2.0",
                        id: message.id,
                        result: [
                            uri: message.params.textDocument.uri,
                            range: [
                                start: [line: res.line, character: res.column],
                                end:   [line: res.line, character: res.column + word.length()]
                            ]
                        ]
                    ])
                    return
                }
            }

            // Decide whether this identifier is likely a TYPE reference.
            boolean classExists = false
            try {
                for (cls in lastParsedUnit?.AST?.classes ?: []) {
                    if (cls?.nameWithoutPackage == word) { classExists = true; break }
                }
            } catch (Throwable t) {
                // ignore
            }
            boolean looksLikeTypeContext =
                    precededByNew ||
                    "as".equals(prevWordGeneric) ||
                    "extends".equals(prevWordGeneric) ||
                    "implements".equals(prevWordGeneric) ||
                    prevNonSpaceChar == '(' ||   // cast like (Foo) x
                    nextNonSpaceChar == '.' ||   // static access Foo.SOMETHING
                    (classExists && Character.isUpperCase(word.charAt(0)))

            Logging.log("Context decision for '${word}': looksLikeTypeContext=${looksLikeTypeContext}, classExists=${classExists}, prevWord=${prevWordGeneric}, prevChar='${prevNonSpaceChar}', nextChar='${nextNonSpaceChar}'")

            // Prefer top-level variables for non-call identifiers ONLY when we are not in a type-ish context.
            if (!isUnqualifiedCall && !looksLikeTypeContext) {
                Logging.log("Heuristic: prefer top-level variable for non-call identifier '${word}'")
                def tlVarFirst = AstNavigator.findTopLevelVariable(word, lines, lastParsedUnit)
                if (tlVarFirst) {
                    Logging.log("Top-level variable chosen for '${word}' at ${tlVarFirst.line}:${tlVarFirst.column}")
                    transport.sendMessage([
                        jsonrpc: "2.0",
                        id: message.id,
                        result: [
                            uri: message.params.textDocument.uri,
                            range: [
                                start: [line: tlVarFirst.line, character: tlVarFirst.column],
                                end:   [line: tlVarFirst.line, character: tlVarFirst.column + word.length()]
                            ]
                        ]
                    ])
                    return
                } else {
                    Logging.log("No top-level variable found for '${word}' in variable-first branch.")
                }
            }

            // Prefer classes/methods (when we didn't early-return with a var)
            def topRes = AstNavigator.findTopLevelClassOrMethod(lastParsedUnit, word, lines)
            if (topRes) {
                Logging.log("Resolved '${word}' as top-level class/method at ${topRes.line}:${topRes.column}")
                transport.sendMessage([
                    jsonrpc: "2.0",
                    id: message.id,
                    result: [
                        uri: message.params.textDocument.uri,
                        range: [
                            start: [line: topRes.line, character: topRes.column],
                            end:   [line: topRes.line, character: topRes.column + word.length()]
                        ]
                    ]
                ])
                return
            }

            // Fallback: try top-level variable (e.g., if we ARE a call, or nothing else matched)
            def toplevelVar = AstNavigator.findTopLevelVariable(word, lines, lastParsedUnit)
            if (toplevelVar) {
                Logging.log("Fallback top-level variable for '${word}' at ${toplevelVar.line}:${toplevelVar.column}")
                transport.sendMessage([
                    jsonrpc: "2.0",
                    id: message.id,
                    result: [
                        uri: message.params.textDocument.uri,
                        range: [
                            start: [line: toplevelVar.line, character: toplevelVar.column],
                            end:   [line: toplevelVar.line, character: toplevelVar.column + word.length()]
                        ]
                    ]
                ])
                return
            }

            Logging.log("Definition not found for: '${word}'")
            transport.sendMessage([jsonrpc: "2.0", id: message.id, result: null])
        } catch (Throwable t) {
            Logging.log("ERROR in handleDefinition: ${t.class.name}: ${t.message}")
            t.printStackTrace(System.err)
            transport.sendMessage([jsonrpc: "2.0", id: message.id, result: null])
        }
    }

    private void handleCompletion(Object message) {
        try {
            if (!lastParsedUnit || lastSourceText == null) {
                transport.sendMessage([jsonrpc: "2.0", id: message.id, result: [isIncomplete:false, items: []]])
                return
            }
            def position = message.params?.position
            def lines = lastSourceText.readLines()
            if (position.line < 0 || position.line >= lines.size()) {
                transport.sendMessage([jsonrpc: "2.0", id: message.id, result: [isIncomplete:false, items: []]])
                return
            }
            def lineText = lines[position.line] ?: ""
            // ignore inside comments or non-placeholder strings
            def commentIndex = lineText.indexOf("//")
            if (commentIndex >= 0 && position.character > commentIndex) {
                transport.sendMessage([jsonrpc: "2.0", id: message.id, result: [isIncomplete:false, items: []]])
                return
            }
            if (StringHeuristics.isInsideDoubleQuotedString(lineText, position.character) && !StringHeuristics.isInsideGStringPlaceholder(lineText, position.character)) {
                transport.sendMessage([jsonrpc: "2.0", id: message.id, result: [isIncomplete:false, items: []]])
                return
            }

            Logging.log("handleCompletion at ${position.line}:${position.character} with line='${lineText}'")
            def items = CompletionEngine.suggest(lastParsedUnit, lastSourceText, position.line, position.character)
            Logging.log("handleCompletion suggestions: ${items?.size() ?: 0}")
            transport.sendMessage([
                jsonrpc: "2.0",
                id: message.id,
                result: [ isIncomplete: false, items: items ?: [] ]
            ])
        } catch (Throwable t) {
            Logging.log("ERROR in handleCompletion: ${t.class.name}: ${t.message}")
            t.printStackTrace(System.err)
            transport.sendMessage([jsonrpc: "2.0", id: message.id, result: [isIncomplete:false, items: []]])
        }
    }
}
