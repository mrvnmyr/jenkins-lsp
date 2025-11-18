// RESERVED

def call(Map args=[:], Closure body = null) {
    foo(stageName: args.stageName ?: "bar-stage") {
        foo.helperStep("body-from-bar")
    }
    foo.helperStep("second-call")
    foo.deepHelper([
        message: "from-bar",
        forwardTobar: "buzz"
    ])
    if (body) {
        body()
    }
    return args.stageName ?: "bar-stage"
}

def helperFromBar(String label) {
    return label?.reverse()
}

def doubleHelper(Number n) {
    return (n ?: 0) * 2
}
