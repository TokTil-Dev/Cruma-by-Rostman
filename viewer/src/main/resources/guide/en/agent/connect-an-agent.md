---
title: Connect an AI agent
kind: howto
summary: Add Cruma to your agent's program once, and let the agent work in your window.
tool: agent
next: agent/access-and-stop
related: agent/what-agents-do, agent/access-and-stop, terms/mcp
---
Give an AI agent, such as Claude Code, the tools of Cruma. You do this once, and the agent's program then starts Cruma
by itself whenever it needs it.

### You need

- An agent's program that speaks [MCP](guide:terms/mcp), such as Claude Code.

## Steps

1. Open **AI agent** on the tool rail. Or open [Settings](guide:start/settings), with the gear of the start screen or
   **Settings…** in the Viewer menu, and its page **AI agent**.
2. Under **CONNECT**, find the field of your program: **Claude Code** for Claude Code, **Other programs** for the rest.
3. Press the copy button at the right end of that field. It holds the command or the settings for this installation.
4. For Claude Code, paste the command into a terminal and run it once. For another program, paste the settings into
   its MCP settings.
5. Turn on **Let an AI agent use this window**, so the agent can work in the window you look at.
6. Choose how far it may go under **ACCESS**. **Edit** is the default.
7. Ask your agent for something, such as "What is the object I selected?".

When it worked, the line under the switch says `1 agent connected` with a green dot, and **CALLS** lists each call
with what it did or why it failed.

[Open the AI agent panel](action:agent-open-panel)

## Without the switch

An agent does not need your window. With the switch off it opens a viewer of its own, with a window or hidden, and
works there. A hidden viewer closes when the agent's program ends. Use your window for "what is this?" and for working
together, and let the agent use its own for long surveys.

### If it went wrong

- The line says `Agents are kept out`: the switch is off. Turn it on.
- The switch is grey and its tip says this run keeps no settings: such a run of Cruma keeps agents out of its window.
- A red note says `Agents cannot reach this window`: the rest of the note says why.
- The agent's program does not list Cruma: run the command of step 4 again, and start the program again.
- The agent says that a viewer runs another version of Cruma: Cruma was updated while the agent's program was open,
  and the program still holds the old list of tools. Start the program again.
