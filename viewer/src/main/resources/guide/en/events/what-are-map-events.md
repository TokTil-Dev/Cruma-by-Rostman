---
title: What are map events
kind: concept
summary: Parts of a map that the server turns on and off, and effects that show only at some hours of the game day.
tool: events
next: events/switch-events
related: events/effects-by-time, terms/map-event, terms/zone-state, terms/game-day
---
A map of the [client](guide:terms/client) holds more than the game shows at once. The lights of a festival, the show of a town clock, or the
signs of a castle's side wait hidden in the map. The server says when they show.

![A map event switches a place between two looks: off as the game starts, on when the server sends the event.](diagram:event-switch)

## Events from the server

Some effects of a map carry a [map event](guide:terms/map-event): a number, such as `22226292`. When the game starts,
the client hides them. When the server sends that number, the client shows them, and it hides them again when the
server turns the event off. The client has no names for these numbers. An [L2OFF](guide:terms/l2off) server names many of them in its
`areadata.txt`.

## Castle sides

P152 and later clients have effects for the side of each castle, light or dark. The server chooses the side, and the
town of the castle shows the effects of that side.

## Zone states

A zone of a map can have [states](guide:terms/zone-state). Some places keep two looks in the same spot, one for each
state, and the server chooses the state players see. It sends one state for each map, so each map has its own, and
the **Map events** panel chooses one for each map too.

## Effects by the hour

Other effects follow the clock of the [game day](guide:terms/game-day), not the server. The blood rain of the High Five
towns falls from 02:00 to 02:10 and from 04:00 to 04:10 of each game day.

![An effect of 05:00 to 06:00 and 13:00 to 15:00. By clock shows it in those hours, Always at every hour, Off never.](diagram:event-day)

## What Cruma does with it

The **Map events** panel lists the events of the loaded [tiles](guide:terms/tile), and you turn each one on or off. Dots in the world mark
where their effects stand. Your choice is kept for the client, and **Export location** and Capture follow it too. The
client and its files never change.

[Open the Map events panel](action:events-open-panel)
