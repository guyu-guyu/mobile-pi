# TerminalCore upstream

`runtime/terminal-core` is currently pinned to the verified upstream baseline:

- repository: `https://github.com/AAswordman/OperitTerminalCore.git`
- commit: `f85be57944b806de4d863dee8b10d80d04daa236`
- license: LGPL-3.0

The maintained fork is published on the `terminal-core` branch of the Mobile Pi
repository. The submodule retains the upstream repository as its local `origin`;
compatibility tests must pass before changing the pinned baseline.

## Mobile Pi modifications

- Adds a host-configurable local terminal runtime contract for extra PRoot bind
  mounts, environment variables, and the initial Linux working directory.
- Mobile Pi uses that contract to share `/workspace` and
  `/mobile-pi/pi/config` between the debug PTY terminal and the non-PTY Pi RPC
  process.
