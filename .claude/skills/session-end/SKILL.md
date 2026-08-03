---
name: session-end
description: Wrap up a work session on this project. Runs the full check, commits, and rewrites NEXT.md, BROKE.md, DECISIONS.md, and ROADMAP.md so the next session can start cold. Use in the last 20 minutes of every session.
---

# Session end

The single most valuable 15 minutes of the session. I work on this once or twice a week,
so everything not written down now is genuinely lost.

Do this in order:

1. **Get to green.** Run `./mvnw verify` (and frontend tests if touched). If it's red and
   can't be fixed in the time left, say so and help me revert to the last green commit
   rather than leaving a broken tree. Never end a session red.

2. **Review the diff.** Run `/code-review` on it. Flag anything that looks like it was
   written to make a test pass rather than because it's correct.

3. **Commit** in small logical commits with conventional-style messages. Don't squash
   unrelated changes together.

4. **Rewrite `NEXT.md` completely.** Not append — rewrite. It must contain:
   - what I did this session, one line
   - where things stand
   - the next action, specific enough that I can start typing within 60 seconds of
     reading it (name the file, the function, the failing test)
   - what's blocked, and anything I noticed and deliberately parked

5. **Ask me these three questions, one at a time:**
   - "Did anything break or surprise you today?" → if yes, write a `BROKE.md` entry with
     me, and push for actual numbers rather than adjectives
   - "Did you make a call today that you'd have to justify in an interview?" → if yes,
     add a `DECISIONS.md` entry including what was rejected
   - "Which roadmap checkboxes can we tick?" → update `ROADMAP.md`

   Don't skip these because the session ran long. If we're out of time, these matter more
   than the last 20 lines of code.

6. Commit the doc updates, and tell me what's next session's first move in one sentence.
