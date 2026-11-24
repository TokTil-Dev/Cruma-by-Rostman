---
title: Table schema
kind: term
summary: A small text file that says how a table of the client's system folder is laid out, field by field.
related: tables/schema-language, tables/client-tables
---
A client keeps its tables in `system/*.dat` files, such as [npcgrp.dat](guide:terms/npcgrp). The order of their
fields changes between versions of the game, and a private server can add fields of its own. Cruma reads each table
with a schema, a `.schema` file. It has schemas for the clients it knows, and you can add your own for a changed
table. See [Write a schema for a changed table](guide:tables/write-a-schema).
