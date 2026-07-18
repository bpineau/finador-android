# CLAUDE.md

Read **[AGENTS.md](AGENTS.md)** - it is the onboarding guide for AI coding agents (purpose,
invariants you must not break, architecture map, where to change things, how to build/test/verify
cheaply, gotchas, and deferred work). This file exists only to route you there.

Two hard gates before claiming any change done (details in AGENTS.md):

1. `make test` fully green (wraps `testDebugUnitTest` and exports `JAVA_HOME`/`ANDROID_HOME` itself).
2. For any change under `crypto/` or `format/`: `scripts/crossimpl.sh` green too (byte
   compatibility with the Go reference implementation in `../finador`).
