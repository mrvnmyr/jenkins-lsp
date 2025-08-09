import groovy.transform.Field

// NOTE : this file ought _NOT_ be runnable without errors with groovy 2.5 interpreter

class Foo {}

void methodReturningFromVoid() {
    return "String"
}

String methodMissingReturn() {}

String methodReturnWrongType() {
    return new Foo()
}

def methodReturnUnknownIdentifier() {
    return fffffffff
}
