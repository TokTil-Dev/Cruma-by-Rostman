---
title: Effects by time of day
kind: howto
summary: Show the effects and play the sounds that follow the game's clock in their own hours, at every hour, or never.
tool: events
related: events/what-are-map-events, terms/game-day
---
Some effects show, and some sounds play, only in some hours of the [game day](guide:terms/game-day). Choose whether
Cruma follows that clock.

### You need

- A world with such effects or sounds. Rain over towns with its sound, mist and smoke in some maps follow the clock.

## Steps

1. Open **Map events** and open **BY TIME OF DAY**. Each row is one set of hours, such as `02:00-02:10, 04:00-04:10`,
   with how many effects and sounds it has and their names, such as
   "14 effects · 47 sounds · BloodRain_eff03 · 22_22".
2. Read **Showing** or **Hidden** in a row: what it does at the **Time of day** of Scene settings. A row is
   **Showing** while some of its effects show or some of its sounds play.
3. Choose **By clock**, **Always** or **Off** over the rows, for every set of hours at once.
4. Click a row to select it and frame its effects, or its sounds when it has no effects. Its card has the same three
   choices for those hours alone.
5. Press the button of the card that names an hour, such as **Set time to 02:05**. It moves **Time of day** to the
   middle of the next window of those hours.

| Choice | The effects show and the sounds play |
|---|---|
| **By clock** | In their own hours, as in the game |
| **Always** | At every hour |
| **Off** | Never |

When it worked, the effects show or hide in the view, their sounds start or stop, and a row with **Always** or
**Off** says so in place of **Showing** or **Hidden**.

The same choice stands under **MAP EVENTS** on the page of the client in [Settings](guide:start/settings) as
**Effects by time of day**, also when no loaded tile has such effects. The sounds of those hours follow it there too.
**Export location** and Capture follow it too.

### If it went wrong

- There is no **BY TIME OF DAY**: no loaded tile has such effects or sounds.
- The button that sets the time is off: it works only with **By clock**.
- A window of 10 minutes seems to pass fast: a game day lasts 4 real hours, so it passes in 1 minute and 40 seconds.
