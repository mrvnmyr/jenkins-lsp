// RESERVED

import jenkinslsp.support.ImportedSupport

// Exercise go-to-definition from a vars script into an imported src class.
def call(Map args=[:]) {
    return ImportedSupport.renderLabel(args.label ?: "from-vars")
}
