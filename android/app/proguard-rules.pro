# R8 rules for when isMinifyEnabled is switched on (not yet, see build.gradle.kts),
# on top of what the libraries ship themselves.

# ML Kit finds its components by class name from the manifest and creates them
# through the no-argument constructor. The library only keeps the classes; in
# R8 full mode that no longer keeps their constructors, and the scanner's text
# recognition fails at start ("Could not instantiate ...Registrar").
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# Coil: R8 9 can drop super calls into Target's default methods when it merges
# classes (https://issuetracker.google.com/issues/524864608). Coil's own rule
# only covers View targets; in Compose every AsyncImage stayed empty without a
# single log line. Coil is small, so keep it whole.
-keep class coil3.** { *; }
