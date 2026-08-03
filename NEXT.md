# Next

> Read this first, every session. Rewrite it at the end of every session.
> The next action must be specific enough to start typing within 60 seconds of reading it.
> Bad: "work on the worker." Good: "add jitter to `RetryScheduler.nextAttemptAt` — the
> test at `RetrySchedulerTest:47` is written and currently failing."

**Last session:** — (none yet)

**Where things stand:** Empty repo. Docs written, no code.

**Next action:**
Fill in `DESIGN.md` §6 (retry policy — the actual schedule and the jitter justification)
and take a first pass at §7a, §7b, §7c in my own words. Write them badly rather than not
at all; they get revised in week 6 once I've seen the failure for real.

**Then:** `git init`, Spring Boot skeleton (web, data-jpa, flyway, actuator), docker-compose
with Postgres 16, `/health` returning 200, first commit.

**Blocked on:** nothing

**Parked / noticed but not doing:**
- (things you notice mid-session and consciously decide not to chase — put them here
  instead of following them, and review the list every few weeks)
