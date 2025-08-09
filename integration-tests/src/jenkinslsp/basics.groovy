import groovy.transform.Field

// NOTE : this file ought be runnable without errors with groovy 2.5 interpreter

@Field
def topLevelVar = "Jenkins LSP Active"

void log(String msg) {
    System.out.println(msg)
}

class Foo {
    String foo = "yeh";

    void heh() {
        log(this.foo)
    }
}

class Bar extends Foo {
    String bar() {
        return this.foo
    }
}

def topLevelMethod(String foo) {
    log(topLevelVar);

    def rabauke = "rabauke"

    // foo
    def f = new Foo()
    log(f.foo)
    log(foo);
    log(rabauke);

    return null
}


def p = new Bar()
System.out.println("Bar:bar(): " + p.bar())
topLevelMethod("a red herring")

void secondTopLevelMethod(String foo) {
    String rapookee = "rapookee"
    Float \
    what = 90

    log(rapookee)
    assert (what + 10.0).toString() == "100.0"
}
secondTopLevelMethod("nanana")

log("topLevelVar: ${topLevelVar}")
