Jenkins LSP Specification
=========================

Overview
--------

Jenkins LSP is a small Groovy 2.5.23–based language server that speaks the
Language Server Protocol over JSON-RPC 2.0 on stdin/stdout. It is intended
to be embedded by editors to power Jenkins pipeline and Job DSL authoring,
but it also works on standalone Groovy scripts.

Goals
-----

* Be self-contained (single shaded JAR, no external index).
* Provide fast, useful diagnostics while users type.
* Offer reliable “Go to definition” for common pipeline-style Groovy
  patterns.
* Offer simple, predictable member completion after a qualifier and ``.``.
* Prefer robustness and clear behaviour over deep static analysis.

Non-goals
---------

* No project-wide indexing, symbol search or cross-file resolution.
* No hover, signature help, rename, code actions or formatting.
* No attempt to fully model dynamic meta-programming; only pragmatic
  handling of common map/property patterns is provided.

Protocol behaviour
------------------

The server implements a minimal but complete LSP surface for single-file
editing:

* ``initialize``
  - Announces support for:
    * ``textDocumentSync`` (full document sync).
    * ``definitionProvider``.
    * ``completionProvider`` with ``.`` as a trigger character.

* ``textDocument/didOpen`` and ``textDocument/didChange``
  - Track the full text of the opened document.
  - Re-parse the source after each change.
  - Publish diagnostics that reflect the current state of the file.

* ``textDocument/definition``
  - Given a position, returns at most one location that represents the
    definition of the symbol under the cursor.
  - If the position does not correspond to a resolvable symbol, no
    location is returned.

* ``textDocument/completion``
  - Provides completion items for qualified member access (``foo.``).
  - Only triggers when the cursor is placed after a qualifier and a dot.

Parsing and diagnostics
-----------------------

* The server parses the current document as Groovy 2.5 source.
* Groovy compilation errors are surfaced as LSP diagnostics with proper
  0-based line/column positions.
* A simple “missing return” check is applied:
  - For methods with a non-``void`` declared return type, if no ``return``
    is present, an additional diagnostic is emitted.
* When a user is in the middle of typing a trailing dot (e.g. ``obj.``),
  the server suppresses the noisy “unexpected token: .” error so that
  diagnostics stay stable while completing member names.

Definition resolution
---------------------

“Go to definition” focuses on cases that are common in Jenkins pipelines
and Groovy scripts:

* Locals and parameters
  - Resolves variables and parameters declared in the current method or
    block when the cursor is on a reference to them.

* Top-level symbols
  - Resolves top-level variables, methods and classes declared in the
    current script.

* Instance members
  - Resolves ``this.member`` to the corresponding field, property or
    method in the enclosing class.
  - Resolves ``qualifier.member`` when the qualifier can be associated
    with a known type from the current script (for example, variables
    initialised with ``new Foo()``).

* Dynamic “context” style access
  - Handles idioms such as ``ctx.foo`` that are common in pipelines:
    * Keys defined in map literals assigned to a variable (e.g.
      ``ctx = [foo: 1, bar: 2]``) can be used as definition targets for
      ``ctx.foo``.
    * Direct assignments like ``ctx.foo = ...`` (including quoted
      property names) can also act as the definition site.
  - When several candidates exist, the last relevant top-level occurrence
    is preferred, reflecting typical “configure at the top, use below”
    patterns.

* GString support
  - Inside GStrings, references like ``"$var"`` or ``"${var}"`` are
    treated as references to the variable ``var``; “Go to definition” on
    ``var`` jumps to its declaration.
  - Positions inside ordinary double-quoted strings (that are not
    GStrings) are ignored.

* Comments and non-code positions
  - Positions inside ``//`` line comments are ignored.
  - If the cursor does not correspond to a meaningful symbol (for
    example, on whitespace or punctuation), no definition is returned.

Completion
----------

Member completion is intentionally narrow and predictable:

* Triggering
  - Completion is provided only when the text immediately before the
    cursor matches the pattern ``<identifier> . <optional prefix>``.
  - The server does not offer global or keyword completion.

* Qualifier understanding
  - The qualifier before the dot (``foo`` in ``foo.bar``) is resolved
    using the same information as “Go to definition”:
    * ``this`` is treated as the enclosing class instance.
    * Locals and top-level variables are used when their type can be
      inferred from the current script.
    * If the qualifier name matches a class declared in the same file,
      its members are offered.

* Completion items
  - Methods, fields and properties from the resolved type (and its
    super-classes/interfaces) are surfaced as completion items.
  - Overloaded methods are presented as a single item per method name
    with a compact signature summary.
  - A simple case-insensitive prefix filter is applied based on the text
    after the dot.
  - The replacement range is restricted to the identifier after the dot,
    so the qualifier and surrounding code are left untouched.

String and layout handling
--------------------------

To behave sensibly on real-world pipeline scripts, the server includes
basic text understanding:

* It distinguishes between code, comments and various Groovy string
  forms (including GStrings, slashy, and dollar-slashy strings) so that
  braces and other delimiters inside strings do not confuse analysis.
* It maintains an approximate notion of “brace depth” per line so that
  it can distinguish top-level assignments (e.g. configuration blocks at
  file level) from nested constructs.
* Variable declarations are recognised even when the type and name are
  split across multiple lines in common Groovy formatting styles.

Logging and DEBUG behaviour
---------------------------

All logging goes through a small central logger:

* Normal logs
  - Printed to stderr with a ``[jenkins-lsp]`` prefix.
  - Used for high-level events such as requests, published diagnostics
    and success/failure of major operations.

* Debug logs
  - Intended for noisy internal traces that help understand why a
    particular definition or completion was chosen.
  - Controlled by the ``DEBUG`` environment variable:
    * Any non-empty, non-``0`` value enables debug logging.
    * When disabled, debug logging is effectively a no-op and has minimal
      runtime cost.

With debug logging enabled, the server emits additional information about
how it resolved qualified member accesses (e.g. ``ctx.foo``) and other
symbols, which can be useful when writing tests or diagnosing unusual
scripts.
