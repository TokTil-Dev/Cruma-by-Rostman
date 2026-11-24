---
title: Schema folders, keys and the command line
kind: reference
summary: Where schemas and keys.txt go, the order Cruma tries schemas in, and Cruma tables on the command line.
related: tables/client-tables, tables/write-a-schema, tables/schema-language, start/where-cruma-writes
---
Cruma has a built-in schema for each table of the clients it knows. Schemas of your own come first, so a schema for
a changed table wins over the built-in one. There is nothing to choose: Cruma tries the schemas of a table in turn.

## Where schemas are

| Place | For |
|---|---|
| `cruma/schemas` in the client folder, beside `system` | This client only. A server can ship this folder with its client |
| `schemas` in Cruma's settings folder, the folder of `cruma.conf` | Every client. A run with `--config` uses the `schemas` of that folder |
| Inside Cruma | The clients Cruma knows |

**Open schemas folder** and **Open my schemas folder** of [Client tables](guide:tables/client-tables) open the first
two. Cruma looks into sub-folders too, at most 8 deep, and does not follow links.

- A schema file has the extension `.schema`. Its name is free: the `file` line inside says which table it is for.
- A folder gives at most 256 schema files with at most 200 000 fields in all. Cruma looks at no more than 10 000 files
  and folders of a folder.
- A schema file is UTF-8. A file that starts with the mark of UTF-8, UTF-16LE or UTF-16BE, as Notepad can write it,
  is read in that form. A file with no mark that is not UTF-8 is read as Windows-1251, so Russian comments work.
- Cruma reads the files of a folder again when one of them changes. **Check again** then reads the tables with them.
- **Save built-in schemas** writes Cruma's own schemas into `built-in schemas` beside `schemas`, which Cruma does not
  read schemas from. A copy in a folder Cruma reads would hide later fixes of the built-in schemas.

## How Cruma chooses

For each table Cruma takes the schemas whose `file` line names the table: those of the client's folder, then those of
the settings folder, then its own, each folder in the order of its file names, and the built-in ones in their own
order. It passes over a schema:

- with a mistake, which **PROBLEMS** shows with its file and line,
- that lacks a field Cruma needs, or gives it of another kind, see [Fields Cruma needs](guide:tables/fields-cruma-needs),
- with `name` fields, for a client that has no `L2GameDataName.dat`.

It reads the table with each of the others in turn and takes the first that reads it to its end: every row, and after
the last field nothing or the `SafePackage` mark. For `npcgrp.dat`, `Weapongrp.dat` and `Armorgrp.dat` the meshes of
the first 50 rows must also look like `Package.Name`. When no schema reads the whole table, Cruma takes the one that
read the most rows of a damaged end, as some patched clients have, when it read at least 100 rows and 9 of 10 of all.
**Client tables** then says partly read.

## Keys

A `.dat` file is locked with a key. Cruma knows the keys of the game's own clients. For a client locked with a key
of its own, write the key into `keys.txt` beside your schemas: in `cruma/schemas` of the client folder, or in
`schemas` of the settings folder. One line holds one key: its exponent, then its modulus, both in hexadecimal. `#`
starts a comment.

```
# My Server
1d 75b4d6de5c016544068a1acf125869f43d2e09fc55b8b1e2...
```

The line above is shortened. A real modulus has 256 hexadecimal digits.

| Rule | Value |
|---|---|
| Order | Cruma's own keys first, then those of the client's folder, then those of the settings folder |
| Exponent | Odd, 3 or more, at most 32 bits |
| Modulus | 1017 to 1024 bits, so it fills a block of 128 bytes |
| Size of `keys.txt` | At most 64 KB, a bigger file is not read |
| A table opened by any key | At most 512 MB, and no more than its packed data can give |

A key helps only a file that keeps the game's own form, with the head `Lineage2Ver411`, `Lineage2Ver412` or
`Lineage2Ver413`. A client with another protection stays closed. A line that cannot be read is shown under
**PROBLEMS** with its file and line.

## On the command line

`Cruma tables` says the same as the page, with no window and no graphics card, so it also runs on a server. It only
reads: it makes no settings folder and writes no file, but the copies of `--save-built-in`.

```
Cruma tables --client <folder>
Cruma tables --client <folder> --variant classic
Cruma tables --client <folder> --table npcgrp.dat
Cruma tables --client <folder> --table npcgrp.dat --row 0
Cruma tables --save-built-in <folder>
```

| Option | Meaning |
|---|---|
| `--config <folder>` | The settings folder, for its `schemas` and its client. The usual one when left out |
| `--client <folder>` | The client folder, which holds `system`. The client of the settings when left out |
| `--variant main`, `classic` or `classic_aden` | The world: `classic_aden` is Essence. The client's world in the settings when left out |
| `--table <file>` | One table, such as `npcgrp.dat` or `ItemName-e.dat`, with every schema it tried |
| `--row <n>` | With `--table`: every field of the row, from 0, as the schema reads it, and where it stopped. `-1` shows the fields outside the rows, and the bytes left after the last field |
| `--schema <title>` | With `--row`: the schema to read with, by its title, the first of that title in the order Cruma tries them. The one that read the table, or came closest, when left out |
| `--save-built-in <folder>` | Writes the built-in schemas into the folder, in place of the copies there. A folder Cruma reads schemas from is refused |

A title with spaces stands in quotes, as in this option:

```
--schema "Interlude and Gracia"
```

It prints one line for each table, then the schemas each table that was not read tried, and the mistakes of schema
files and of `keys.txt`.

| Exit code | When |
|---|---|
| 0 | Every table the client has was read, also one with a damaged end, or is not used, as the client does not need it |
| 1 | A table was not read |
| 2 | A wrong argument, settings that cannot be read, a folder that is not a client, a table the client does not have, or a failure of the command |

## For an AI agent

The tool `assets_find` of [MCP](guide:terms/mcp) reads the same. With kind `table` it lists the tables and how each was
read, with `row` it reads a row field by field, and with kind `schema` it gives every schema of a table with its text.
`variant` chooses the world. No tool writes a schema: the agent's own program writes the file into a folder that the
answer names, and Cruma reads it at the next call.

## Packages

Schemas describe tables only. Maps, meshes and textures, the files `.unr`, `.usx`, `.utx` and the others, are read
by Cruma's code. A client of a private server that changed how those files are made needs a new version of Cruma.
