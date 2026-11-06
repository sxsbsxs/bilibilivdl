
# BiliVdl UI Design System

## Visual direction
A Bilibili-inspired native Android client: calm neutral surfaces, restrained #FB7299 accent, compact icon actions and a clear content hierarchy. This is an original design system, not a pixel-for-pixel copy.

## Design source and runtime
- Figma is the recommended editable source for page layouts, components and handoff annotations.
- Android XML + Kotlin + Material 3 are the runtime implementation. Keep playback, downloads, navigation and media handling native; do not replace them with WebView.
- SVG is the source format for custom icons. Export Android Vector Drawable XML into res/drawable for runtime.
- WebP is preferred for covers and thumbnails; keep JPEG fallback for compatibility.

## Color tokens
| Token | Light | Dark |
|---|---|---|
| brand | #FB7299 | #FF82A8 |
| background | #F6F7F8 | #101114 |
| surface | #FFFFFF | #191A1F |
| elevated | #F1F2F3 | #24262D |
| primary text | #18191C | #F1F2F3 |
| secondary text | #9499A0 | #A6ABB3 |
| outline | #E7E8EB | #30333B |

Legacy Android resource names are retained to preserve existing view references. The values-night resource folder overrides the same tokens for system dark mode.

## Layout rules
- Use a 4dp spacing base; common steps are 4 / 8 / 12 / 16 / 24dp.
- Standard cards: 14dp corners; feature surfaces: 16–18dp; search field: pill shape.
- Page title: 23–24sp; section title: 17–18sp; body: 13–14sp; metadata: 10–12sp.
- Video cards: 16:9-style cover, duration badge on image, two-line title, compact creator row and muted metadata.
- Icon actions: 20–22dp, borderless feedback; avoid filling every action with accent color.
- Comments: avatar + author/time row, readable body line-height, muted metadata and lightweight reply actions.
- Navigation: stable bottom bar; selected state uses brand accent only.

## Assets and handoff
- design/icons/*.svg: editable vector source icons.
- app/src/main/res/drawable/*.xml: Android runtime vector assets.
- Lottie JSON is optional and must have a static fallback.
- Notifications use transparent monochrome white small icons.
- Figma remains the editable visual handoff; repository tokens and native resources are the implementation source of truth.

## Review checklist
- No clipped Chinese text at system font scale.
- Verify light and dark mode on every screen.
- Verify compact-width devices and landscape playback.
- Preserve existing view IDs when changing layouts so Kotlin bindings remain intact.
