# BiliVdl Design System

Version: 1.0  
Status: Initial implementation  
Product: BiliVdl — video discovery, playback and download manager

## Product identity

BiliVdl is an independent video client and downloader. Its interface must have its own visual identity. Upstream projects may inform technical architecture, but their screen compositions, branded assets, illustrations, copy and distinctive visual treatments are not copied.

## Visual principles

- Content first: video thumbnails and titles receive the strongest visual hierarchy.
- Calm utility: playback and download controls are compact, predictable and easy to scan.
- Pink is an accent: reserve it for selected navigation, primary actions, progress and important states.
- Clear surfaces: use a neutral canvas, white or dark-neutral content surfaces, and subtle separators.
- Dense, not cramped: keep useful metadata visible while preserving consistent spacing.
- Progressive disclosure: advanced download options belong in sheets or detail sections, not permanently crowded onto cards.
- Native behavior: respect Android system bars, font scaling, accessibility semantics and 48dp touch targets.

## Color tokens

| Token | Light | Dark |
|---|---|---|
| Primary | #FB7299 | #FF82A8 |
| Primary pressed | #E85D87 | #FB7299 |
| Primary container | #FFE3EC | #3B202B |
| Canvas | #FFF5F8 | #101114 |
| Surface | #FFFFFF | #191A1F |
| Muted surface | #F1F3F6 | #24262D |
| Main text | #24252A | #F1F2F3 |
| Secondary text | #9296A0 | #A6ABB3 |
| Outline | #E9EBEF | #30333B |

## Spacing

Use a 4dp base scale: 4, 8, 12, 16, 20, 24 and 32dp. Standard phone page inset is 16dp. Use 12–16dp between related content groups and 20–24dp between major sections.

## Shape and elevation

- Small controls: 8–12dp.
- Cards and grouped surfaces: 14–18dp.
- Sheets and prominent panels: 20–24dp top corners.
- Prefer tonal surface separation over heavy shadows or decorative borders.

## Typography

Use the platform sans-serif with Material typography roles. Typical hierarchy:
- Screen title: titleLarge / 20–22sp, medium or semibold.
- Section title: titleMedium / 16–18sp, semibold.
- Video title: bodyMedium / 14sp, medium.
- Metadata: bodySmall / 11–12sp, secondary color.
- Never hard-code fixed-height text containers; allow Chinese text and accessibility font scaling to wrap.

## Components

- Bottom navigation: three destinations (发现、下载、我的); restrained selected indicator; no oversized labels.
- Search: neutral filled surface, concise hint, small action affordance.
- Video card: 16:9 thumbnail, title up to two lines, creator and view metadata below; avoid enclosing every metadata line in a chip.
- Player: video surface dominates; controls fade or remain compact; details and comments follow below.
- Download task: title and state first, progress and size second, compact pause/resume and overflow actions.
- Buttons: text/tonal by default; filled pink only for the single most important action on a screen.
- Chips: use neutral surfaces; pink only for selected filters or active quality.

## Interaction and accessibility

- Interactive targets must remain at least 48dp even when the visible icon is smaller.
- Use semantic Material color roles and paired on-colors.
- Support system dark mode and large font settings.
- Show loading, empty, error and completed states in the same layout position.
- Do not rely on color alone to communicate download state.

## Implementation

- XML/View screens use res/values and res/values-night resources.
- New Compose screens use com.sxsbsxs.bilivdl.ui.theme.BiliVdlTheme.
- Keep tokens synchronized across XML and Compose. Review real device screenshots before calling a screen complete.
