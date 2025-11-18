// RESERVED

def call(Map args=[:], Closure body = null) {
    if (body) {
        body()
    }
    bar(stageName: args.stageName ?: "foo-stage")
    bar.helperFromBar(args.stageName ?: "foo-stage")
    return args.stageName ?: "foo-stage"
}

def helperStep(String label) {
    return label?.trim() ?: ""
}

def deepHelper(Map config=[:]) {
    if (config.forwardToBar) {
        bar.helperFromBar(config.forwardToBar)
    }
    return config.message ?: "none"
}

def askBarForDouble(Number n) {
    return bar.doubleHelper(n ?: 0)
}
