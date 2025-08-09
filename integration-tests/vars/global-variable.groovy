#!/usr/bin/env groovy
// RESERVED : for import
// RESERVED : for import
// RESERVED : for import
// RESERVED : for import
// RESERVED : for import

void log(String msg) {
    System.out.println(msg)
}

def _internal(Map args=[:]) {
    args.stageName = args.stageName ?: "Unknown"
    args.timeout = args.timeout ?: null

    echo("args.someArg: ${args.someArg}")

    def result = null
    Closure executeBody = {
        try {
            result = args.body()
        } catch (Exception e) {
            // Set current stage as 'skipped'
            org.jenkinsci.plugins.pipeline.modeldefinition.Utils.markStageSkippedForConditional(args.stageName)
            System.out.println(e.getMessage())
        }
    }

    if (args.timeout) {
        timeout(args.timeout, executeBody)
    } else {
        executeBody()
    }

    return result
}

// Support all possible calls ↓
def call(Map args=[:], Closure cb) {
    def result
    stage(args.stageName) {
        result = _internal(args + [
            body: cb,
        ])
    }
    return result
}

def call(Map args=[:]) {
    def result
    stage(args.stageName) {
        result = _internal(args)
    }
    return result
}

def call(Map args=[:], String stageName, Closure cb) {
    def result
    stage(stageName) {
        result = _internal(args + [
            stageName: stageName,
            body: cb,
        ])
    }
    return result
}

// NOTE : these are not in order on purpose
call(stageName: "test-1") { log("test-1"); }
call(droppedArg: null, "test-2", {  log("test-2"); })
call(stageName: "test-3", cb: { log("test-3"); })
