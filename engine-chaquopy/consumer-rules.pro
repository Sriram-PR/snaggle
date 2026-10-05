# Called from Python (bootstrap.py) via Chaquopy reflection; R8 can't see those calls.
-keep interface dev.placeholder.engine.chaquopy.PyEventSink { *; }
-keep class * implements dev.placeholder.engine.chaquopy.PyEventSink { *; }
