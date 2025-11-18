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

String insufficientIfReturn(String s) {
    if (s) {
        return "TRUE"
    }
}

String insufficientSwitchReturn(String s) {
    switch (s) {
    case "foo":
        return "foo"
        break;
    case "bar":
        return "bar"
        break;
    default:
        break;
    }
}
