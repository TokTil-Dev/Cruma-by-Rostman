---
title: Read the Performance panel
kind: reference
summary: What the numbers of the Performance panel mean, and what to change when the view is slow.
tool: performance
related: terms/frame-time, terms/draw-call, view/scene-settings, start/clients-and-tiles
---
The Performance panel says whether the frames come in time, and which part of the computer is slow. Press
<kbd>F4</kbd>, or the third button of the tool rail.

[Open the Performance panel](action:view-open-performance)

## Frame health

The word at the top right of the panel is its diagnosis. The budget of a frame is 16.7 ms, the time of one frame at
60 frames a second.

| Diagnosis | What it means |
|---|---|
| **WITHIN BUDGET** | The frames come in time. |
| **OVER BUDGET** | A frame takes more than 1.1 times the budget. |
| **FRAME PRESSURE** | A frame takes more than twice the budget, or fewer than 30 frames come a second. |
| **GPU BOUND** | The graphics card is the slow part. It shows only when the time of the card is measured. |
| **DRAW-CALL PRESSURE** | More than 6000 draw calls go to the card in a frame. |
| **CPU SUBMIT** | Preparing the scene alone takes more than the budget. |
| **STREAMING WORLD** | Tiles are still loading. |
| **WARMING UP** | No frame has been measured yet. |

## The numbers

| Number | What it is |
|---|---|
| **FPS** | Frames a second. Green from 55, amber from 30 to 54, red under 30. |
| **FRAME** | The [frame time](guide:terms/frame-time): how long a frame takes now, in ms. |
| **P95** | 95 of 100 of the last 120 frames were this fast or faster, in ms. |
| **CPU**, **SUBMIT**, **GPU** | Where the time of a frame goes: the processor, preparing the scene, and the graphics card. |
| **SUBMITS** | [Draw calls](guide:terms/draw-call) in a frame. |
| **VISIBLE** | Parts of the world the camera sees, after the hidden and the far ones are left out. It says sampling while it waits. |
| **FX** | Batches of [particles](guide:terms/particle) of the effects. |

The line under the numbers draws the last 120 frames: green under the budget, amber over it, red for a spike.
**DETAILS** opens four tabs, **FRAME**, **VISIBILITY**, **RENDER** and **PASSES**, with the numbers of each step.

## The frame limit

The FPS in the top bar, such as `73 FPS`, is a button. Press it to open **Frame limit**: the most frames the Viewer
draws in a second. A lower limit gives the graphics card less work, so it stays cooler and quieter, also when the view
is fast.

| Control | What it does |
|---|---|
| **30**, **60**, **120**, **144**, **240** | Sets this limit. The limit in use is marked |
| **Custom** | Type a whole number from 1 to 1000 and press <kbd>Enter</kbd>, or click elsewhere. Another number is refused |

The new limit counts from the next frame. The Viewer keeps it for the next start: it is **Frame limit (FPS)** in
Settings, on the page **Graphics**, where you can also set it before a world opens. The first start uses 144. When vertical
sync is on, the display can hold the rate lower than the limit, and the popup says so. <kbd>Esc</kbd> or a click
outside closes the popup.

## When the view is slow

- If the graphics card works hard, press the FPS in the top bar and choose a lower **Frame limit**, such as **60**.
- Load fewer tiles. Each one adds objects to draw.
- Lower **View Distance**, **Grass distance** and **Impostor distance** in **Scene settings**.
- Turn **Custom Lighting** off.
- Look again after a few seconds: while **STREAMING WORLD** shows, the numbers are not yet true.
