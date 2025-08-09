#!/usr/bin/env groovy
import groovy.json.*
import java.nio.charset.StandardCharsets

import groovy.transform.Field


class LspTestClient {
    static List testErrors = []

    Process lspProcess
    BufferedWriter lspIn
    BufferedReader lspOut
    int nextId = 1
    Map<Integer, Map> pending = [:]
    Map<String, Object> lastDiagnostics = [:]
    Thread lspReaderThread
    Thread lspStderrThread

    boolean debug = false

    class TestUnit {
        String name
        String fileName
        File f
        String uri

        public TestUnit(String fileName) {
            this.name = getFilenameWithoutExtension(fileName)
            this.fileName = fileName
            f = new File(fileName)
            uri = uriForFile(f)
        }

        void assertGoto(Map args=[:]) {
            try {
                def from = wrangleLocation(args.from)
                def to = wrangleLocation(args.to)
                print "[${this.name}] Asserting GoTo ($args.from) => ($args.to) "
                def res = sendDefinition(uri, from.line, from.col)
                assert res != null : "No definition found"
                def actual = [line: res.range.start.line, col: res.range.start.character]
                assert actual.toString() == to.toString() : "Expected to resolve to ${to} but got ${actual}"
                print " ...OK\n"
            } catch (Throwable e) {
                testErrors << "[${this.name}] assertGoto failed: $args.from -> $args.to: While testing ${args.test}. ${e.message}"
                println " ...FAILED"
            }
        }

        void assertNoGoto(Map args=[:]) {
            try {
                def from = wrangleLocation(args.from)
                print "[${this.name}] Asserting NoGoTo ($args.from) "
                def res = sendDefinition(uri, from.line, from.col)
                assert res == null : "A definition was found when it shouldn't have to ${[line: res.range.start.line, col: res.range.start.character]}"
                print " ...OK\n"
            } catch (Throwable e) {
                testErrors << "[${this.name}] assertNoGoto failed: $args.from: While testing ${args.test}. ${e.message}"
                println " ...FAILED"
            }
        }

        void assertDiagnostic(Map args) {
            try {
                print "[${this.name}] Asserting diagnostic: ${args.msg} "
                def diags = getDiagnostics(uri)
                assert diags.find { it.toString() == args.msg } : "\nExpected diagnostic:\n${args.msg}\n\nActual:\n$diags"
                print "  ...OK\n"
            } catch (Throwable e) {
                testErrors << "[${this.name}] assertDiagnostic failed: While testing ${args.test}. ${e.message}"
                println " ...FAILED"
            }
        }

        void assertNoDiagnostic() {
            try {
                print "[${this.name}] Asserting 0 diagnostics "
                def diags = getDiagnostics(uri)
                assert diags.size() == 0 : "Expected 0 diagnostics, found ${diags.size()}:\n${diags}"
                print "  ...OK\n"
            } catch (Throwable e) {
                testErrors << "[${this.name}] assertNoDiagnostic failed: ${e.message}"
                println " ...FAILED"
            }
        }
    }

    static def getFilenameWithoutExtension(String path) {
        def file = new File(path)
        def name = file.name
        def dotIndex = name.lastIndexOf('.')
        return (dotIndex != -1) ? name[0..<dotIndex] : name
    }

    static Map wrangleLocation(String loc) {
        def result = [:]
        def parts = loc.split(":")
        // NOTE : -1 for 'vim' line/column reporting
        result.line = parts[0].toInteger() - 1
        result.col = parts[1].toInteger() - 1
        return result
    }

    void startLsp(String cmdLine, boolean debug = false) {
        this.debug = debug
        println "Starting LSP server: $cmdLine"
        lspProcess = cmdLine.execute()
        lspIn = new BufferedWriter(new OutputStreamWriter(lspProcess.outputStream, StandardCharsets.UTF_8))
        lspOut = new BufferedReader(new InputStreamReader(lspProcess.inputStream, StandardCharsets.UTF_8))

        // Start a thread to read protocol (inputStream only)
        lspReaderThread = Thread.start {
            try {
                while (true) {
                    def headers = [:]
                    String line
                    // Read headers
                    while ((line = lspOut.readLine()) != null) {
                        if (line.trim() == "") break
                        def parts = line.split(/:\s*/, 2)
                        if (parts.size() == 2) headers[parts[0]] = parts[1]
                    }
                    if (!headers['Content-Length']) continue
                    int len = headers['Content-Length'] as int
                    char[] buf = new char[len]
                    int read = lspOut.read(buf, 0, len)
                    if (read != len) throw new IOException("Short read: $read vs $len")
                    def msg = new JsonSlurper().parseText(new String(buf))
                    handleMsg(msg)
                }
            } catch (Exception e) {
                if (debug) e.printStackTrace()
            }
        }

        // Forward LSP server's stderr if debug is enabled
        if (debug) {
            lspStderrThread = Thread.start {
                try {
                    InputStream es = lspProcess.getErrorStream()
                    BufferedReader br = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8))
                    String line
                    while ((line = br.readLine()) != null) {
                        System.err.println("[LSP-STDERR] $line")
                    }
                } catch (Exception e) {}
            }
        }
    }

    void send(Object msg) {
        String json = JsonOutput.toJson(msg)
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8)
        lspIn.write("Content-Length: ${bytes.length}\r\n\r\n")
        lspIn.write(json)
        lspIn.flush()
    }

    void handleMsg(Map msg) {
        // Reply (result to our request)
        if (msg.id != null) {
            pending[msg.id as int] = msg
        }
        // Notification (diagnostics etc)
        if (msg.method == "textDocument/publishDiagnostics") {
            lastDiagnostics[msg.params.uri] = msg.params.diagnostics
        }
    }

    void sendInitialize() {
        send([
            id: nextId++,
            jsonrpc: "2.0",
            method: "initialize",
            params: [
                capabilities: [:]
            ]
        ])
    }

    void sendDidOpen(String uri, String text) {
        send([
            jsonrpc: "2.0",
            method: "textDocument/didOpen",
            params: [
                textDocument: [
                    uri: uri,
                    languageId: "groovy",
                    version: 1,
                    text: text
                ]
            ]
        ])
    }

    Map sendDefinition(String uri, int line, int character) {
        int reqId = nextId++
        send([
            id: reqId,
            jsonrpc: "2.0",
            method: "textDocument/definition",
            params: [
                textDocument: [ uri: uri ],
                position: [ line: line, character: character ]
            ]
        ])
        // Wait for reply
        for (int i=0; i<100; ++i) {
            Thread.sleep(50)
            if (pending.containsKey(reqId)) {
                def res = pending.remove(reqId)
                return res?.result
            }
        }
        throw new RuntimeException("Timeout waiting for definition result")
    }

    List getDiagnostics(String uri) {
        // Wait for diagnostics
        for (int i=0; i<100; ++i) {
            Thread.sleep(50)
            if (lastDiagnostics.containsKey(uri)) {
                return lastDiagnostics[uri]
            }
        }
        throw new RuntimeException("Timeout waiting for diagnostics")
    }

    static String uriForFile(File f) {
        return "file:///${f.absolutePath.replaceAll('\\\\','/')}"
    }

    // High level API

    TestUnit loadTestUnit(String fileName) {
        File f = new File(fileName)
        assert f.exists() : "File does not exist: $fileName"
        String text = f.text
        String uri = uriForFile(f)
        sendDidOpen(uri, text)
        Thread.sleep(200) // let diagnostics flow

        return new TestUnit(fileName)
    }

    void close() {
        lspProcess?.destroy()
        lspReaderThread?.interrupt()
        lspStderrThread?.interrupt()
    }

    static void run(String lspCmd, Boolean debug, Closure cb) {
        def client = new LspTestClient() 
        try { 
            client.startLsp(lspCmd, debug) 
            client.sendInitialize() 
            Thread.sleep(200) 

            cb(client)
        } finally {
            client.close()

            // ==== Summary and exit code ====
            if (!testErrors.isEmpty()) {
                println "\n=== Test Failures (${testErrors.size()}) ==="
                testErrors.each { println it }
                System.exit(1)
            } else {
                println "\nAll tests passed!"
                System.exit(0)
            }
        }
    }
}

// -------- Argument parsing and Example usage (can be adapted) ----------
def debug = args.any { it == '--debug' || it == '-d' }
// def lspCmd = "java -jar ./target/hello-world-1.0.0.jar"
def lspCmd = "java -jar ./target/jenkins-lsp-1.0.0-all.jar"

LspTestClient.run(lspCmd, debug){ def client ->
    def tuBasics = client.loadTestUnit("./integration-tests/src/jenkinslsp/basics.groovy") 
    tuBasics.assertGoto(from: "35:12", to: "29:9", test: "resolving 'rabauke'")
    tuBasics.assertGoto(from: "34:9", to: "26:27", test: "resolving foo (parameter)")
    tuBasics.assertGoto(from: "33:11", to: "13:12", test: "resolving foo (Foo.foo property)")
    tuBasics.assertGoto(from: "32:17", to: "12:7", test: "resolving Class Foo")
    tuBasics.assertGoto(from: "16:18", to: "13:12", test: "resolving 'this.foo' in a method of own class")
    tuBasics.assertGoto(from: "22:21", to: "13:12", test: "resolving 'this.foo' in a subclass")
    tuBasics.assertGoto(from: "33:9", to: "32:9", test: "resolving 'f.' to 'def f'")
    tuBasics.assertGoto(from: "42:38", to: "21:12", test: "resolving 'p.bar' to 'String bar()' method of Class Bar")
    tuBasics.assertNoGoto(from: "31:8", test: "not being able to resolve text in comments")
    tuBasics.assertNoGoto(from: "51:37", test: "trying to GoTo on `==` shouldn't break LSP Server")
    tuBasics.assertGoto(from: "50:9", to: "46:12", test: "resolving 'rapookee' (specific type, not def)")
    tuBasics.assertGoto(from: "51:13", to: "48:5", test: "resolving 'what' (with line continuation character)")
    tuBasics.assertNoGoto(from: "55:6", test: "resolving 'topLevelVar' outside of a placeholder expression in a \" string literal - which should not succeed")
    tuBasics.assertNoGoto(from: "51:8", test: "resolving the 'assert' keyword")
    tuBasics.assertGoto(from: "55:21", to: "6:5", test: "resolving 'topLevelVar' inside of a placeholder expression in a \" string literal - which should succeed")
    tuBasics.assertNoDiagnostic()

    def tuBasicsErrors = client.loadTestUnit("./integration-tests/src/jenkinslsp/basics-errors.groovy") 
    tuBasicsErrors.assertDiagnostic(msg: """{message=Method 'methodMissingReturn' declares return type 'String' but does not return anything, range={end={character=1, line=10}, start={character=0, line=10}}, severity=1, source=groovy-lsp}""", test: "methods with false/missing return values/types")

    def tuVarsGlobalVariable = client.loadTestUnit("./integration-tests/vars/global-variable.groovy") 
    tuVarsGlobalVariable.assertGoto(from: "69:1", to: "39:5", test: "resolving 'def call(Map args=[:], Closure cb)'")
    tuVarsGlobalVariable.assertGoto(from: "70:1", to: "57:5", test: "resolving 'def call(Map args=[:])'")
    // tuVarsGlobalVariable.assertGoto(from: "71:1", to: "49:5", test: "resolving 'def call(Map args=[:], String stageName, Closure cb)'")
    tuVarsGlobalVariable.assertNoDiagnostic()
}
