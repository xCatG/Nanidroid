# PR G6 — Kotlin KeroView

The modern Android source set now implements `KeroView` in Kotlin.  It remains
open for Java test doubles and keeps the three XML inflation constructors.  Its
only specialized behavior continues to select Kero surfaces from the shared
surface manager.

`legacy/src/.../KeroView.java` is a Java-only overlay for the frozen Ant lane;
the Gradle app contains no Java `KeroView` implementation.
