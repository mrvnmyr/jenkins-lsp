// Integration test fixture mirroring poo.groovy to exercise cross-file vars lookups.

def call(Map args=[:], Closure body = null) {
    foo(stageName: args.stageName ?: "poo-stage") {
        foo.helperStep("body-from-poo")
    }
    foo.helperStep("second-call")
    foo.deepHelper([
        message: "from-poo",
        forwardToPoo: "buzz"
    ])
    if (body) {
        body()
    }
    return args.stageName ?: "poo-stage"
}

def helperFromPoo(String label) {
    return label?.reverse()
}

def doubleHelper(Number n) {
    return (n ?: 0) * 2
}
