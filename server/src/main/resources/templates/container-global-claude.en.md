# vibe-coder-server Container Global Rules

This file is mounted as `/home/vibe/.claude/CLAUDE.md` inside the container and applies globally
to Claude Code, Codex, and other AI coding sessions that run there. Codex reads the same content
through the `/home/vibe/.codex/AGENTS.md` symlink.

> This is the default template seeded on first server start. Once it exists, the server does not
> overwrite it. Platform-specific build, UI, release, and tooling rules are injected into the
> project-local `CLAUDE.md` by the selected platform engine during project creation.

## 1. Response Language

- Always respond to the user in English.
- Keep code, commands, identifiers, and proper nouns verbatim.

## 2. Work Style

- After code changes, run compile, build, and relevant tests when feasible.
- If verification cannot run, state why.
- Summarize changed files, key decisions, and remaining risk briefly.
- Avoid commands that wait for interactive input, run indefinitely, or open watch/REPL/TUI flows.
- If confirmation is needed, put choices and a recommended default at the end. Do not wait on stdin.

## 3. Global vs Project Instructions

- Global instructions contain only operational, security, documentation, and verification rules common
  to mobile projects.
- Platform-specific build commands, UI framework rules, package structure, signing, store upload, and
  runtime device rules belong only in the project-local `CLAUDE.md`.
- Do not guess the project type. Inspect the project-local `CLAUDE.md`, project files, and server
  metadata first.
- Do not automatically call tools or prompts meant for a different platform.

## 4. Security

- Never put secrets, tokens, signing assets, API keys, passwords, or private keys in logs, docs,
  test fixtures, or commits.
- Do not read or write outside the workspace boundary.
- Do not build raw shell strings directly from user input.
- MCP, release, publish, and deploy actions with external write permissions require an explicit user request.

## 5. Tools and MCP

- For current library, SDK, CLI, or cloud/API behavior, check official docs or a connected docs MCP first.
- Global default MCPs must stay platform-neutral.
- Platform-specific MCP, skill, and agent recommendations come from the project's platform engine/tooling profile.
- Conditional tools require the matching preflight, registered secret, or explicit user request.

## 6. Mobile Common Quality

- Check accessibility labels, touch targets, small screens, long text, loading, empty, error, and permission-denied
  states as relevant to the task.
- Avoid unnecessary dependencies, excessive abstractions, legacy code, dead code, and duplicate tool installs.
- Inspect the existing project structure and design language before changing it.

## 7. Git and Docs

- When a change affects user-visible behavior, operating policy, API, wire shape, deployment, or project creation,
  update the relevant documentation with it.
- Remove legacy descriptions and stale examples when found.
- Commit and push only when the user explicitly asks.
