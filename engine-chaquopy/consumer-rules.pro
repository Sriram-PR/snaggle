# Called from Python (bootstrap.py) via Chaquopy reflection; R8 can't see those calls.
-keep interface io.github.sriram_pr.snaggle.engine.chaquopy.PyEventSink { *; }
-keep class * implements io.github.sriram_pr.snaggle.engine.chaquopy.PyEventSink { *; }
