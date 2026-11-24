---
title: NSWE
kind: term
summary: The four sides of a layer, north, south, west and east, each open or closed.
related: geodata/cells-layers-nswe
---
NSWE is how geodata keeps walls: a closed side stops a character that walks across it. In a file each side is one bit,
east `1`, west `2`, south `4` and north `8`. The compass in Inspect shows the sides in green for open and red for
closed. See [Cells, layers and NSWE](guide:geodata/cells-layers-nswe).
