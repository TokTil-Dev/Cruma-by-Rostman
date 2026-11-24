package com.rost.cruma.viewer.ui.aside;

import com.rost.cruma.core.schema.TableReport.State;
import com.rost.cruma.core.spawn.NpcPosDocument;
import com.rost.cruma.core.spawn.NpcPosFile;
import com.rost.cruma.core.world.WorldMapCatalog;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.config.ClientProfiles;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.launch.ClientInstallation;
import com.rost.cruma.viewer.launch.ClientTables;
import com.rost.cruma.viewer.launch.StartPlaces;
import com.rost.cruma.viewer.spawn.SpawnSource;
import com.rost.cruma.viewer.spawn.SpawnTotals;
import com.rost.cruma.viewer.ui.CardWidgets;
import com.rost.cruma.viewer.ui.ClientFolderStatus;
import com.rost.cruma.viewer.ui.FontManager;
import com.rost.cruma.viewer.ui.GeodataWidgets;
import com.rost.cruma.viewer.ui.HudWidgets;
import com.rost.cruma.viewer.ui.LatestAnswer;
import imgui.ImGui;
import imgui.flag.ImGuiComboFlags;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jetbrains.annotations.Nullable;

public final class ClientAside implements StartAside {
    private static final float PAD = CardWidgets.PAD;
    private static final int TITLE_SIZE = 28;

    private static final int NAMED_TILES = 24;

    private static final ExecutorService SPAWN_WORKER = Executors.newSingleThreadExecutor(work -> {
        var thread = new Thread(work, "cruma-client-spawns");
        thread.setDaemon(true);
        return thread;
    });

    public record Saved(Path root, String label, @Nullable List<String> tiles, int namedMaps, String problem) {}

    private record FactsKey(Path root, boolean classic) {}

    private record Facts(boolean readable, int classicOnly) {
        static final Facts UNKNOWN = new Facts(true, -1);
    }

    private record MapKey(Path root, WorldVariant world) {}

    private record TablesKey(Path root, WorldVariant world) {}

    private record Spawns(@Nullable SpawnTotals totals, String problem) {}

    private record Counted(long size, FileTime time, SpawnTotals totals) {}

    private final Map<Path, Counted> counted = new ConcurrentHashMap<>();
    private List<Saved> saved = List.of();
    private boolean settingsRequested;
    private boolean tablesRequested;

    private @Nullable Path against;
    private @Nullable Path againstFor;

    private LatestAnswer<FactsKey, Facts> facts = newFacts();
    private LatestAnswer<String, ClientFolderStatus> geodata = newGeodata();
    private LatestAnswer<String, ClientFolderStatus> scripts = newScripts();
    private LatestAnswer<MapKey, Boolean> mapImage = newMapImage();
    private LatestAnswer<String, Spawns> spawns = newSpawns();
    private LatestAnswer<TablesKey, ClientTables.Key> tables = newTables();

    private @Nullable Object settingsSeen;
    private @Nullable ClientInstallation clientSeen;
    private String geodataFolder = "";
    private String scriptFolder = "";
    private int namedPlaces;
    private @Nullable Path configDirectory;

    private final Map<String, float[]> places = new TreeMap<>();
    private String shownState = "";
    private String shownComparison = "";
    private String shownTables = "";
    private ClientTables.@Nullable Key tablesAsked;

    @Override
    public String getLabel() {
        return "Client";
    }

    public void setSavedClients(List<Saved> clients) {
        saved = List.copyOf(clients);
    }

    public boolean takeSettingsRequest() {
        var asked = settingsRequested;
        settingsRequested = false;
        return asked;
    }

    public boolean takeTablesRequest() {
        var asked = tablesRequested;
        tablesRequested = false;
        return asked;
    }

    public void forget() {
        facts = newFacts();
        geodata = newGeodata();
        scripts = newScripts();
        mapImage = newMapImage();
        spawns = newSpawns();
        tables = newTables();
        tablesAsked = null;
        settingsSeen = null;
    }

    @Override
    public void close() {
        forget();
        counted.clear();
        settingsRequested = false;
        tablesRequested = false;
        places.clear();
    }

    @Override
    public void draw(Context context, float width, float height) {
        places.clear();
        var client = context.client();
        var left = ImGui.getCursorScreenPosX();
        if (client == null) {
            shownState = "no client";
            CardWidgets.emptyCard(
                    places,
                    left,
                    width,
                    "No client chosen",
                    "Choose one in the list at the left, or add one with Add client…",
                    null,
                    "client.empty/No client chosen");
            return;
        }
        follow(context.loader(), client);
        var base = context.loader().getConfigDirectory();
        var factsKey = new FactsKey(client.root(), client.hasClassicMaps());
        var known = facts.isAnswered(factsKey) ? facts.get(factsKey) : Facts.UNKNOWN;
        if (!facts.isAnswered(factsKey)) {
            facts.get(factsKey);
        }
        if (!known.readable()) {
            shownState = "unreadable";
            CardWidgets.emptyCard(
                    places,
                    left,
                    width,
                    "This client cannot be read",
                    "Its folder is not there any more. Choose it again in the list when it is back, or remove it.",
                    client.root(),
                    "client.empty/This client cannot be read");
            return;
        }
        drawHeader(context.fonts(), client, left, width);
        drawNumbers(context.fonts(), client, known, left, width);
        drawSpawns(context.fonts(), base, left, width);
        drawComparison(client, left, width);
        drawFolders(client, base, left, width);
    }

    private void drawHeader(FontManager fonts, ClientInstallation client, float left, float width) {
        var inner = width - 2 * PAD;
        var top = CardWidgets.beginCard(left);
        ImGui.textColored(GeodataWidgets.MUTED, "THIS CLIENT");
        var mine = savedOf(client.root());
        var label = mine == null ? folderName(client.root()) : mine.label();
        CardWidgets.bigText(fonts, TITLE_SIZE, HudWidgets.TEXT, HudWidgets.truncateToWidth(label, inner));
        CardWidgets.folderLine(client.root(), inner);
        GeodataWidgets.space(GeodataWidgets.ROW);
        drawRanks(client, inner);
        CardWidgets.endCard(places, left, width, top, "client.header");
    }

    private void drawRanks(ClientInstallation client, float inner) {
        var counts = new TreeMap<Integer, List<String>>();
        for (var entry : saved) {
            if (entry.tiles() != null) {
                counts.computeIfAbsent(entry.tiles().size(), ignored -> new ArrayList<>())
                        .add(entry.label());
            }
        }
        var own = client.tiles().size();
        counts.computeIfAbsent(own, ignored -> new ArrayList<>());
        var least = counts.firstKey();
        var most = counts.lastKey();
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var lineY = y + 8;
        var span = inner - 12;
        draw.addLine(x + 6, lineY, x + 6 + span, lineY, GeodataWidgets.DIVIDER, 2);
        var mouseX = ImGui.getIO().getMousePosX();
        var mouseY = ImGui.getIO().getMousePosY();
        String hovered = null;
        for (var entry : counts.entrySet()) {
            var share = most == least ? .5f : (entry.getKey() - least) / (float) (most - least);
            var dotX = x + 6 + span * share;
            var isOwn = entry.getKey() == own;
            draw.addCircleFilled(dotX, lineY, isOwn ? 6 : 4, isOwn ? HudWidgets.ACCENT : GeodataWidgets.FAINT);
            if (Math.abs(mouseX - dotX) <= 6 && Math.abs(mouseY - lineY) <= 8 && ImGui.isWindowHovered()) {
                var names = new ArrayList<>(entry.getValue());
                if (isOwn && names.isEmpty()) {
                    names.add("this client");
                }
                hovered = String.join(", ", names) + " · " + entry.getKey() + " tiles";
            }
        }
        var caption = least + " tiles";
        var end = most + " tiles";
        draw.addText(x, lineY + 10, GeodataWidgets.FAINT, caption);
        draw.addText(x + inner - ImGui.calcTextSizeX(end), lineY + 10, GeodataWidgets.FAINT, end);
        var middle = "Saved clients by tiles";
        draw.addText(x + (inner - ImGui.calcTextSizeX(middle)) * .5f, lineY + 10, GeodataWidgets.FAINT, middle);
        ImGui.dummy(inner, 18 + ImGui.getFontSize());
        places.put("client.ranks", new float[] {x, y, x + inner, y + 18 + ImGui.getFontSize()});
        if (hovered != null) {
            HudWidgets.showTooltip(hovered);
        }
    }

    private void drawNumbers(FontManager fonts, ClientInstallation client, Facts known, float left, float width) {
        var numbers = new ArrayList<CardWidgets.Number>();
        numbers.add(new CardWidgets.Number(
                Integer.toString(client.tiles().size()),
                client.tiles().size() == 1 ? "tile" : "tiles",
                "Map tiles of the " + client.variant().getLabel() + " world, as on the map."));
        numbers.add(new CardWidgets.Number(
                Integer.toString(namedPlaces),
                namedPlaces == 1 ? "named place" : "named places",
                "Named places of Settings, Places, that lie in these tiles."));
        if (client.hasClassicMaps()) {
            numbers.add(new CardWidgets.Number(
                    known.classicOnly() < 0 ? "…" : Integer.toString(known.classicOnly()),
                    known.classicOnly() == 1 ? "Classic tile" : "Classic tiles",
                    "Tiles that only the Classic world has: maps named X_Y_Classic with no main map of their own."));
        }
        var mine = savedOf(client.root());
        var named = mine == null ? 0 : mine.namedMaps();
        numbers.add(new CardWidgets.Number(
                Integer.toString(named),
                named == 1 ? "other map" : "other maps",
                "Maps with a name of their own, such as Lobby01, as More, Other maps lists them."));
        CardWidgets.numberRow(places, fonts, numbers, left, width, "client.numbers");
    }

    private void drawSpawns(FontManager fonts, Path base, float left, float width) {
        var folder = ClientFolderStatus.resolve(scriptFolder, base);
        if (folder == null) {
            shownState = "no scripts";
            CardWidgets.emptyCard(
                    places,
                    left,
                    width,
                    "No server script folder",
                    "Set it in Settings of this client to count its zones, makers and NPCs.",
                    null,
                    "client.empty/No server script folder");
            return;
        }
        var key = folder.toString();
        var answer = spawns.isAnswered(key) ? spawns.get(key) : null;
        if (!spawns.isAnswered(key)) {
            spawns.get(key);
        }
        if (answer != null && answer.totals() == null) {
            shownState = "spawns failed";
            CardWidgets.emptyCard(
                    places,
                    left,
                    width,
                    "Cannot count the NPC spawns",
                    answer.problem(),
                    null,
                    "client.empty/Cannot count the NPC spawns");
            return;
        }
        var totals = answer == null ? null : answer.totals();
        shownState = totals == null ? "spawns loading" : "spawns";
        var tip = "In npcpos.txt of the server scripts, as the NPC Spawns panel counts them.";
        CardWidgets.numberRow(
                places,
                fonts,
                List.of(
                        spawnNumber(totals == null ? -1 : totals.zones(), "zone", "zones", tip),
                        spawnNumber(totals == null ? -1 : totals.makers(), "maker", "makers", tip),
                        spawnNumber(totals == null ? -1 : totals.npcs(), "NPC", "NPCs", tip)),
                left,
                width,
                "client.spawns");
    }

    private void drawComparison(ClientInstallation client, float left, float width) {
        var others = new ArrayList<Saved>();
        for (var entry : saved) {
            if (!entry.root().equals(client.root()) && entry.tiles() != null) {
                others.add(entry);
            }
        }
        var inner = width - 2 * PAD;
        var top = CardWidgets.beginCard(left);
        if (others.isEmpty()) {
            ImGui.textColored(GeodataWidgets.MUTED, "AGAINST ANOTHER CLIENT");
            ImGui.textColored(GeodataWidgets.FAINT, "No other saved client to compare with.");
            CardWidgets.endCard(places, left, width, top, "client.against");
            return;
        }
        var other = againstOf(client, others);
        var comboWidth = (float) Math.floor(Math.min(220, inner * .55f));
        var lineTop = ImGui.getCursorScreenPosY();
        ImGui.alignTextToFramePadding();
        ImGui.textColored(GeodataWidgets.MUTED, "AGAINST");
        ImGui.sameLine();
        var comboLeft = left + width - PAD - comboWidth;
        ImGui.setCursorScreenPos(comboLeft, lineTop);
        ImGui.setNextItemWidth(comboWidth);
        places.put(
                "client.against-choice",
                new float[] {comboLeft, lineTop, comboLeft + comboWidth, lineTop + ImGui.getFrameHeight()});
        if (HudWidgets.beginCombo("##client-against", other.label(), ImGuiComboFlags.HeightRegular)) {
            for (var entry : others) {
                if (ImGui.selectable(
                        entry.label() + " · " + entry.tiles().size() + " tiles##" + entry.root(),
                        entry.root().equals(other.root()))) {
                    against = entry.root();
                    againstFor = client.root();
                }
                places.put("client.against-row/" + entry.label(), new float[] {
                    ImGui.getItemRectMinX(), ImGui.getItemRectMinY(), ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY()
                });
            }
            ImGui.endCombo();
        }
        GeodataWidgets.tip("The saved client to compare the tiles with.");
        var mine = Set.copyOf(client.tiles());
        var theirs = Set.copyOf(other.tiles());
        var more = difference(client.tiles(), theirs);
        var fewer = difference(other.tiles(), mine);
        shownComparison = other.label() + " +" + more.size() + " -" + fewer.size();
        if (more.isEmpty() && fewer.isEmpty()) {
            ImGui.alignTextToFramePadding();
            ImGui.textColored(HudWidgets.TEXT, "The same tiles as " + other.label());
        } else {
            differenceLine(more, "more", inner);
            differenceLine(fewer, "fewer", inner);
        }
        CardWidgets.endCard(places, left, width, top, "client.against");
    }

    private void differenceLine(List<String> tiles, String word, float inner) {
        var count =
                tiles.isEmpty() ? "No tiles " + word : tiles.size() + (tiles.size() == 1 ? " tile " : " tiles ") + word;
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        ImGui.alignTextToFramePadding();
        ImGui.textColored(HudWidgets.TEXT, count);
        var hovered = ImGui.isItemHovered();
        if (!tiles.isEmpty()) {
            var room = inner - ImGui.calcTextSizeX(count) - GeodataWidgets.BLOCK;
            var names = HudWidgets.truncateToWidth(String.join(", ", tiles), room);
            ImGui.sameLine(0, 0);
            ImGui.setCursorScreenPos(x + inner - ImGui.calcTextSizeX(names), y);
            ImGui.alignTextToFramePadding();
            ImGui.textColored(GeodataWidgets.MUTED, names);
            hovered |= ImGui.isItemHovered();
        }
        if (hovered && !tiles.isEmpty()) {
            var shown = tiles.subList(0, Math.min(NAMED_TILES, tiles.size()));
            HudWidgets.showTooltip(String.join(", ", shown)
                    + (tiles.size() > shown.size() ? " and " + (tiles.size() - shown.size()) + " more" : ""));
        }
    }

    private void drawFolders(ClientInstallation client, Path base, float left, float width) {
        var inner = width - 2 * PAD;
        var top = CardWidgets.beginCard(left);
        ImGui.textColored(GeodataWidgets.MUTED, "FOLDERS");
        folderRow("Geodata", geodataFolder, geodata, base, inner);
        folderRow("Server scripts", scriptFolder, scripts, base, inner);
        var key = new MapKey(client.root(), client.variant());
        var ready = mapImage.isAnswered(key) ? mapImage.get(key) : null;
        if (!mapImage.isAnswered(key)) {
            mapImage.get(key);
        }
        CardWidgets.statusRow(
                "Map image",
                ready == null ? new ClientFolderStatus("…", 0) : ClientFolderStatus.ofMapImage(ready),
                inner);
        drawTables(client, inner);
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (CardWidgets.link(
                "client-settings", "Settings of this client", "Opens Settings on the page of this client.")) {
            settingsRequested = true;
        }
        CardWidgets.endCard(places, left, width, top, "client.folders");
    }

    private void drawTables(ClientInstallation client, float inner) {
        var key = tables.get(new TablesKey(client.root(), client.variant()));
        if (key == null
                || !key.system().startsWith(client.root().toAbsolutePath().normalize())) {
            return;
        }
        var checked = ClientTables.getChecked(key);
        if (checked == null) {
            if (!key.equals(tablesAsked)) {
                tablesAsked = key;
                ClientTables.start(key);
            }
            shownTables = "checking";
            return;
        }
        var notRead = checked.count(State.NOT_READ);
        var partly = checked.count(State.PARTIAL);
        shownTables = notRead + " not read, " + partly + " partly";
        if (notRead + partly == 0) {
            return;
        }
        var text = notRead > 0 && partly > 0
                ? notRead + " not read, " + partly + " partly"
                : notRead > 0 ? notRead + " not read" : partly + " partly read";
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        ImGui.setNextItemAllowOverlap();
        var pressed = ImGui.invisibleButton("##client-tables", inner, HudWidgets.CONTROL_HEIGHT);
        var hovered = ImGui.isItemHovered();
        if (hovered) {
            ImGui.getWindowDrawList()
                    .addRectFilled(x - 6, y, x + inner + 6, y + HudWidgets.CONTROL_HEIGHT, GeodataWidgets.HOVER, 6f);
            var lost = new ArrayList<String>();
            for (var report : checked.reports()) {
                if (report.state() == State.NOT_READ || report.state() == State.PARTIAL) {
                    lost.add(report.file().getFileName() + ", " + ClientTables.describeState(report.state()) + ": "
                            + ClientTables.describe(report, key.system()));
                }
            }
            HudWidgets.showTooltip(String.join("\n", lost) + "\n\nClick to open Client tables in Settings.");
        }
        ImGui.setCursorScreenPos(x, y);
        CardWidgets.statusRow("Client tables", new ClientFolderStatus(text, GeodataWidgets.WARNING), inner);
        places.put("client.tables", new float[] {x, y, x + inner, y + HudWidgets.CONTROL_HEIGHT});
        if (pressed) {
            tablesRequested = true;
        }
    }

    private void folderRow(
            String label, String typed, LatestAnswer<String, ClientFolderStatus> answers, Path base, float inner) {
        if (typed.isBlank()) {
            CardWidgets.statusRow(label, new ClientFolderStatus("not set", 0), inner);
            return;
        }
        var folder = ClientFolderStatus.resolve(typed, base);
        var key = folder == null ? typed : folder.toString();
        var status = answers.isAnswered(key) ? answers.get(key) : new ClientFolderStatus("…", 0);
        if (!answers.isAnswered(key)) {
            answers.get(key);
        }
        if (CardWidgets.statusRow(label, status, inner)) {
            HudWidgets.showTooltip(key);
        }
    }

    private void follow(ConfigLoader loader, ClientInstallation client) {
        var settings = loader.getEffectiveSettings();
        if (settings == settingsSeen && client.equals(clientSeen)) {
            return;
        }
        settingsSeen = settings;
        clientSeen = client;
        configDirectory = loader.getConfigDirectory();
        try {
            geodataFolder = ClientProfiles.geodataPath(settings, client.root(), configDirectory, client.variant());
            scriptFolder = ClientProfiles.scriptPath(settings, client.root(), configDirectory, client.variant());
        } catch (RuntimeException unreadable) {
            geodataFolder = "";
            scriptFolder = "";
        }
        var tiles = Set.copyOf(client.tiles());
        var count = 0;
        try {
            for (var location : loader.load().viewer().camera().locations()) {
                if (tiles.contains(StartPlaces.tileOf(location))) {
                    count++;
                }
            }
        } catch (RuntimeException unreadable) {
        }
        namedPlaces = count;
    }

    private @Nullable Saved savedOf(Path root) {
        for (var entry : saved) {
            if (entry.root().equals(root)) {
                return entry;
            }
        }
        return null;
    }

    private Saved againstOf(ClientInstallation client, List<Saved> others) {
        if (client.root().equals(againstFor)) {
            for (var entry : others) {
                if (entry.root().equals(against)) {
                    return entry;
                }
            }
        }
        var own = client.tiles().size();
        var below = others.stream()
                .filter(entry -> entry.tiles().size() < own)
                .max(Comparator.comparingInt((Saved entry) -> entry.tiles().size()));
        return below.orElseGet(() -> others.stream()
                .min(Comparator.comparingInt((Saved entry) -> entry.tiles().size()))
                .orElseThrow());
    }

    private LatestAnswer<FactsKey, Facts> newFacts() {
        return new LatestAnswer<>(ClientAside::readFacts, Facts.UNKNOWN);
    }

    private LatestAnswer<String, ClientFolderStatus> newGeodata() {
        return new LatestAnswer<>(typed -> ClientFolderStatus.ofGeodata(Path.of(typed)), ClientFolderStatus.NONE);
    }

    private LatestAnswer<String, ClientFolderStatus> newScripts() {
        return new LatestAnswer<>(typed -> ClientFolderStatus.ofScripts(Path.of(typed)), ClientFolderStatus.NONE);
    }

    private LatestAnswer<MapKey, Boolean> newMapImage() {
        return new LatestAnswer<>(
                key -> configDirectory != null
                        && ClientFolderStatus.isMapImageReady(key.root(), key.world(), configDirectory),
                false);
    }

    private LatestAnswer<TablesKey, ClientTables.Key> newTables() {
        return new LatestAnswer<>(key -> ClientTables.Key.of(key.root(), key.world()), null);
    }

    private LatestAnswer<String, Spawns> newSpawns() {
        return new LatestAnswer<>(folder -> countSpawns(Path.of(folder)), new Spawns(null, ""), SPAWN_WORKER);
    }

    private Spawns countSpawns(Path chosen) {
        var folder = SpawnSource.resolveFolder(chosen);
        var positions = folder == null ? null : SpawnSource.find(folder, SpawnSource.POSITIONS);
        if (positions == null) {
            return new Spawns(null, "No " + SpawnSource.POSITIONS + " in " + chosen + ".");
        }
        try {
            var size = Files.size(positions);
            var time = Files.getLastModifiedTime(positions);
            var known = counted.get(positions);
            if (known != null && known.size() == size && known.time().equals(time)) {
                return new Spawns(known.totals(), "");
            }
            var totals = SpawnTotals.of(new NpcPosDocument(NpcPosFile.read(positions)).getState());
            counted.put(positions, new Counted(size, time, totals));
            return new Spawns(totals, "");
        } catch (IOException | RuntimeException failure) {
            return new Spawns(null, "Cannot read " + positions + ": " + failure.getMessage());
        }
    }

    private static CardWidgets.Number spawnNumber(long value, String one, String many, String tip) {
        return new CardWidgets.Number(value < 0 ? "…" : CardWidgets.compact(value), value == 1 ? one : many, tip);
    }

    private static Facts readFacts(FactsKey key) {
        if (!Files.isDirectory(key.root())) {
            return new Facts(false, -1);
        }
        if (!key.classic()) {
            return new Facts(true, -1);
        }
        var main = new HashSet<>(WorldMapCatalog.discover(key.root(), WorldVariant.MAIN));
        var only = 0;
        for (var tile : WorldMapCatalog.discover(key.root(), WorldVariant.CLASSIC)) {
            if (!main.contains(tile)) {
                only++;
            }
        }
        return new Facts(true, only);
    }

    private static List<String> difference(List<String> tiles, Set<String> others) {
        var result = new ArrayList<String>();
        for (var tile : tiles) {
            if (!others.contains(tile)) {
                result.add(tile);
            }
        }
        result.sort(Comparator.comparing((String tile) -> Integer.parseInt(tile.split("_")[0]))
                .thenComparing(tile -> Integer.parseInt(tile.split("_")[1])));
        return result;
    }

    private static String folderName(Path root) {
        var name = root.getFileName();
        return name == null ? root.toString() : name.toString();
    }
}
