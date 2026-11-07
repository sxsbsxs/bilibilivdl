# Data Binding IDs are passed from the app into BVD's generated binding classes.
# Keep the BR owner module's ID table when a consuming app enables R8 shrinking.
-keep class cc.kafuu.bilidownload.BR { *; }
