Jenkins LSP Specification
=========================

Overview
--------

Jenkins LSP is a small Groovy 2.5.23 based language server that speaks
the Language Server Protocol over JSON-RPC 2.0 on stdin/stdout. It is
intended to be embedded by editors to power Jenkins pipeline and Job DSL
authoring, but it works on any standalone Groovy script.

Goals
-----

* Be self contained (single shaded JAR, no external index).
* Provide fast but helpful diagnostics while users type.
* Offer reliable "Go to definition" for the most common patterns in
  pipeline-style Groovy.
* Offer simple member completion after a qualifier and ``.``.
* Prefer robustness and clear behaviour over deep static analysis.

Protocol behaviour
------------------

The main entry point is :class:`jenkinslsp.LspServer`. It drives a
simple loop that reads and writes JSON-RPC messages via
:class:`JsonRpcTransport`.

Supported methods:

* ``initialize``

  - Announces support for ``textDocumentSync`` (full),
    ``definitionProvider`` and ``completionProvider`` with ``.`` as a
    trigger character.

* ``textDocument/didOpen`` and ``textDocument/didChange``

  - Cache the last full document as text.
  - Re-parse the source and publish diagnostics.

* ``textDocument/definition``

  - Compute a single location for the symbol under the cursor.

* ``textDocument/completion``

  - Return completion items for qualified member access.

Parsing and diagnostics
-----------------------

Parsing is implemented in :class:`Parser` using Groovy 2.5 compiler
APIs and a custom :class:`ParseResult` wrapper.

Key points:

* The original source text is preserved, but a patched copy may be used
  internally to make a trailing ``.`` parseable (``Bar.`` becomes
  ``Bar.__LSP_STUB__``) so that an AST still exists during member
  completion and GoTo tests.

* Any ``CompilationFailedException`` is converted into LSP-style
  diagnostics, with line/column converted from 1-based to 0-based.

* A small post-pass checks method declarations: if a method has a
  non-``void`` declared return type and there is no ``return`` in the
  body, a diagnostic is emitted.

* LspServer applies a heuristic filter to hide the single "unexpected
  token: ." error that would be produced by an unfinished trailing dot
  when the user is mid-typing.

Definition resolution
---------------------

Definition resolution is driven by :meth:`LspServer.handleDefinition`
and uses several layers of heuristics:

1. Input validation:

   * If no ``lastParsedUnit`` is available or the position is outside
     the current text, the request is rejected.
   * If the cursor is inside ``//`` line comments, no lookup is
     performed.
   * If the cursor is inside a normal double-quoted string (not a
     GString placeholder), the request is ignored.

2. GString support:

   * When the cursor is inside a GString but not inside a ``${...}``
     placeholder, :meth:`StringHeuristics.gstringVarAt` tries to detect
     a ``$var`` token and treats ``var`` as the identifier at the
     cursor.

3. Qualified member resolution:

   * :meth:`MemberResolver.resolveQualifiedProperty` looks on the
     current line for patterns of the form ``qualifier.member`` with
     optional spaces. The cursor can be on the member name, just after
     it, on the dot, or in the whitespace between dot and member.

   * ``this.member`` is resolved against the containing class using
     :meth:`AstNavigator.findFieldOrPropertyInHierarchy`.

   * For other qualifiers, the server builds a local-variable map from
     the enclosing method with
     :meth:`AstNavigator.collectLocalVariables`. The declared type is
     refined by scanning for initialisers like ``def ctx = new Foo()``.

   * If no local matches, top-level variables are inspected with
     :meth:`AstNavigator.findTopLevelVariableWithType`. Their type is
     also refined by detecting ``new Foo()`` assignments.

   * When the resulting type corresponds to a ``ClassNode`` in the same
     :class:`SourceUnit`, the class hierarchy is walked with
     :meth:`AstNavigator.findFieldOrPropertyInHierarchy` to locate
     fields, properties or methods. For methods, simple argument-kind
     inference (:meth:`StringHeuristics.extractGroovyCallArgKinds`) is
     used to pick the best overload.

4. Dynamic map and property heuristics:

   When no concrete static type is available, the resolver assumes that
   qualifiers such as ``ctx`` are dynamic data structures and tries:

   * Map-literal keys: ``resolveMapKeyFromAssignment`` searches for
     assignments like ``ctx = [foo: 1, bar: 2]`` (possibly multi-line)
     and for ``foo:`` style keys inside the literal using a brace-depth
     aware scanner (:meth:`StringHeuristics.computeBraceDepths`).

   * Top-level property assignments:
     ``resolveTopLevelPropertyAssignment`` searches for assignments of
     the form ``ctx.foo = ...`` (including quoted property names) and
     prefers true top-level occurrences.

   * A final relaxed pass searches a bounded window after the
     assignment for a bare ``foo:`` key inside the same map literal to
     compensate for unusual formatting.

   In all cases, the *last* valid top-level match is preferred to
   reflect typical script patterns.

5. Unqualified identifiers:

   If no qualified access applies, the server:

   * searches locals/parameters in the current method;
   * resolves unqualified method calls against the enclosing class via
     :meth:`AstNavigator.findFieldOrPropertyInHierarchy`;
   * decides whether an identifier is being used as a type (casts,
     ``new``, ``extends``, ``implements`` or known class names);
   * otherwise looks for top-level variables, methods and classes.

Completion
----------

Completion is implemented in :class:`CompletionEngine` and uses the same
AST and local-variable infrastructure as definition:

* It only triggers when the token before the cursor matches
  ``<identifier> . <optional prefix>``.

* The qualifier is resolved to a :class:`ClassNode` using:

  - ``this`` => containing class;
  - locals/top-level variables with type refinement via ``new Foo()``;
  - or by matching the qualifier name directly to a class declared in
    the same unit.

* The class hierarchy is then walked, collecting:

  - methods (grouped by name so that overloads collapse into a single
    completion item with a compact signature summary);
  - properties;
  - fields.

* A simple case-insensitive prefix filter is applied on the member
  names. The replacement range is restricted to the identifier after
  the ``.`` so that type names and qualifiers are not touched.

String and brace heuristics
---------------------------

The :class:`StringHeuristics` helper encapsulates all pure string
operations:

* smart column calculation for variable declarations, including
  multi-line ``Type`` / ``name`` layout;

* Groovy keyword detection;

* GString utilities:

  - detecting whether a position is inside a normal string;
  - recognising ``${...}`` placeholders;
  - extracting the variable name for ``$var`` tokens at the cursor;

* heuristic extraction of "argument kinds" (Map, Closure, String, etc.)
  from a single callsite line;

* a robust brace-depth computation that ignores braces inside quoted
  strings, slashy and dollar-slashy strings, and block/line comments.
  The depth recorded for each line is the depth at the *start* of that
  line and is used extensively by the top-level variable and property
  scans.

Logging and DEBUG behaviour
---------------------------

All logging goes through :class:`Logging`:

* ``log`` is always emitted to stderr with a ``[jenkins-lsp]`` prefix and
  is reserved for high-level events (requests, major decisions,
  diagnostics).

* ``debug`` is intended for noisy internal traces. It is gated by
  the ``DEBUG`` environment variable:

  - any non-empty, non-``0`` value enables debug logging;
  - when disabled, ``Logging.debug`` is a cheap no-op.

Additional debug lines are present around the qualified member and
map-key resolution paths so that, when ``DEBUG`` is enabled, it is
possible to see exactly which heuristic produced the final GoTo target.

Non-goals
---------

* No project-wide indexing, symbol search or cross-file resolution.
* No hover, signature help, rename, code actions or formatting.
* No attempt to model dynamic meta-programming beyond the pragmatic
  map/property heuristics described above.
