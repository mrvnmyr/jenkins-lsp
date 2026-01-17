package jenkinslsp

import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.regex.Pattern
import java.nio.file.Files
import java.nio.file.Path

class StdlibSourceIndex {
    private static final String STDLIB_RESOURCE =
        "/stdlib/groovy-" + GroovySystem.version + "-sources.jar"
    private static final File CACHE_DIR = new File(System.getProperty("user.home"), ".cache/jenkins-lsp/stdlib")
    private static final List<String> PREFERRED_METHOD_FILES = [
        "org/codehaus/groovy/runtime/DefaultGroovyMethods.java",
        "org/codehaus/groovy/runtime/StringGroovyMethods.java",
        "org/codehaus/groovy/runtime/ArrayGroovyMethods.java",
        "org/codehaus/groovy/runtime/CollectionGroovyMethods.java",
        "org/codehaus/groovy/runtime/IteratorGroovyMethods.java",
        "org/codehaus/groovy/runtime/NumberGroovyMethods.java",
        "org/codehaus/groovy/runtime/ResourceGroovyMethods.java",
        "org/codehaus/groovy/runtime/DateGroovyMethods.java",
        "org/codehaus/groovy/runtime/EncodingGroovyMethods.java",
        "org/codehaus/groovy/runtime/IOGroovyMethods.java"
    ]
    private static volatile boolean extracted = false

    static File ensureExtracted() {
        if (extracted) return CACHE_DIR
        synchronized (StdlibSourceIndex) {
            if (extracted) return CACHE_DIR
            if (!CACHE_DIR.exists()) {
                extractResourceJar(STDLIB_RESOURCE, CACHE_DIR)
            }
            extracted = true
        }
        return CACHE_DIR
    }

    static Map resolveMethod(String methodName) {
        if (!methodName) return null
        File root = ensureExtracted()
        if (!root?.exists()) return null

        for (String rel : PREFERRED_METHOD_FILES) {
            File f = new File(root, rel)
            Map hit = findMethodInFile(f, methodName)
            if (hit) return hit
        }

        // Fallback: scan runtime package for a static method signature.
        File runtimeDir = new File(root, "org/codehaus/groovy/runtime")
        Map fallback = scanDirectoryForMethod(runtimeDir, methodName)
        if (fallback) return fallback

        // Final fallback: scan everything (slow but rare).
        return scanDirectoryForMethod(root, methodName)
    }

    static Map resolveClass(String className) {
        if (!className) return null
        File root = ensureExtracted()
        if (!root?.exists()) return null

        Path found = findFileBySimpleName(root.toPath(), className)
        if (!found) return null

        File f = found.toFile()
        Map decl = findClassInFile(f, className)
        if (!decl) return null
        return buildLocation(f, decl.line as int, decl.column as int)
    }

    private static void extractResourceJar(String resourcePath, File destDir) {
        InputStream raw = StdlibSourceIndex.class.getResourceAsStream(resourcePath)
        if (!raw) {
            Logging.log("Stdlib source resource missing at ${resourcePath}; skipping extraction.")
            return
        }
        destDir.mkdirs()
        String rootPath
        try {
            rootPath = destDir.getCanonicalPath() + File.separator
        } catch (IOException ignore) {
            rootPath = destDir.absolutePath + File.separator
        }
        JarInputStream jar = null
        try {
            jar = new JarInputStream(raw)
            JarEntry entry
            while ((entry = jar.nextJarEntry) != null) {
                if (entry.isDirectory()) continue
                String name = entry.name
                if (!(name.endsWith(".java") || name.endsWith(".groovy"))) continue
                File out = new File(destDir, name)
                String outPath
                try {
                    outPath = out.getCanonicalPath()
                } catch (IOException ignore) {
                    outPath = out.absolutePath
                }
                if (!outPath.startsWith(rootPath)) {
                    Logging.log("Skipping suspicious stdlib entry: ${name}")
                    continue
                }
                File parent = out.parentFile
                if (parent && !parent.exists()) parent.mkdirs()
                out.withOutputStream { os ->
                    byte[] buf = new byte[8192]
                    int read
                    while ((read = jar.read(buf)) > 0) {
                        os.write(buf, 0, read)
                    }
                }
            }
            Logging.log("Stdlib sources extracted to ${destDir}")
        } catch (Throwable t) {
            Logging.log("Stdlib source extraction failed: ${t.class.name}: ${t.message}")
        } finally {
            try { jar?.close() } catch (Throwable ignore) {}
            try { raw?.close() } catch (Throwable ignore) {}
        }
    }

    private static Map scanDirectoryForMethod(File dir, String methodName) {
        if (!dir?.exists()) return null
        try {
            Files.walk(dir.toPath()).withCloseable { stream ->
                stream
                    .filter { p -> Files.isRegularFile(p) && (p.toString().endsWith(".java") || p.toString().endsWith(".groovy")) }
                    .forEach { Path p ->
                        if (p == null) return
                        Map hit = findMethodInFile(p.toFile(), methodName)
                        if (hit) {
                            throw new FoundLocationException(p.toFile(), hit.line as int, hit.column as int)
                        }
                    }
            }
        } catch (FoundLocationException found) {
            return buildLocation(found.file, found.line, found.column)
        } catch (Throwable ignore) {
            return null
        }
        return null
    }

    private static Map findMethodInFile(File file, String methodName) {
        if (!file?.exists()) return null
        List<String> lines
        try {
            lines = file.readLines("UTF-8")
        } catch (Throwable ignore) {
            return null
        }
        Pattern p = Pattern.compile("\\bstatic\\b[^;{]*\\b" + Pattern.quote(methodName) + "\\b\\s*\\(")
        for (int i = 0; i < lines.size(); i++) {
            String line = lines[i] ?: ""
            if (isSkippableLine(line)) continue
            if (p.matcher(line).find()) {
                int col = line.indexOf(methodName)
                if (col >= 0) return buildLocation(file, i, col)
            }
            if (i + 1 < lines.size()) {
                String combined = line + " " + (lines[i + 1] ?: "")
                if (p.matcher(combined).find()) {
                    String nextLine = lines[i + 1] ?: ""
                    if (!isSkippableLine(nextLine)) {
                        int col = nextLine.indexOf(methodName)
                        if (col >= 0) return buildLocation(file, i + 1, col)
                    }
                }
            }
        }
        return null
    }

    private static Map findClassInFile(File file, String className) {
        if (!file?.exists()) return null
        List<String> lines
        try {
            lines = file.readLines("UTF-8")
        } catch (Throwable ignore) {
            return null
        }
        Pattern p = Pattern.compile("\\b(class|interface|enum|trait)\\s+" + Pattern.quote(className) + "\\b")
        for (int i = 0; i < lines.size(); i++) {
            String line = lines[i] ?: ""
            if (isSkippableLine(line)) continue
            def m = p.matcher(line)
            if (m.find()) {
                int col = line.indexOf(className)
                if (col >= 0) return [line: i, column: col]
            }
        }
        return null
    }

    private static Path findFileBySimpleName(Path root, String className) {
        if (!root) return null
        String javaName = className + ".java"
        String groovyName = className + ".groovy"
        try {
            Files.walk(root).withCloseable { stream ->
                stream
                    .filter { p ->
                        Files.isRegularFile(p) &&
                            (p.fileName?.toString() == javaName || p.fileName?.toString() == groovyName)
                    }
                    .forEach { Path p ->
                        throw new FoundLocationException(p.toFile(), 0, 0)
                    }
            }
        } catch (FoundLocationException found) {
            return found.file.toPath()
        } catch (Throwable ignore) {
            return null
        }
        return null
    }

    private static boolean isSkippableLine(String line) {
        String t = (line ?: "").trim()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("@")
    }

    private static Map buildLocation(File file, int line, int column) {
        return [uri: file.toURI().toString(), line: line, column: column]
    }

    private static class FoundLocationException extends RuntimeException {
        final File file
        final int line
        final int column

        FoundLocationException(File file, int line, int column) {
            this.file = file
            this.line = line
            this.column = column
        }
    }
}
