package jenkinslsp

class ConfigLoader {
    static final String CONFIG_NAME = ".jenkinslsp.groovy"

    static Map loadForFile(File file, File cwd) {
        File configFile = null
        if (file?.parentFile) {
            configFile = findConfigUpwards(file.parentFile)
        }
        if (!configFile && cwd) {
            configFile = findConfigUpwards(cwd)
        }
        Map conf = configFile ? readConfig(configFile) : [:]
        boolean allowStdlib = conf.containsKey("allowStdlibResolution") ?
            toBoolean(conf.allowStdlibResolution) : true
        return [allowStdlibResolution: allowStdlib, configFile: configFile, configRoot: configFile?.parentFile]
    }

    private static File findConfigUpwards(File startDir) {
        File current = canonicalFile(startDir)
        while (current) {
            File candidate = new File(current, CONFIG_NAME)
            if (candidate.exists() && candidate.isFile()) return candidate
            current = current.parentFile
        }
        return null
    }

    private static Map readConfig(File file) {
        try {
            def res = new GroovyShell().evaluate(file)
            if (res instanceof Map) return (Map) res
            Logging.log("Config file ${file} did not return a Map, ignoring.")
        } catch (Throwable t) {
            Logging.log("Failed to load config ${file}: ${t.class.name}: ${t.message}")
        }
        return [:]
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean) return (boolean) value
        if (value == null) return false
        String text = String.valueOf(value).trim().toLowerCase()
        return text == "true" || text == "1" || text == "yes" || text == "y"
    }

    private static File canonicalFile(File f) {
        if (!f) return null
        try {
            return f.getCanonicalFile()
        } catch (Throwable ignore) {
            return f
        }
    }
}
