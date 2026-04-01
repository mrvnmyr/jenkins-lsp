// RESERVED

import jenkinslsp.support.ImportedSupport

// Exercise diagnostics for invalid imported src method calls from a vars script.
def call(Map args=[:]) {
    ImportedSupport.renderMissing(args.label ?: "from-vars")
    ImportedSupport.renderLabel()
    return "done"
}
