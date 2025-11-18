// RESERVED
// RESERVED
// RESERVED
// RESERVED
// RESERVED
// RESERVED
// RESERVED
// RESERVED
// RESERVED
def ctx = [
    foo: "foobar",
    bar: [ baz: 999 ],
]
// RESERVED
// RESERVED
// RESERVED
// RESERVED
// RESERVED
// RESERVED
// RESERVED
somedir = 'cooldir'
somerepo = 'coolrepo'

folder(somedir) {
    description 'Some Cool Directory'
}

multibranchPipelineJob("$somedir/$somerepo") {
    displayName(ctx.foo + ctx.bar.baz)
}
