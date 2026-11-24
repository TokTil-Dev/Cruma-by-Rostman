---
title: Client tables
kind: howto
summary: See how Cruma reads the tables of a client, and add a schema for a table that it cannot read.
next: tables/write-a-schema
related: tables/schema-language, tables/schemas-and-keys, start/settings, start/where-cruma-writes
---
A client keeps its tables in the `system` folder, in files such as `npcgrp.dat`: which model an NPC has, which icon a
skill has. Essence keeps them in the folder of its language, such as `system/eu`, and Cruma looks there too when
`system` has no such table. Cruma reads each table with a schema, a small text file that says how the table is laid out. It knows the
schemas of the game's own clients. A client of a private server can change a table, and then no schema of Cruma reads
it. You can add a schema of your own, with no new version of Cruma.

### You need

- The client chosen on the start screen, or a world of it open in the Viewer.
- For a new schema: what the server changed in the table. A server often ships its schemas in `cruma/schemas`.

1. Open **Settings**, then **Client tables** under **THIS CLIENT**. When a table is not read, the **Client** panel of
   the start screen also has a **Client tables** line under **FOLDERS**. A click on it opens the page.
2. Read the line under the title, such as "17 read · 1 not read". Under **TABLES**, each table has a dot and a word:
   read, partly read, not read, not used, or no file. A table that was read names its schema, where it is from and its
   rows. A table that was not read says what you lose, such as "NPCs are drawn without their models." A table that is
   not used is no fault: the client has the file, but Cruma takes it only with another table that the client does not
   have, as `CharCreategrp.dat` goes with `CharHeadExgrp.dat` for the class figures of Essence.
3. Click a table that was not read. **SCHEMAS IN THE ORDER CRUMA TRIED THEM** says how far each schema got: the row,
   the byte and the reason it stopped.
4. Press **Show row**. It shows every field of the row where the closest schema stopped: the field, its type, its
   byte and its value. The field where it stopped is marked in red. **Row** shows another row, and `-1` the fields
   outside the rows, before and after them. A schema that reads every row but leaves bytes at the end shows `-1` at
   once, with the bytes left marked. The line beside **Row** names the schema and where it is from, as a copy of a
   built-in schema in your folder can have the same title.
5. Press **Save built-in schemas** to write Cruma's own schemas into `built-in schemas` beside your schemas folder,
   with **Show folder** under it. Saving again replaces the copies there. The folder button beside it saves them into
   a folder you choose, but not into a folder Cruma reads schemas from, where the copies would hide later fixes of
   the built-in schemas. Copy the one that came closest, or write one that is `like` it and says only what differs.
6. Put your schema into a folder: **Open schemas folder** for this client alone, or **Open my schemas folder** for
   every client. Cruma asks before it makes `cruma/schemas` in the client folder: **Create and open** makes it, and
   **Cancel** writes nothing.
7. Press **Check again**. Cruma reads every table again, with your schema, and the line under the title counts again.

When it worked, the table says read, with your schema's title and "your folder" or "this client's folder". The
Viewer uses it the next time a world opens.

> **Note:** A check reads every table, which takes a second or two on a large client. The page shows what Cruma knows
> at once, and checks a client the first time you open the page for it.

### If it went wrong

- **PROBLEMS** lists each schema file with a mistake and each line of `keys.txt` that cannot be read, with its file and
  line. A schema with a mistake is not tried.
- A table that a schema reads to its end, with values that make no sense, stays not read: the reason says which value.
- A table locked with a key Cruma does not know says "The file cannot be opened with any key Cruma knows." Its tip
  has the full reason. A line in `keys.txt` beside your schemas opens it, see [Keys](guide:tables/schemas-and-keys).
- How to write the schema itself is in [Write a schema for a changed table](guide:tables/write-a-schema), every
  word of it in [The language of schemas](guide:tables/schema-language), and the order Cruma tries schemas in, the
  keys and the command line `Cruma tables` in [Schema folders, keys and the command line](guide:tables/schemas-and-keys).
