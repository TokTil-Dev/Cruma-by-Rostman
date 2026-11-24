---
title: The language of schemas
kind: reference
summary: Every line, type, list, structure, condition and change a schema file can hold, with examples.
next: tables/fields-cruma-needs
related: tables/write-a-schema, tables/fields-cruma-needs, tables/schemas-and-keys, terms/table-schema
---
A [schema](guide:terms/table-schema) is a text file with the extension `.schema`. It says how one table of a client's
`system` folder is laid out, field by field, in the order of the file. This is the built-in schema of `npcgrp.dat`
of Interlude and Gracia Final, without its comments:

```
format  1
file    npcgrp.dat
title   Interlude and Gracia

list uint records {
    uint    id
    string  class
    string  mesh
    list uint string textures
    list uint string secondTextures
    list compact uint properties
    float   speed
    list uint string unknownNames
    list uint string attackSounds
    list uint string defenseSounds
    list uint string damageSounds
    list uint decorations {
        string decoration
        float  scale
    }
    list compact uint quests
    string  attackEffect
    uint    unknown
    float   soundVolume
    float   soundRadius
    float   soundRandom
    uint    quest
    uint    classLimit
}
```

Each line is one field: its type, then its name. A field with no name is read and thrown away. `#` starts a comment,
to the end of the line. Spaces between words are free. A `{` stands at the end of the line that opens a block, and a
`{` or a `}` also ends a line, so a short block fits on one line, as in `after speed { uint vipFlag }`.

## The head

| Line | Meaning |
|---|---|
| `format 1` | The version of the language. It is the first line. |
| `file npcgrp.dat` | The table. Letter case does not matter. `*` stands for any text, as in `ItemName-*.dat`. A schema of `npcgrp.dat` also reads the table of a world, such as `Npcgrp_Classic.dat` or `Npcgrp_ClassicAden.dat`. |
| `title Interlude and Gracia` | The name **Client tables** shows, and the name a `like` line uses. The rest of the line is the title. |
| `like Interlude and Gracia` | Start from the schema of the same table with this title, see below. |
| `set bodyParts 1` | A fixed value that Cruma reads with the table. Only `Armorgrp.dat` uses one. |

Every schema has a `file` line and a `title` line.

## Types

| Type | In the file |
|---|---|
| `byte` | 1 byte, 0 to 255 |
| `short`, `ushort` | 2 bytes, with and without a sign |
| `int`, `uint` | 4 bytes. A `uint` that counts a list is at most 2 147 483 647 |
| `long` | 8 bytes |
| `float`, `double` | 4 and 8 bytes |
| `compact` | The engine's packed number, 1 to 5 bytes |
| `string` | 4 bytes of length in bytes, then UTF-16 text |
| `fstring` | The engine's text: a `compact` length, then one byte a letter, or two when the length is below 0 |
| `ansi` | 4 bytes of length, then one byte a letter |
| `name` | 4 bytes: the number of a text in `L2GameDataName.dat`. Cruma gives the text. -1 is an empty text |
| `skip 24` | 24 bytes that Cruma does not look at |

All numbers are little-endian. The words of the tool L2ClientDat mean the same and work too:

| L2ClientDat | Type |
|---|---|
| `UCHAR`, `UBYTE` | `byte` |
| `SHORT`, `USHORT`, `INT`, `UINT`, `LONG`, `FLOAT`, `DOUBLE` | the same word in small letters |
| `CNTR` | `compact` |
| `UNICODE` | `string` |
| `ASCF` | `fstring` |
| `MAP_INT` | `name` |

A name of a field is made of letters, digits and `_`, at most 64 of them. It may be the same word as a type, as in
`name name`. Two fields of one group have different names. Only the two sides of an `if` may each have a field of the
same name, as a row reads only one side.

## Lists

`list`, then the count, then what repeats, then the name:

```
list uint string textures    # 4 bytes of count, then texts
list compact uint quests     # a packed count, then numbers
list 3 float color           # always 3
list meshCount byte flags    # as many as meshCount says
list uint decorations {      # a group of fields repeats
    string name
    float  scale
}
```

The count is a type, so Cruma reads it from the file, or a number, or the name of a field read before. A type word is
always a type here, even when a field has that name. A list of single values has a name in the plural, such as
`meshes`. A list of groups with a name gives its groups, each with its own fields. A list of groups with no name gives
nothing: its names serve only `like` and **Show row**.

## Structures

A group of fields that comes several times is written once and then used as a type. Its name starts with a capital
letter. It stands at the top of the schema, before or after its uses, and its counts and conditions may use the
fields of the group where it is used.

```
struct Look {
    list uint string meshes
    list uint string textures
}

list uint records {
    uint id
    Look mfighter
    Look ffighter
}
```

## Conditions

```
if handness == 5 {
    uint arrowBone
} else {
    skip 4
}
```

A condition compares fields read before with numbers: `==`, `!=`, `<`, `<=`, `>`, `>=`, joined by `and` and `or`,
with brackets where needed. `and` is taken before `or`. The fields and the numbers are whole numbers. A name stands
for the nearest field of that name read before, in this group or in the groups around it. For one more condition
after `else`, write `else { if ... }`.

## A table of its own texts

Some tables keep their texts in a list at the start and name them by number later. `use names` makes the `name`
fields after it read from that list in place of `L2GameDataName.dat`. It stands at the top of the schema, after the
list, and names a list of texts, or a list of groups and the text field of its groups.

```
list compact strings {
    fstring text
    int
}
use names strings.text
```

## The rows and the end

The rows Cruma takes are the list named `records`. After the last field, a table holds the `SafePackage` mark that
ends the tables of the game, or nothing. Any other byte left means the schema does not fit. So a schema that ends a
field too early is found at once. A client whose tables hold more bytes after the rows reads nothing of such a table
until a schema passes over them. The message says how many there are:

```
format  1
file    npcgrp.dat
title   My Server
like    Interlude and Gracia

after records {
    skip 4
}
```

## A schema like another

A schema with a `like` line starts from another schema of the same table and says only what differs:

| Line | Meaning |
|---|---|
| `after speed { ... }` | Put these fields after the field `speed` |
| `before speed { ... }` | Put them before it |
| `replace speed { ... }` | Put them in its place |
| `remove speed` | Take the field out |

- When two fields have the same name, write the way to the one you mean with dots, as in `records.speed`. The way
  goes through the names of lists and may leave out its start: `decorations.scale` finds `records.decorations.scale`.
- A field of a structure is found through the name of the structure, as in `Look.meshes`, and the change holds for
  every use of the structure.
- When the name stands on the two sides of an `if`, the change holds on each side.
- A `like` schema may add structures of its own, and its `set` lines take the place of those of the same name.
- When several schemas of the table have the title, `like` takes the first of them in the order Cruma tries them. A
  schema cannot be like itself, directly or through others, and a structure cannot hold itself.
- A title of a built-in schema such as `P152 and later` reaches as far as the next schema of the same table. Ten
  tables of Essence have a schema of their own, titled `Essence 542 and later`, so `P152 and later` of these tables
  ends at 196. Five of them, and the two tables `WeaponGrp_Res.dat` and `ArmorGrp_Res.dat`, have one more schema,
  titled `Essence 563 and later`, for the client whose `L2.exe` names revision 563. For a table of Essence, write a
  schema that is `like Essence 542 and later` or `like Essence 563 and later`, the one that reads your client's
  table or comes closest.
- The built-in schemas name every field, so a change can name any of them. **Save built-in schemas** writes them out.

## Limits

A schema fits only a table that keeps inside these limits, so a wrong schema stops early:

| What | Limit |
|---|---|
| A text | 65 536 bytes. A UTF-16 text has an even number of bytes |
| A list in a row | 100 000 items |
| A list outside the rows | 2 000 000 items |
| A count or a text | No more than the bytes left in the file can hold |
| A `name` | -1, or the number of a text that exists |
| Work | 4 fields, structures, list items and comparisons a byte of the table, and 100 000 more |

The tables of the game take less than one step a byte. A schema itself is at most 1 MB, nests its blocks, structures
and brackets at most 32 deep, has at most 64 comparisons in a condition and at most 10 000 fields once its structures
are put in place. A schema over a limit is refused as a schema with a mistake: **PROBLEMS** names the file and the
line. A schema with `name` fields is not tried for a client that has no `L2GameDataName.dat`.
