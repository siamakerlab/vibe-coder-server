---
name: Vibe Coder Server
description: A quiet, dense, browser-first operations UI for Android project creation, AI console work, builds, devices, and artifacts.
colors:
  base-void: "#0f1419"
  shell-panel: "#1a1f29"
  work-surface: "#1e2532"
  rule-line: "#2a3142"
  readable-ink: "#e4e8ef"
  quiet-ink: "#8b94a8"
  action-blue: "#5e9eff"
  action-blue-hover: "#7eb4ff"
  danger-red: "#ff6b6b"
  warning-amber: "#ffa94d"
  success-green: "#69db7c"
  waiting-yellow: "#fab005"
  halted-violet: "#b197fc"
  status-off: "#3a4150"
  pt-rail-bg: "#0d1018"
  pt-rail-surface: "#131722"
  pt-rail-control: "#0c0f17"
  pt-rail-control-alt: "#121722"
  pt-rail-hover: "#1a1f2c"
  pt-rail-line: "#1f2330"
  pt-rail-line-strong: "#2a3145"
  pt-rail-muted: "#5a6175"
  pt-danger-hover: "#2c1a1a"
  pt-danger-line: "#3a2424"
  pt-danger-text: "#ff9e9e"
  pt-provider-claude-bg: "#1c1510"
  pt-provider-claude-line: "#b45309"
  pt-provider-claude-text: "#fbbf24"
  pt-provider-codex-bg: "#101827"
  pt-provider-codex-line: "#2563eb"
  pt-provider-codex-text: "#93c5fd"
  pt-provider-opencode-bg: "#0f1c1c"
  pt-provider-opencode-line: "#0d9488"
  pt-provider-opencode-text: "#5eead4"
  context-read: "#3a82f6"
  context-create: "#2dd4bf"
  context-input: "#ffb86b"
  pt-context-read: "#3a82f6"
  pt-context-create: "#2dd4bf"
  pt-context-input: "#ffb86b"
  pt-control-button-bg: "#1f2937"
  pt-control-button-bg-hover: "#26303f"
  pt-control-button-text: "#cbd5e1"
  pt-control-button-line: "#2b3648"
  pt-automation-primary-bg: "#1e40af"
  pt-automation-danger-bg: "#7f1d1d"
  pt-automation-danger-line: "#991b1b"
  pt-positive-accent: "#34d399"
  pt-ink-on-fill: "#ffffff"
  pt-mask-solid: "#000"
  console-bg: "#0a0d12"
  console-sticky-bg: "#121a28"
  console-scroll-thumb: "#3b3b3b"
  console-scroll-thumb-hover: "#555"
  console-assistant-accent: "#5ed3a1"
  console-tool-accent: "#cfa763"
  console-tool-out-accent: "#8ea0c2"
  console-gauge-vibe: "#16a34a"
typography:
  display:
    fontFamily: "-apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Apple SD Gothic Neo, sans-serif"
    fontSize: "24px"
    fontWeight: 700
    lineHeight: 1.3
  headline:
    fontFamily: "-apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Apple SD Gothic Neo, sans-serif"
    fontSize: "18px"
    fontWeight: 700
    lineHeight: 1.3
  title:
    fontFamily: "-apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Apple SD Gothic Neo, sans-serif"
    fontSize: "15px"
    fontWeight: 600
    lineHeight: 1.3
  body:
    fontFamily: "-apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Apple SD Gothic Neo, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "-apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Apple SD Gothic Neo, sans-serif"
    fontSize: "12px"
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: "0.5px"
  mono:
    fontFamily: "ui-monospace, SFMono-Regular, SF Mono, Menlo, Consolas, monospace"
    fontSize: "12.5px"
    fontWeight: 400
    lineHeight: 1.55
rounded:
  hairline: "2px"
  xs: "3px"
  sm: "4px"
  md: "6px"
  lg: "8px"
  badge: "9px"
  console-pill: "11px"
  console-bubble: "14px"
  pill: "999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "20px"
  xxl: "24px"
  page: "32px"
components:
  button-primary:
    backgroundColor: "{colors.action-blue}"
    textColor: "#ffffff"
    rounded: "{rounded.md}"
    padding: "10px 16px"
  button-secondary:
    backgroundColor: "{colors.work-surface}"
    textColor: "{colors.readable-ink}"
    rounded: "{rounded.md}"
    padding: "8px 16px"
  chip-neutral:
    backgroundColor: "{colors.base-void}"
    textColor: "{colors.quiet-ink}"
    rounded: "{rounded.pill}"
    padding: "4px 10px"
  card:
    backgroundColor: "{colors.work-surface}"
    textColor: "{colors.readable-ink}"
    rounded: "{rounded.md}"
    padding: "20px"
  input:
    backgroundColor: "{colors.base-void}"
    textColor: "{colors.readable-ink}"
    rounded: "{rounded.md}"
    padding: "10px 12px"
---

# Design System: Vibe Coder Server

## 1. Overview

**Creative North Star: "The Operations Console"**

Vibe Coder Server is a practical, quiet, dense product UI. It is used while Android projects, AI agents, Gradle builds, device sessions, file edits, and downloadable artifacts are all active at once, so the interface must feel steady under load instead of expressive for its own sake.

The system is dark, compact, and task-first. The left shell, project tabs, console, cards, forms, tables, chips, and status pills share one restrained vocabulary: flat surfaces, 1px borders, 6px corners, system typography, blue action emphasis, and semantic state colors. Familiarity is the quality bar; unusual UI is allowed only when it solves a workflow problem.

This system explicitly rejects SaaS landing-page composition, excessive card grids, decorative glass or gradients, large empty mobile-app spacing, inconsistent one-off controls, duplicated app chrome, nested headers, and modal-first workflows that hide the current task.

**Key Characteristics:**
- Dense operational layout with clear regions: shell, content, tabs, console, rail, and toolbars.
- Dark neutral surfaces with one primary action blue and strict semantic state colors.
- System sans for product UI, monospace only for logs, code, IDs, paths, prompts, and terminal-like output.
- Single-shell ownership: headers, sidebars, tabs, rails, and back controls must not duplicate across embedded pages.
- Mobile remains operational: navigation may collapse or scroll, but primary console/build/settings workflows must stay reachable under zoom and virtual keyboards.

## 2. Colors

The palette is a restrained dark operations palette: cool charcoal surfaces, readable near-white text, muted secondary text, one blue action channel, and explicit state colors.

### Primary
- **Action Blue**: the only primary action and selection color. Use it for submit actions, active tabs, focus rings, links, progress fill, and the current project/action affordance.
- **Action Blue Hover**: the hover and raised emphasis version of Action Blue. Use it only as a response to interaction.

### Secondary
- **Success Green**: running, connected, completed, ready-to-use, and positive completion states.
- **Warning Amber**: warnings, quota pressure, caution banners, and non-fatal attention.
- **Waiting Yellow**: queued, waiting, or paused-but-recoverable project state.
- **Halted Violet**: stopped, cancelled, interrupted, or rate-limit halted state.
- **Danger Red**: destructive actions, failed jobs, invalid states, and blocking errors.

### Neutral
- **Base Void**: the app background and code/log base surface.
- **Shell Panel**: sidebar, table headers, elevated shell regions, and secondary dark panels.
- **Work Surface**: cards, default buttons, active nav rows, form groups, and contained modules.
- **Rule Line**: borders, dividers, table rows, input strokes, and low-emphasis outlines.
- **Readable Ink**: primary text and important labels.
- **Quiet Ink**: secondary labels, helper text, inactive nav items, timestamps, and metadata.

### Named Rules

**The One Accent Rule.** Action Blue is for primary actions, focus, links, and current selection. It is not decoration.

**The State Means State Rule.** Success Green, Warning Amber, Waiting Yellow, Halted Violet, and Danger Red are reserved for status and feedback. Do not use them as arbitrary accents.

**The Surface Ladder Rule.** Base Void sits behind Shell Panel; Shell Panel sits behind Work Surface; Rule Line separates them. Do not add another dark surface unless it has a named role.

## 3. Typography

**Display Font:** system UI sans stack with Apple SD Gothic Neo fallback.
**Body Font:** system UI sans stack with Apple SD Gothic Neo fallback.
**Label/Mono Font:** ui-monospace, SFMono-Regular, SF Mono, Menlo, Consolas, monospace.

**Character:** familiar, compact, and legible. The product should read like an operations tool, not a brand surface; type hierarchy is tight and functional.

### Hierarchy
- **Display** (700, 24px, 1.3): page-level h1 and major shell headings only.
- **Headline** (700, 18px, 1.3): section h2 headings and major card groups.
- **Title** (600, 15px, 1.3): h3 headings, compact module titles, and dense panel labels.
- **Body** (400, 14px, 1.5): default UI text, form copy, and table content.
- **Label** (600, 12px, 0.5px letter spacing): uppercase metadata labels, table headers, legends, and card headings.
- **Mono** (400, 12.5px, 1.55): console logs, code blocks, file paths, package names, IDs, timestamps, and prompt text.

### Named Rules

**The Product Type Rule.** Use one system sans family for UI. Do not introduce display fonts, decorative labels, or fluid heading scales.

**The Mono Boundary Rule.** Monospace is for machine-readable content only: logs, code, IDs, paths, prompts, timestamps, and terminal output.

## 4. Elevation

The system uses a hybrid of tonal layering, borders, and small shadows. Most surfaces are flat at rest with a 1px Rule Line border. Shadows are low and structural: cards use the shared shadow to separate modules from the page, dropdowns and floating controls use stronger shadows because they escape normal document flow.

### Shadow Vocabulary
- **Card Shadow** (`0 2px 8px rgba(0,0,0,0.3)`): default card, auth card, and contained module depth.
- **Brand Icon Shadow** (`0 2px 8px rgba(0,0,0,0.25)`): avatar/logo image lift inside the shell.
- **Dropdown Shadow** (`0 8px 24px rgba(0,0,0,0.45)`): project switcher and menus that overlay the page.
- **Drawer Shadow** (`-12px 0 32px color-mix(in srgb, Base Void 72%, transparent)`): mobile navigation drawer edge separation.
- **Floating Control Shadow** (`0 4px 14px rgba(0,0,0,0.5)`): jump-to-bottom and similar controls that must remain visible over scrolling content.

### Overlay Vocabulary
- **Overlay Scrim** (`color-mix(in srgb, Base Void 78%, transparent)`): mobile drawer backdrop and other shell-level temporary overlays.

### Named Rules

**The Flat Until Escaped Rule.** Normal page modules use tonal layers and borders. Shadows become stronger only for dropdowns, popovers, dialogs, and controls floating above scrollable content.

## 5. Components

### Buttons

- **Shape:** compact rounded rectangle (6px radius), except back buttons use a slightly more forgiving 8px radius and chips are pill-shaped.
- **Primary:** Action Blue background, white text, 10px 16px padding, 600 weight. Use for the main submit or start action in a region.
- **Hover / Focus:** hover shifts to Action Blue Hover; keyboard focus uses a 2px Action Blue outline with 2px offset.
- **Secondary / Ghost / Tertiary:** secondary buttons use Work Surface with Rule Line border and Readable Ink text. Ghost controls are transparent with Quiet Ink text and a Rule Line border.
- **Danger:** transparent Danger Red text and border by default; filled Danger Red with white text on hover.

### Chips

- **Style:** pill shape, 4px 10px padding, 12px text, Base Void background, Rule Line border, Quiet Ink text.
- **State:** hover moves to Work Surface, Readable Ink text, and a stronger border. Destructive chips use Danger Red text and border.
- **Use:** lightweight actions, filters, route links, table actions, and compact toolbars. Do not use chips as page titles or major CTAs.

### Cards / Containers

- **Corner Style:** modest radius (6px), never oversized.
- **Background:** Work Surface on Base Void or Shell Panel.
- **Shadow Strategy:** default Card Shadow plus Rule Line border.
- **Border:** always 1px Rule Line unless the component is a text-only inline group.
- **Internal Padding:** 20px desktop, 16px phone, 12-14px for fieldsets and compact modules.

### Inputs / Fields

- **Style:** Base Void background, Rule Line border, 6px radius, 10px 12px padding, inherited font.
- **Focus:** remove default outline and shift border to Action Blue; full keyboard focus still uses the global focus-visible outline.
- **Error / Disabled:** disabled controls use 50% opacity and not-allowed cursor. Error states pair Danger Red text and border with a low-alpha Danger Red background.
- **Textareas:** monospace by default for prompts, code, logs, and configuration.

### Navigation

- **Sidebar:** 220px desktop shell, 56px collapsed desktop shell, Shell Panel background, Rule Line right border, compact nav rows with 20px icons.
- **Nav Rows:** 8px 12px padding, 6px radius, Quiet Ink default; Work Surface and Readable Ink on hover/active; active rows use 600 weight.
- **Project Tabs:** fullbleed project pages own their own sticky header, compact tab bar, iframe pane, and optional right rail.
- **Project Tabs / Rail Tokens:** project tab layouts use scoped `--pt-*` tokens on `#project-tabs-root` for rail width, rail background, menu/modal surfaces, control fields, hover rows, borders, muted labels, provider selectors, context meter segments, automation buttons, primary/state fills, overlays, and rail shadows. New tab or rail widgets should consume these scoped tokens before adding local color values.
- **Mobile:** at 768px and below, the sidebar becomes a horizontal top header. Navigation wraps or scrolls, content padding reduces, and settings tabs become horizontally scrollable.
- **Back Controls:** use the shared icon back button pattern. Text arrows, random chips, and mixed `target` behavior are not acceptable for new screens.

### Tables

- **Style:** Work Surface background, Rule Line border, 6px radius, 13px content.
- **Headers:** Shell Panel background, Quiet Ink uppercase label, 11px size, 0.5px letter spacing.
- **Cells:** 10px 14px padding, Rule Line row dividers, word wrapping for long paths and values.
- **Mobile:** wrap tables in `.table-wrap` for horizontal scrolling. A table must never force page-level horizontal scroll.

### Console

- **Surface:** deeper log surface (`#0a0d12`) with Rule Line border and 6px radius.
- **Typography:** monospace 12.5px, 1.55 line-height.
- **Messages:** assistant/tool/system states use low-alpha tinted bubbles; the current user prompt is sticky, uses a blue left inset, and clamps to two lines until expanded.
- **Console Tokens:** console surfaces, scrollbar colors, message bubble fills, role label accents, sticky prompt background, floating jump button shadow, and gauge colors live in global `--console-*` tokens. New log roles should extend this vocabulary instead of adding local rgba values.
- **Keyboard Mode:** on short viewports or mobile keyboard states, optional toolbars and quick prompts may hide so the log and prompt remain usable.

### Status Pills

- **Style:** pill shape, 3px 9px padding, 12px text, 1px border, leading dot.
- **States:** responding uses Success Green and pulse; idle/ready uses Quiet Ink; waiting uses Waiting Yellow; stopped uses Halted Violet; error uses Danger Red.
- **Status Tokens:** shared status tint backgrounds and borders should use semantic variables such as `--status-stopped-fill` and `--status-stopped-border`, paired with the core state colors.
- **Reduced Motion:** pulsing indicators must stop under `prefers-reduced-motion: reduce`.

## 6. Do's and Don'ts

### Do:

- **Do** keep every new screen inside one clear shell ownership model: global shell, project fullbleed shell, or embedded content, never a mixture.
- **Do** use the existing `:root` tokens before adding new values: Base Void, Shell Panel, Work Surface, Rule Line, Readable Ink, Quiet Ink, Action Blue, and semantic state colors.
- **Do** keep product type compact: 24px page h1, 18px h2, 15px h3, 14px body, 12px labels, and monospace only for machine-readable content.
- **Do** preserve operational density while keeping scroll ownership explicit: page content scrolls inside `.content`; tables and logs scroll inside their own containers.
- **Do** test mobile at 768px, 480px, browser zoom, and virtual keyboard states before declaring a UI change complete.
- **Do** use `prefers-reduced-motion` for any pulse, transition, or reveal that could affect task flow.

### Don't:

- **Don't** use SaaS landing-page composition.
- **Don't** use excessive card grids.
- **Don't** use decorative glass or gradients.
- **Don't** use large empty mobile-app spacing.
- **Don't** create inconsistent one-off controls.
- **Don't** duplicate app chrome, nested headers, sidebars, tab bars, rails, or back controls.
- **Don't** use modal-first workflows that hide the current task when inline or progressive disclosure would work.
- **Don't** add display fonts, gradient text, decorative motion, or full-saturation inactive states.
- **Don't** rely on page-level horizontal scroll. Long data belongs in a scroll container, wrapped table, clipped label, or monospace block.
- **Don't** use `border-left` or `border-right` greater than 1px as a colored accent stripe. Use state pills, full borders, tonal backgrounds, or icon/dot indicators instead.
