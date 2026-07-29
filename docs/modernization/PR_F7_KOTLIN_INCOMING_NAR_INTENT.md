# PR F7 — Kotlin incoming NAR intent gate

`IncomingNarIntent` is now a Kotlin object with `@JvmStatic` overloads, so the
remaining Java Activity and Service callers retain their exact static call
surface. The external policy is unchanged: only `ACTION_VIEW`, an HTTPS URI,
a nonempty host, and a `.nar` or `.zip` path are accepted. In particular,
`file:` URIs are still rejected.

The Java implementation is retained solely below `legacy/src/` for the frozen
Ant build overlay. Gradle compiles only the Kotlin gate.

The migration has a source contract for Java-static exposure and strict URI
policy, runs the existing target-SDK security contract, and compiles the mixed
Kotlin/Java Android sources.
