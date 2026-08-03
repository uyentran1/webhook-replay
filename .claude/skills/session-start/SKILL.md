---
name: session-start
description: Start a work session on this project. Re-orients after a week away by reading the state files, checking git and build status, and proposing a plan for the session. Use at the beginning of every session.
---

# Session start

I've been away for at least a few days. Your job is to get me productive in under five
minutes without me having to remember anything.

Do this in order:

1. Read `NEXT.md`, then the current phase in `ROADMAP.md`.
2. `git log --oneline -10` and `git status` — tell me what branch I'm on and whether the
   tree is dirty. If it's dirty, that's a problem: last session should have ended clean.
   Show me the diff and help me decide whether to commit or discard before anything else.
3. Run the build and tests. If anything is red, that's the session's first task —
   say so plainly rather than proceeding to the planned work.
4. Read any code files the next action touches, so you have them in context.

Then give me, in under 200 words:

- **Where we are** — one sentence
- **The next action** — verbatim from `NEXT.md`, plus whether it still makes sense given
  what you see in the repo
- **A proposed plan for this session**, sized to the time I tell you I have, ending at a
  natural commit point. Ask how long I have if I haven't said.
- **Anything that looks off** — failing tests, TODOs left in the code, a half-finished
  refactor, a `NEXT.md` that disagrees with the actual state of the repo

Then stop and wait. Don't start implementing.

Remember which bucket the work falls into (see `CLAUDE.md`). If this session's work is in
the "I drive" bucket — retry scheduling, fairness, ordering, circuit breaking, HMAC —
your role is to help me write a failing test first and then review what I write. Say so
before we start.
