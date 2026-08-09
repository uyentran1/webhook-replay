---
name: session-end
description: Wrap up a work session on this project. Runs the full check, commits, and rewrites NEXT.md, BROKE.md, DECISIONS.md, LESSONS.md, and ROADMAP.md so the next session can start cold. Use in the last 20 minutes of every session.
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

6. **Propose `LESSONS.md` entries — don't ask me for them.** I cannot reliably report my
   own misconceptions; if I'd known something was wrong I wouldn't have believed it. You
   observed them, so you write the candidate entries and I confirm or correct.

   Go back over the session and collect:
   - anything I stated that you corrected — a wrong number, an inverted concept, a
     confused term (these are the valuable ones)
   - anything I asked "what is X?" or "why do we do X?" about
   - anything I guessed right but for shaky reasons

   Format per entry: **Corrected** (I believed X, actually Y) or **Learned** (didn't know
   X), dated, filed under the matching theme heading, with *why it matters* — the
   consequence, not just the fact. Include the sanity check or rule of thumb that would
   have caught it. Keep each to a few lines.

   Be accurate rather than kind. A softened entry is a useless one. Equally, don't invent
   misconceptions to fill the file — some sessions won't produce any, and that's fine.

7. Commit the doc updates, and tell me what's next session's first move in one sentence.

8. **Shut down local infrastructure.** From the project root:

   ```bash
   docker compose down
   ```

   Never `down -v` unless I explicitly ask — that deletes the `pgdata` volume and the
   database with it. Plain `down` removes the containers and keeps the data.

   The point isn't tidiness. It's that every session then *starts* by running
   `docker compose up -d`, which is the quickstart path in `README.md`. If that command
   ever breaks, I find out in the first two minutes of a session rather than in week 14
   while writing setup instructions for other people.

   Note tests don't need it — Testcontainers starts its own Postgres, so `./mvnw verify`
   passes with everything shut down. Only `./mvnw spring-boot:run` needs compose.
