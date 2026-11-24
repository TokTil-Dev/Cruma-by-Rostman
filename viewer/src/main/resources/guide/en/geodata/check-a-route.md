---
title: Check a route
kind: howto
summary: See whether a character can get from A to B by the server's own rules, and where it stops when it cannot.
tool: geodata
tab: route
next: geodata/edit-cells
related: geodata/find-problems, geodata/cells-layers-nswe
---
Find out whether a character or a monster can get from one place to another, and see the way it takes.

### You need

- A geodata folder open in the **Geodata** panel.
- For **Path**, the files `pathnode.idx` and `pathnode.bin` in the same folder. The other modes need only the [cells](guide:terms/cell).

## Steps

1. Choose **Route**, or press <kbd>R</kbd>.
2. Choose a mode: **Path**, **Straight**, **Sight** or **Reach**.
3. Click the ground where the route starts. This is A.
4. Click where it ends. This is B, and the route runs at once.
5. Press **Add stop C** to go on to more places, up to 8 points from A to H.
6. Choose a size under **Body** to check that a big creature fits through, not only a point.
7. Press **Walk** to see a marker follow the route. <kbd>Space</kbd> pauses it, <kbd>Esc</kbd> stops it.

When the route reaches B, the panel says so in green, such as "Reaches B · 4 waypoints" or "Walks straight to B". It
also shows the length of the route, and a profile of its heights.

| Mode | What it checks |
|---|---|
| **Path** | The way the server finds for a monster, on its pathnode graph |
| **Straight** | A walk in a straight line, and where it stops |
| **Sight** | Whether A can see B, as for spells and arrows |
| **Reach** | Every cell a character can walk to from A, within a radius |

### If it went wrong

- The panel says "Stops before B" or "Stops": the route meets a closed side or a drop. Click the cell where it stops
  and read it in Inspect.
- The panel says "Search budget reached before B": the server gives up on long paths. Under **Options**, choose
  **Custom** to search further. A route found that way can still fail on a real server.
- **Path** needs the pathnode files of the folder. Without them, use **Straight** or **Reach**.
- **Sight** says A sees B through a door: doors and moving objects are not part of geodata.
