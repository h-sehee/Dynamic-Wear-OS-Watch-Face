# --- RewindWatch R8 rules ---
# Manifest-declared components (MyWatchFace, MainActivity) are kept automatically.
# Watch Face Format / androidx.wear.watchface ship their own consumer rules.

# UserStyle settings are looked up by string id at runtime; keep their
# option classes intact so the editor <-> service handshake keeps working.
-keep class androidx.wear.watchface.style.** { *; }

# We log exception class names; keep them readable in logcat.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
