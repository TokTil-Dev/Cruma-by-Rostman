---
title: What an AI agent can do
kind: concept
summary: An AI agent, such as Claude, uses the tools of Cruma for you: it looks, reads, edits, films and exports.
tool: agent
next: agent/connect-an-agent
related: agent/connect-an-agent, agent/access-and-stop, terms/mcp, terms/ai-agent
---
You can ask for work in words, such as "find objects that hang in the air near Dion" or "make a flight over Oren at
sunset". An [AI agent](guide:terms/ai-agent) then does it with the same tools you use, and shows you what it found.

![The agent's program calls Cruma mcp, which runs the tools in a viewer and writes only what the access allows.](diagram:agent-connection)

## How it sees and acts

The agent's program talks to Cruma through [MCP](guide:terms/mcp), a common way for AI programs to use tools. It
starts `Cruma mcp` by itself, a part of Cruma with no window. Through it the agent can:

- move the camera and take pictures, so it sees the world as you do,
- read actors, materials, geodata, spawns, NPCs and scenes,
- change the world, geodata and spawns, each change one Undo step named `AI: ...`,
- film scenes with the video director, and export videos, locations and NPCs,
- export the effects you edited, and save the scripts of effects and skills as files, one at a time or whole packages,
  as **Script** in Effects does: see [View the script of an effect](guide:effects/view-a-script),
- read this Guide, to use the same words as the screen.

## Your window or its own

The agent works in a viewer. With **Let an AI agent use this window** on, in the **AI agent** panel, it works in your
window: it sees your selection and your camera, and you see what it does. Or it opens a viewer of its own, often a
hidden one, so your camera does not move and your work is not touched.

## What you keep in hand

Each call passes the [access level](guide:terms/access-level) you set: **See**, **Edit** or **Write**. Below Write,
nothing is saved into the client or the server folders. The **AI agent** panel lists each call with what it did, and
**Stop** refuses all further calls. See [Access and Stop](guide:agent/access-and-stop).

A change of an agent stands in the Undo list like your own, so <kbd>Cmd+Z</kbd>, or <kbd>Ctrl+Z</kbd> outside macOS,
takes it back.

## Findings

An agent can leave lists for you, such as places where NPCs stand in walls. They show under **FINDINGS** in the
**AI agent** panel, and a click on a line flies the camera there.

[Open the AI agent panel](action:agent-open-panel)
