# Checkpoint 2.1: compact UI foundation

## Why this checkpoint exists

The first installation UI proved the interaction model but looked like a
collection of large Material defaults. The persistent title block and four
pill-shaped navigation buttons consumed too much vertical space, typography
competed with controls, and stacked cards made every element look equally
important.

This checkpoint keeps the behavior and replaces the visual hierarchy.

## Product direction

uDroid now uses a compact Linux control-panel vocabulary:

- a small wordmark bar instead of a permanent marketing header;
- bottom navigation with a thin active rail instead of four pill buttons;
- one page title and one sentence of context;
- white working surfaces on a cool neutral canvas;
- Ubuntu orange only for distro identity;
- monospace only for machine-facing information and terminal output;
- the terminal drawer as the single visually strong signature.

## Tokens

| Role | Value |
| --- | --- |
| Canvas | `#F4F7F5` |
| Working surface | `#FFFFFF` |
| Primary ink | `#17211C` |
| Secondary ink | `#637068` |
| uDroid green | `#226548` |
| Soft green | `#E4EFE9` |
| Hairline | `#DCE4DF` |
| Ubuntu identity | `#E95420` |
| Terminal | `#0B1410` |
| Terminal success | `#A2F4C9` |

The application uses the Android sans-serif family for interface copy and
monospace for IDs, stage counters, percentages, hashes, and logs. The explicit
type scale avoids relying on oversized Material defaults.

## Layout contract

```text
┌───────────────────────────┐
│ u  uDroid           LOCAL │  compact app bar
├───────────────────────────┤
│ Page title                │
│ one sentence              │
│                           │
│ focused working surface   │
│                           │
├───────────────────────────┤
│ ━                         │  active page rail
│ Home  Linux Device  Logs  │
└───────────────────────────┘
```

Installation adds a five-segment stage rail above the exact byte or step
progress. The stage rail answers “where am I?” while the thin progress line
answers “how far through this stage plan am I?”

The terminal opens over the bottom half of the working area. Bottom navigation
remains visible so users retain orientation; the installation continues to be
owned by the future service, not by the drawer.

## Acceptance checks

- Home, catalogue, installation, and terminal states were inspected on the
  Pixel 6a at its enlarged system font scale.
- Navigation labels remain on one line.
- Catalogue selection and advanced-image controls remain above the fold.
- Stage title, percentage, and details do not collide.
- The terminal remains readable and preserves the shared event stream.
- Unit tests, Android lint, and debug APK assembly remain required gates.
