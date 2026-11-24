---
title: Hit time
kind: term
summary: How long a skill's cast takes, from the client's Skillgrp.dat, before the caster's speed.
related: effects/skill-casts, effects/play-a-skill
---
At the hit time the server lands the spell or the blow. A skill whose shot does not fly plays its hit effects on the
target then. A flying shot plays them when it reaches the target, which can be a little before or after. The server makes the cast
shorter for a fast caster: the time is the hit time × 333 / speed. A skill's card in the **Effects** panel says it,
such as "Casts 4.0 s". See [How a skill casts](guide:effects/skill-casts).
