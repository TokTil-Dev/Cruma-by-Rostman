---
title: Access and Stop
kind: reference
summary: What See, Edit and Write let an agent do, the limits on its exports, and how Stop holds an agent at once.
tool: agent
related: agent/what-agents-do, agent/connect-an-agent, terms/access-level, start/where-cruma-writes
---
You decide how far an AI agent may go. Cruma checks the [access level](guide:terms/access-level) on each call, whatever
the agent tries, and **Stop** holds it at once.

![See, Edit and Write: each level holds the one before it and adds one step.](diagram:agent-access)

## The three levels

**ACCESS** in the **AI agent** panel, and the same row on the page **AI agent** of Settings, sets the level. A change
counts from the agent's next call, also in another window.

| Access | The agent may | The agent may not |
|---|---|---|
| **See** | Move the camera, take pictures, read all data, export into the export folder, and save the scripts of effects and skills into the **Effect scripts folder** of Settings | Change anything |
| **Edit** | All of See. Change the world, geodata and spawns in memory, each change one Undo step. Write scene files of the video director, with a dated `.bak` beside a scene it changes | Write to the client or the server folders |
| **Write** | All of Edit. Save into the client and the server folders, and write to other folders | Delete files. No tool does that |

**Edit** is the default. Your agent's program can also ask you before each call.

> **Warning:** **Write** reaches the files of your client and your server, often your only copy of them. Give it only
> for a task that needs a save, and keep a copy of the client.

## Limits on exports

| Limit | Value |
|---|---|
| Free room an export leaves on the disk | At least 5 GB, or the export is refused |
| Exports in one run of the agent's program, below Write | At most 20 GB |
| Pictures of the agent that are kept | The last 200 |

The limits count the exports of locations, NPCs, scenes and edited effects. The scripts of effects and skills need the
free room too, but they replace the same files each time they are saved again, so they do not count toward the 20 GB.

Nothing old is deleted to make room. The agent tells you, and you decide what goes.

## Stop and Allow

**Stop**, at the right of the line under the switch, refuses all further calls in this window. A call at work ends
first. Tools that change or write something then stop in every viewer, hidden ones too, and so do exports, renders,
builds and opening a world. Reading goes on elsewhere. The line then says `Stopped · calls are refused`.

**Allow**, in the same place, lets the agent call again. Stop lasts until you press Allow or close the window, also when
you turn the switch off.

Turning **Let an AI agent use this window** off closes the connection to your window at once. A window that an agent
opened itself stays connected, so **Stop** is the way to hold that agent. Stop is grey while the switch is off: turn
the switch on, then press Stop.

## Taking a change back

A change of an agent stands in the Undo list like yours. The agent can take back only its own steps: it stops at a step
of yours. When you drag something, the agent's edit waits for the end of your drag.
