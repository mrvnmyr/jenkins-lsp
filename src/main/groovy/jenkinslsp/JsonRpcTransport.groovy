package jenkinslsp

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

import java.nio.charset.Charset
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * JSON-RPC 2.0 transport over stdio.
 * - Reads framed messages using Content-Length headers
 * - Writes responses using the same framing
 */
class JsonRpcTransport {
    private final BufferedReader reader
    private final OutputStream stdout
    private final JsonSlurper slurper

    JsonRpcTransport() {
        this.reader = new BufferedReader(new InputStreamReader(System.in, Charset.forName("UTF-8")))
        this.stdout = System.out
        this.slurper = new JsonSlurper()
    }

    /**
     * Reads a single JSON-RPC message from stdin. Returns a Map or List depending on input.
     * When EOF is reached, exits the process (to keep parity with the original script).
     */
    Object readMessage() {
        def headers = [:]
        String line
        while ((line = reader.readLine()) != null) {
            if (line.trim() == "") break
            def parts = line.split(/:\s*/, 2)
            if (parts.size() == 2) headers[parts[0]] = parts[1]
            else {
                Logging.log("Invalid header line: '${line}'")
                return null
            }
        }
        if (line == null) {
            Logging.log("Client disconnected (EOF)")
            System.exit(0)
        }
        def contentLength = headers['Content-Length']?.toInteger()
        if (!contentLength) {
            Logging.log("Missing Content-Length")
            return null
        }
        char[] buffer = new char[contentLength]
        int read = reader.read(buffer, 0, contentLength)
        if (read != contentLength) {
            Logging.log("Incomplete read: expected ${contentLength}, got ${read}")
            return null
        }
        def text = new String(buffer)
        return slurper.parseText(text)
    }

    /**
     * Serializes and writes a JSON-RPC message to stdout using Content-Length framing.
     */
    void sendMessage(Object json) {
        def text = JsonOutput.toJson(json)
        def bytes = text.getBytes("UTF-8")
        stdout.write(("Content-Length: " + bytes.length + "\r\n\r\n").getBytes("UTF-8"))
        stdout.write(bytes)
        stdout.flush()
    }
}
