---
title: Write a schema for a changed table
kind: howto
summary: Make a table of a private server readable with a short schema that says only what it changed.
next: tables/schema-language
related: tables/client-tables, tables/schema-language, tables/fields-cruma-needs, terms/table-schema
---
A private server often adds a field or two to a table of the game. Then no schema of Cruma reads the table, and the
thing it holds goes missing, such as the models of NPCs. A schema that is `like` the built-in one and says only what
the server changed reads it again. It is a text file of a few lines.

### You need

- A table that [Client tables](guide:tables/client-tables) shows as not read.
- A text editor that saves plain text, such as Notepad.

1. Open **Settings**, then **Client tables**, and click the table. Under **SCHEMAS IN THE ORDER CRUMA TRIED THEM**,
   find the schema that came closest: the one that stopped at the highest row, or at the highest byte. Its title is
   the first word of its line, such as `Interlude and Gracia`.
2. Press **Show row**. Look at the field where it stopped, in red, and at the fields just before it. A value that
   makes no sense, such as a speed of `7.2E-41` or a count of `16777216`, is often the place where the server put its
   own field.
3. Press **Save built-in schemas**, then **Show folder**, and open the file of that title in a text editor. Find the
   names of the fields around that place, such as `speed`.
4. Make a new file, such as `my-server.schema`, that names the table, a title of your own and the schema it is like,
   and then says what differs. For one more number after `speed`:

```
format  1
file    npcgrp.dat
title   My Server
like    Interlude and Gracia

after speed {
    uint vipFlag
}
```

5. Save it as plain text with the extension `.schema` into the folder that **Open schemas folder** opens, for this
   client alone, or that **Open my schemas folder** opens, for every client.
6. Press **Check again**. Cruma reads every table again, and your schema is tried first.
7. If the table is still not read, press **Show row** again. The line beside **Row** names your schema now, and the
   field where it stops has moved on. Change the file and press **Check again**.

When it worked, the table says read, with your title and "this client's folder" or "your folder". The Viewer uses it
the next time a world opens. Your schema changes nothing for other tables or for clients whose table a built-in schema
reads, as Cruma tries a schema only on the file its `file` line names.

> **Note:** `after`, `before`, `replace` and `remove` change one field each. Several of them can stand in one schema.
> They are described in [A schema like another](guide:tables/schema-language).

### If it went wrong

- **PROBLEMS** names your file and its line, and says what to change, such as: unknown type "uin". Did you mean
  "uint"? A schema with a mistake is not tried.
- "No schema of this table has the title": the `like` line names a title as **Client tables** writes it, letter case
  aside. The message lists the titles there are.
- "2 fields have this name": write the way to the field with dots, such as `records.speed`, as the message lists them.
- "4 bytes stand between the last field and the SafePackage mark": the server added bytes after all the rows. Write
  `after records { skip 4 }` with that number.
- "Its field level is a float, and Cruma needs a whole number": a field Cruma uses must give the kind of value it
  reads. See [Fields Cruma needs](guide:tables/fields-cruma-needs).
- The table is read, but NPCs show wrong models or icons: a field stands in the wrong place, and the values went to
  the wrong names. **Show row** shows which value each name got.
