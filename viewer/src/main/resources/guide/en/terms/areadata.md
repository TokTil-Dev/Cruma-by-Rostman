---
title: areadata.txt
kind: term
summary: The file of an L2OFF server that lists its areas, such as its towns, water, swamps and teleports.
related: areas/what-is-an-area, terms/script-folder, areas/save-areas
---
`areadata.txt` lies in the server's [script folder](guide:terms/script-folder). Each area is one record from
`area_begin` to `area_end`, with settings such as `name`, `type` and `range`, the corners of its outline. A `//` line
right above a record is the area's note. The **Areas** panel reads the file, shows it and saves it. See
[What is a server area](guide:areas/what-is-an-area).
