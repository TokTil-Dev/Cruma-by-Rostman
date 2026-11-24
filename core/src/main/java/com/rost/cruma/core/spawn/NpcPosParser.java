package com.rost.cruma.core.spawn;

import com.rost.cruma.core.spawn.NpcPosFile.Problem;
import com.rost.cruma.core.spawn.NpcPosLexer.Kind;
import com.rost.cruma.core.spawn.NpcPosLexer.Token;
import com.rost.cruma.core.spawn.SpawnNpc.Spans;
import com.rost.cruma.core.spawn.SpawnPosition.Fixed;
import com.rost.cruma.core.spawn.SpawnPosition.Point;
import com.rost.cruma.core.spawn.SpawnPosition.Weighted;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class NpcPosParser {
    private static final Set<String> TOP_LEVEL =
            Set.of("territory_begin", "domain_begin", "npcmaker_begin", "npcmaker_ex_begin");
    private static final Set<String> TIME_UNITS = Set.of("sec", "min", "hour", "day");

    private final SpawnText source;
    private final List<Problem> problems = new ArrayList<>();
    private final NpcPosLexer lexer;
    private final List<SpawnTerritory> territories = new ArrayList<>();
    private final List<SpawnMaker> makers = new ArrayList<>();
    private int npcCount;

    private int lastEnd;
    private Token lastToken;

    NpcPosParser(SpawnText source) {
        this.source = source;
        this.lexer = new NpcPosLexer(source.text(), problems);
    }

    NpcPosFile parse() {
        while (lexer.peek().kind() != Kind.END) {
            var token = lexer.peek();
            if (token.isWord("territory_begin")) {
                territory(false);
            } else if (token.isWord("domain_begin")) {
                territory(true);
            } else if (token.isWord("npcmaker_begin")) {
                parseMaker(false);
            } else if (token.isWord("npcmaker_ex_begin")) {
                parseMaker(true);
            } else {
                addProblem(token, "Unexpected '" + token.text() + "' outside a definition");
                lexer.next();
                skipToTopLevel();
            }
        }
        return new NpcPosFile(source, territories, makers, problems);
    }

    private void territory(boolean domain) {
        var begin = lexer.next();
        var endWord = domain ? "domain_end" : "territory_end";
        var name = lexer.next();
        if (name.kind() != Kind.NAME) {
            addProblem(name, "A territory needs a [name]");
            skipPast(endWord);
            return;
        }
        var domainId = 0;
        if (domain && lexer.peek().isWord("domain_id")) {
            lexer.next();
            expect('=');
            domainId = lexer.next().intValue();
        }
        var open = lexer.peek();
        var points = new ArrayList<TerritoryPoint>();
        if (!expect('{')) {
            skipPast(endWord);
            return;
        }
        var close = open;
        while (lexer.peek().kind() != Kind.END) {
            var point = point();
            if (point == null) {
                skipPast(endWord);
                return;
            }
            points.add(point);
            close = lexer.next();
            if (close.is('}')) {
                break;
            }
            if (!close.is(';')) {
                addProblem(close, "Expected ';' or '}' between territory points");
                skipPast(endWord);
                return;
            }
        }
        for (var point : points) {
            if (point.top() <= point.bottom()) {
                addProblem(
                        begin,
                        "Territory ["
                                + name.text()
                                + "] has a point whose top "
                                + point.top()
                                + " is not above its bottom "
                                + point.bottom());
            }
        }
        var end = lexer.next();
        if (!end.isWord(endWord)) {
            addProblem(end, "Expected " + endWord);
            skipPast(endWord);
        }
        territories.add(new SpawnTerritory(
                territories.size(),
                name.text(),
                domain,
                domainId,
                points,
                begin.line(),
                new SourceSpan(begin.start(), end.end()),
                new SourceSpan(name.start(), name.end()),
                new SourceSpan(open.start(), close.end())));
    }

    private TerritoryPoint point() {
        var values = parseTuple(4);
        return values == null ? null : new TerritoryPoint(values[0], values[1], values[2], values[3]);
    }

    private int[] parseTuple(int count) {
        if (!expect('{')) {
            return null;
        }
        var values = new int[count];
        for (var i = 0; i < count; i++) {
            var number = lexer.next();
            if (number.kind() != Kind.INTEGER && number.kind() != Kind.FLOAT) {
                addProblem(number, "Expected a number");
                return null;
            }
            values[i] = number.kind() == Kind.FLOAT ? (int) Double.parseDouble(number.text()) : number.intValue();
            if (i + 1 < count && !expect(';')) {
                return null;
            }
        }
        return expect('}') ? values : null;
    }

    private void parseMaker(boolean extended) {
        var begin = lexer.next();
        var endWord = extended ? "npcmaker_ex_end" : "npcmaker_end";
        var npcWord = extended ? "npc_ex_begin" : "npc_begin";
        var territoryNames = new ArrayList<String>();
        var banned = new ArrayList<String>();
        lastEnd = begin.end();
        var territoriesStart = -1;
        while (true) {
            var name = lexer.next();
            if (name.kind() != Kind.NAME) {
                addProblem(name, "A maker needs its territory [names]");
                break;
            }
            if (territoriesStart < 0) {
                territoriesStart = name.start();
            }
            territoryNames.add(name.text());
            lastEnd = name.end();
            if (!lexer.peek().is(';')) {
                break;
            }
            lexer.next();
        }
        var territoriesSpan = territoriesStart < 0 ? SourceSpan.NONE : new SourceSpan(territoriesStart, lastEnd);
        var bannedSpan = SourceSpan.NONE;
        var nameSpan = SourceSpan.NONE;
        String makerName = "";
        String ai = "";
        String aiParameters = "";
        String spawnTime = "";
        String initialSpawn = "";
        var maximumNpc = -1;
        var maximumNpcSpan = SourceSpan.NONE;
        var aiSpan = SourceSpan.NONE;
        var aiParametersSpan = SourceSpan.NONE;
        var flying = false;
        var extra = new ArrayList<String>();
        var headerEnd = lastEnd;
        while (true) {
            var token = lexer.peek();
            if (token.kind() == Kind.END
                    || token.isWord(npcWord)
                    || token.isWord(endWord)
                    || TOP_LEVEL.contains(token.text()) && token.kind() == Kind.WORD) {
                break;
            }
            lexer.next();
            if (token.kind() != Kind.WORD || !lexer.peek().is('=')) {
                addProblem(token, "Unexpected '" + token.text() + "' in a maker");
                continue;
            }
            lexer.next();
            switch (token.text()) {
                case "banned_territory" -> {
                    banned.addAll(nameList());
                    bannedSpan = new SourceSpan(token.start(), lastEnd);
                }
                case "name" -> {
                    makerName = nameValue();
                    nameSpan = new SourceSpan(token.start(), lastEnd);
                }
                case "ai" -> {
                    ai = nameValue();
                    aiSpan = new SourceSpan(token.start(), lastEnd);
                }
                case "ai_parameters" -> {
                    aiParameters = rawValue();
                    aiParametersSpan = new SourceSpan(token.start(), lastEnd);
                }
                case "spawn_time" -> spawnTime = rawValue();
                case "initial_spawn" -> initialSpawn = rawValue();
                case "maximum_npc" -> {
                    maximumNpc = intValue();
                    maximumNpcSpan = new SourceSpan(token.start(), lastEnd);
                }
                case "flying" -> flying = intValue() == 1;
                default -> {
                    rawValue();
                    extra.add(settingText(token));
                }
            }
            headerEnd = lastEnd;
        }
        var npcs = new ArrayList<SpawnNpc>();
        while (lexer.peek().isWord(npcWord)) {
            var npc = parseNpc(extended, makers.size());
            if (npc != null) {
                npcs.add(npc);
            }
        }
        var end = lexer.peek();
        if (end.isWord(endWord)) {
            lexer.next();
        } else {
            addProblem(end, "Expected " + endWord);
            skipToTopLevel();
        }
        var span = new SourceSpan(begin.start(), end.isWord(endWord) ? end.end() : end.start());
        var endSpan = end.isWord(endWord) ? new SourceSpan(end.start(), end.end()) : SourceSpan.NONE;
        makers.add(new SpawnMaker(
                makers.size(),
                extended,
                territoryNames,
                banned,
                makerName,
                ai,
                aiParameters,
                maximumNpc,
                spawnTime,
                initialSpawn,
                flying,
                npcs,
                begin.line(),
                span,
                new SourceSpan(begin.start(), headerEnd),
                maximumNpcSpan,
                endSpan,
                aiSpan,
                aiParametersSpan,
                territoriesSpan,
                bannedSpan,
                nameSpan,
                extra));
    }

    private String settingText(Token key) {
        return source.text().substring(key.start(), lastEnd);
    }

    private SpawnNpc parseNpc(boolean extended, int makerIndex) {
        var begin = lexer.next();
        var endWord = extended ? "npc_ex_end" : "npc_end";
        var name = lexer.next();
        if (name.kind() != Kind.NAME) {
            addProblem(name, "An NPC entry needs an NPC [name]");
            skipPast(endWord);
            return null;
        }
        SpawnPosition position = null;
        var total = 1;
        String respawn = "";
        String respawnRandom = "";
        String nickname = "";
        String dbName = "";
        String privates = "";
        String ai = "";
        var positionSpan = SourceSpan.NONE;
        var totalSpan = SourceSpan.NONE;
        var respawnSpan = SourceSpan.NONE;
        var respawnRandomSpan = SourceSpan.NONE;
        var extra = new ArrayList<String>();
        while (true) {
            var token = lexer.peek();
            if (token.kind() == Kind.END
                    || token.isWord(endWord)
                    || token.kind() == Kind.WORD
                            && (TOP_LEVEL.contains(token.text())
                                    || token.text().startsWith("npcmaker")
                                    || token.text().startsWith("npc_")
                                            && token.text().endsWith("_begin"))) {
                break;
            }
            lexer.next();
            if (token.kind() != Kind.WORD) {
                addProblem(token, "Unexpected '" + token.text() + "' in NPC entry [" + name.text() + "]");
                continue;
            }
            if (lexer.peek().is('(')) {
                rawValue();
                extra.add(settingText(token));
                continue;
            }
            if (!lexer.peek().is('=')) {
                addProblem(token, "Expected '=' after " + token.text());
                continue;
            }
            lexer.next();
            switch (token.text()) {
                case "pos" -> {
                    position = parsePosition(name.text());
                    positionSpan = new SourceSpan(token.start(), lastEnd);
                }
                case "total" -> {
                    total = intValue();
                    totalSpan = new SourceSpan(token.start(), lastEnd);
                }
                case "respawn" -> {
                    respawn = rawValue();
                    respawnSpan = new SourceSpan(token.start(), lastEnd);
                }
                case "respawn_rand" -> {
                    respawnRandom = rawValue();
                    respawnRandomSpan = new SourceSpan(token.start(), lastEnd);
                }
                case "nickname" -> nickname = nameValue();
                case "dbname" -> {
                    dbName = nameValue();
                    extra.add(settingText(token));
                }
                case "Privates" -> {
                    privates = nameValue();
                    extra.add(settingText(token));
                }
                case "ai" -> {
                    ai = nameValue();
                    extra.add(settingText(token));
                }
                default -> {
                    rawValue();
                    extra.add(settingText(token));
                }
            }
        }
        var end = lexer.peek();
        SourceSpan endSpan;
        if (end.isWord(endWord)) {
            lexer.next();
            endSpan = new SourceSpan(end.start(), end.end());
        } else {
            addProblem(end, "Expected " + endWord + " for NPC entry [" + name.text() + "]");
            endSpan = SourceSpan.NONE;
        }
        if (position == null) {
            addProblem(begin, "NPC entry [" + name.text() + "] has no pos");
            position = SpawnPosition.ANYWHERE;
        }
        var entrySpan = new SourceSpan(begin.start(), endSpan.isPresent() ? endSpan.end() : end.start());
        var spans = new Spans(
                entrySpan,
                new SourceSpan(name.start(), name.end()),
                positionSpan,
                totalSpan,
                respawnSpan,
                respawnRandomSpan,
                endSpan);
        return new SpawnNpc(
                npcCount++,
                makerIndex,
                name.text(),
                position,
                total,
                respawn,
                respawnRandom,
                nickname,
                dbName,
                privates,
                ai,
                begin.line(),
                spans,
                extra);
    }

    private SpawnPosition parsePosition(String npcName) {
        var first = lexer.peek();
        if (first.isWord("anywhere")) {
            lexer.next();
            lastEnd = first.end();
            return SpawnPosition.ANYWHERE;
        }
        if (!expect('{')) {
            rawValue();
            return SpawnPosition.ANYWHERE;
        }
        if (lexer.peek().is('{')) {
            var points = new ArrayList<Point>();
            while (lexer.peek().is('{')) {
                lexer.next();
                var values = new int[5];
                for (var i = 0; i < 5; i++) {
                    values[i] = lexer.next().intValue();
                    if (i < 4) {
                        expect(';');
                    }
                }
                expect('%');
                expect('}');
                points.add(new Point(values[0], values[1], values[2], values[3], values[4]));
                if (lexer.peek().is(';')) {
                    lexer.next();
                }
            }
            if (expect('}')) {
                lastEnd = lastToken.end();
            }
            return new Weighted(points);
        }
        var values = new ArrayList<Integer>();
        var closed = false;
        while (true) {
            var token = lexer.peek();
            if (token.kind() == Kind.INTEGER) {
                values.add(lexer.next().intValue());
            } else if (token.is(';')) {
                lexer.next();
            } else if (token.is('}')) {
                lastEnd = lexer.next().end();
                closed = true;
                break;
            } else {
                break;
            }
        }
        if (!closed || values.size() != 4) {
            addProblem(first, "The position of [" + npcName + "] needs {x;y;z;heading}");
            return SpawnPosition.ANYWHERE;
        }
        return new Fixed(values.get(0), values.get(1), values.get(2), values.get(3));
    }

    private boolean expect(char punct) {
        var token = lexer.next();
        lastToken = token;
        if (token.is(punct)) {
            lastEnd = token.end();
            return true;
        }
        addProblem(token, "Expected '" + punct + "' but found '" + token.text() + "'");
        return false;
    }

    private String nameValue() {
        var token = lexer.next();
        lastEnd = token.end();
        if (token.kind() == Kind.NAME) {
            return token.text();
        }
        addProblem(token, "Expected a [name] but found '" + token.text() + "'");
        return token.text();
    }

    private int intValue() {
        var token = lexer.next();
        lastEnd = token.end();
        if (token.kind() != Kind.INTEGER) {
            addProblem(token, "Expected an integer but found '" + token.text() + "'");
        }
        return token.intValue();
    }

    private List<String> nameList() {
        var names = new ArrayList<String>();
        if (!lexer.peek().is('{')) {
            names.add(nameValue());
            return names;
        }
        lexer.next();
        while (lexer.peek().kind() == Kind.NAME) {
            names.add(lexer.next().text());
            if (lexer.peek().is(';')) {
                lexer.next();
            }
        }
        expect('}');
        return names;
    }

    private String rawValue() {
        var first = lexer.next();
        var end = first.end();
        if (first.is('{')) {
            end = skipBalanced('{', '}', 1);
        } else if (first.is('(')) {
            end = skipBalanced('(', ')', 1);
        } else if (first.kind() == Kind.INTEGER || first.kind() == Kind.FLOAT) {
            var unit = lexer.peek();
            if (unit.kind() == Kind.WORD && TIME_UNITS.contains(unit.text()) && unit.start() == first.end()) {
                end = lexer.next().end();
            }
        } else if (first.kind() == Kind.WORD && lexer.peek().is('(')) {
            lexer.next();
            end = skipBalanced('(', ')', 1);
        }
        lastEnd = end;
        return lexer.getText().substring(first.start(), end);
    }

    private int skipBalanced(char open, char close, int depth) {
        var end = 0;
        while (depth > 0) {
            var token = lexer.next();
            if (token.kind() == Kind.END) {
                addProblem(token, "Missing '" + close + "'");
                return token.start();
            }
            if (token.is(open)) {
                depth++;
            } else if (token.is(close)) {
                depth--;
            }
            end = token.end();
        }
        return end;
    }

    private void skipPast(String word) {
        while (lexer.peek().kind() != Kind.END) {
            var token = lexer.peek();
            if (token.kind() == Kind.WORD && TOP_LEVEL.contains(token.text())) {
                return;
            }
            lexer.next();
            if (token.isWord(word)) {
                return;
            }
        }
    }

    private void skipToTopLevel() {
        while (lexer.peek().kind() != Kind.END
                && !(lexer.peek().kind() == Kind.WORD
                        && TOP_LEVEL.contains(lexer.peek().text()))) {
            lexer.next();
        }
    }

    private void addProblem(Token token, String message) {
        problems.add(new Problem(token.line(), message));
    }
}
