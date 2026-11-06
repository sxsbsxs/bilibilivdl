# Figma handoff

This folder contains editable SVG icon sources. Import the SVG files into Figma (File → Place image or drag-and-drop); each SVG remains vector-editable after import. The Android XML vector drawables in `app/src/main/res/drawable` are the runtime equivalents.

A native `.fig` binary is not generated in this repository because Figma owns that document format. To create the editable design file, create a Figma file and import the SVG assets, then use `docs/UI_DESIGN_SYSTEM.md` as the component and token specification.

## Included assets
- search.svg
- download.svg
- refresh.svg
- settings.svg

## Runtime notes
- Android adaptive launcher icon: `mipmap-anydpi-v26/ic_launcher.xml` + `drawable/ic_launcher_foreground.xml`
- Notification small icon: `drawable/ic_notification.xml` (white monochrome vector)
- Prefer Android Vector Drawable XML in the app; SVGs are source/handoff assets only.
- App store PNG exports (512×512 Play icon and 1024×1024 iOS icon) are separate export deliverables; this Android-only project does not include an iOS target.
