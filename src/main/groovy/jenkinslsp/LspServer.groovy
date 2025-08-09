package jenkinslsp

import groovy.json.JsonOutput
import org.codehaus.groovy.control.SourceUnit

/**
 * Minimal LSP server loop + JSON-RPC handling.
 * Keeps parity with your single-file behavior while delegating to Parser,
 * AstNavigator, StringHeuristics, and MemberResolver.
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
            switch (message.method) {
                case 'initialize':
                    transport.sendMessage([
                        jsonrpc: "2.0",
                        id: message.id,
                        result: [ capabilities: [ textDocumentSync: 1, definitionProvider: true ]]
                    ])
                    break

                case 'textDocument/didOpen':
                case 'textDocument/didChange':
                    handleDidOpenOrChange(message)
                    break

                case 'textDocument/definition':
                    handleDefinition(message)
                    break

                default:
                    Logging.log("Ignored method: ${message.method}")
                    break
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
        transport.sendMessage([
            jsonrpc: "2.0",
            method: "textDocument/publishDiagnostics",
            params: [ uri: uri, diagnostics: diagnostics ]
        ])
    }

    private void handleDefinition(Object message) {
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
        // If inside a double-quoted string but NOT inside a ${...} placeholder, do not resolve
        if (StringHeuristics.isInsideDoubleQuotedString(lineText, position.character) && !StringHeuristics.isInsideGStringPlaceholder(lineText, position.character)) {
            Logging.log('Position is inside a string literal (not in \\${...}); skipping definition lookup.')
            transport.sendMessage([jsonrpc: "2.0", id: message.id, result: null])
            return
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

        // Fallback: identifier under cursor
        def wordMatcher = (lineText =~ /\b\w+\b/)
        def word = null
        int wordStart = -1
        int wordEnd = -1
        while (wordMatcher.find()) {
            if (wordMatcher.start() <= position.character && position.character < wordMatcher.end()) {
                word = wordMatcher.group()
                wordStart = wordMatcher.start()
                wordEnd = wordMatcher.end()
                break
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

        // If looks like an unqualified method call at this cursor, try overload resolution on the enclosing class (e.g., Script)
        boolean nextCharIsParen = (wordEnd >= 0 && wordEnd < (lineText?.length() ?: 0) && lineText.charAt(wordEnd) == '(')
        // Guard: don't treat "new Foo(" (constructor) as an unqualified method call
        boolean precededByNew = false
        if (nextCharIsParen && wordStart > 0) {
            int i = wordStart - 1
            while (i >= 0 && Character.isWhitespace(lineText.charAt(i))) i--
            int end = i
            while (i >= 0 && Character.isJavaIdentifierPart(lineText.charAt(i))) i--
            String prevWord = (end >= 0 && i < end) ? lineText.substring(i + 1, end + 1) : ""
            precededByNew = "new".equals(prevWord)
            Logging.log("Unqualified-call check: prevWord='${prevWord}', precededByNew=${precededByNew}, nextCharIsParen=${nextCharIsParen}")
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

        def toplevelVar = AstNavigator.findTopLevelVariable(word, lines)
        if (toplevelVar) {
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

        def topRes = AstNavigator.findTopLevelClassOrMethod(lastParsedUnit, word, lines)
        if (topRes) {
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

        Logging.log("Definition not found for: '${word}'")
        transport.sendMessage([jsonrpc: "2.0", id: message.id, result: null])
    }
}
