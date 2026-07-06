# Product

## Register

product

## Users

Solo Android developers operating a self-hosted development server from desktop and mobile browsers. The main user is often moving between project dashboards, AI consoles, build logs, settings, devices, and downloadable artifacts while a long-running AI or Gradle process continues in the background.

## Product Purpose

Vibe Coder Server lets a single user create Android projects, send AI coding prompts, inspect live console output, build apps, manage development tooling, and download APKs entirely from the browser. The web UI is the primary product surface; companion clients are optional.

## Brand Personality

Practical, quiet, dense. The interface should feel like a trustworthy operations tool for repeated daily use, not a marketing site or decorative demo.

## Anti-references

Avoid SaaS landing-page composition, excessive card grids, decorative glass or gradients, large empty mobile-app spacing, and inconsistent one-off controls. Avoid duplicated app chrome, nested headers, and modal-first workflows that hide the current task.

## Design Principles

- Browser-first completeness: every core workflow must be efficient and understandable in the web UI without relying on another client.
- Task density with structure: show many controls and statuses when useful, but keep hierarchy, spacing, and grouping predictable.
- One shell, one navigation model: headers, sidebars, tabs, and back controls must have a single consistent ownership model across embedded and standalone screens.
- Mobile is operational, not secondary: narrow screens, zoomed text, virtual keyboards, and touch targets must preserve the primary console/build/settings workflows.
- Recovery beats decoration: status, errors, disabled states, loading states, and escape paths matter more than visual novelty.

## Accessibility & Inclusion

Target WCAG AA for contrast, keyboard focus, readable text sizing, and touch targets. Support reduced motion and avoid layouts that break under mobile browser zoom, dynamic viewport changes, or virtual keyboard visibility.
