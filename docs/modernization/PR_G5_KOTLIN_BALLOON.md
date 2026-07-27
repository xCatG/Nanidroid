# PR G5 — Kotlin Balloon compatibility view

The Gradle product now implements `Balloon` in Kotlin.  It preserves the
three XML/Java constructors and the legacy `setText(String)` behavior: URL
linkification, overflow detection, and bottom-line scrolling.  The Compose
host continues to choose this view only when links or scrolling make retained
interaction necessary.

The frozen Ant lane copies `legacy/src/.../Balloon.java` over the Gradle
source tree.  This is intentional compatibility scaffolding: Ant cannot
compile Kotlin, while the shipped modern Android product has no Java Balloon
implementation.
