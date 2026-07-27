# PR G3: Compose static surface image layer

Compose now has a conservative visual ownership path for static base shell
surfaces. It uses the `SurfaceDefinition` snapshot and reproduces the legacy
upper-left transparency-key behavior. The retained `SakuraView` stays in place
for hit testing and as the fallback compositor for element surfaces and all
animation states.

This deliberately moves one concern at a time: image presentation first, then
collision input, then animation scheduling. No ghost behavior is downgraded
when the Compose policy cannot reproduce it exactly.
