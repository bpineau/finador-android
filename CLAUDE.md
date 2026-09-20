# CLAUDE.md

Read **[AGENTS.md](AGENTS.md)**. It is the onboarding guide for AI coding agents: what the app is
for, the principles that decide every trade-off, the architecture map, where to change things, how
to verify cheaply, the traps, and the definition of done. This file only routes you there.

The three things that decide everything here, in case you read nothing else:

1. **The app must survive Android's yearly churn with almost no maintenance.** Platform and
   first-party AndroidX APIs first; a third-party library needs a written justification in
   `docs/maintainability.md` and leaves as soon as the platform can do its job. No deprecated API,
   and every compiler or lint warning is a bug to fix, never to suppress. Stay current on
   `compileSdk`/`targetSdk`, AGP, Gradle and Kotlin in README §6's order, never out of order. A
   feature that adds yearly upkeep must earn it: when in doubt, do less.
2. **The `.fin` format and every computed number mirror the Go reference in `../finador`.** Change
   the Go side first, mirror it here with the same test numbers.
3. **Two hard gates before claiming done**: `make test` fully green, and `make crossimpl` green too
   for any change under `crypto/` or `format/`. Plus `make lint` at "No issues found". Commit to
   `master` and push; never create a tag by hand (a `v*` tag publishes a release).
