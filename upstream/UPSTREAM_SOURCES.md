# Vendored upstream source

GPL-3.0 source imports pinned to exact upstream revisions:

- BVD / BiliDownload: https://github.com/KafuuNeko/BiliDownload
  - Revision: f33bd19b131851990e63619bc7d4ef2b044f14f3
  - Imported the Android source package, resources, manifest, Room schemas, native sources and build metadata. Download implementation is in common/download, common/manager, common/room, service and notification.
- bilimiao2: https://github.com/10miaomiao/bilimiao2
  - Revision: eff134d715c3fd13202ebd029261d1fbc33d2171
  - Imported complete bilimiao-comm, bilimiao-download, bilimiao-compose, bilimiao-cover, bilimiao-grpc and danmaku-engine modules, PlaybackService and Gradle metadata.

Original license texts are retained beside each imported source tree. Preserve all upstream copyright notices and GPL-3.0 terms in redistributed builds.
