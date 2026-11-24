package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoFormat;
import com.rost.cruma.core.geodata.GeoPathNodeFolder.State;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.core.geodata.PathNodeGraph.ZoneTable;
import com.rost.cruma.core.geodata.generation.ClientGeodataBuild;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.geodata.GeodataBuildJob;
import com.rost.cruma.viewer.geodata.GeodataBuildJob.PathNodes;
import com.rost.cruma.viewer.geodata.GeodataBuildJob.StartSources;
import com.rost.cruma.viewer.geodata.PathNodeChecks;
import com.rost.cruma.viewer.spawn.SpawnSource;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiWindowFlags;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class GeodataBuildControls {
    private static final String[] ABOUT = {
        "The build repeats NCsoft's own geodata builder step by step: the same box, the same drops and the same walk"
                + " from cell to cell, as in the code of the 152 and 162 clients.",
        "From a 162 client, a typical region has 99.6% of its cells the same as the 162 server's file; 154 of 224"
                + " regions match in 99% of their cells or more.",
        "A region differs more when the server's file was built from other maps, such as an older chronicle's, or"
                + " reached places that no start point in the client leads to, such as some roofs over dungeons. Start"
                + " points from the server's NPC spawns add some of them.",
        "C4 and Gracia servers have files from an older builder, which left gaps on steep slopes. The viewer uses the"
            + " newer one for every client, so builds from those clients differ in about 1% more cells, up to 3%: on"
            + " steep slopes and at cell corners, where NCsoft changed the builder. There the new files should be"
            + " better."
    };

    private static final GeoTile ANY_TILE = new GeoTile(20, 18);

    private static final String[] FORMAT_LABELS = {"L2OFF", "L2J"};
    private static final String[] SERVER_LABELS = {"Auto", "320 zones", "340 zones"};

    private static boolean withPathNodes;

    private static int server;

    private static final PathNodeChecks PATH_NODE_CHECKS = new PathNodeChecks();

    private static GeoFormat format = GeoFormat.L2OFF;

    private static boolean startAtSpawns = true;

    private static final String REBUILD_POPUP = "Rebuild regions##geodata-build";
    private static final String GRAPH_POPUP = "Pathnode graph##geodata-graph";

    private @Nullable Path scriptFolder;
    private @Nullable Path npcpos;
    private long sourcesCheckedAt;

    private Path askGraph;
    private Path graphFolder;
    private boolean graphReplaces;
    private boolean graphBackup = true;

    private Plan chosenPlan;
    private List<GeoTile> plannedFor;
    private Path plannedClient;
    private GeoFormat plannedFormat;
    private long plannedAt;
    private List<GeoTile> askRebuild;
    private List<GeoTile> confirming;
    private boolean aboutOpen;
    private GeoFormat countedFormat;
    private final GeodataBuildJob job = GeodataBuildJob.getShared();
    private WorldVariant variant = WorldVariant.MAIN;
    private int[] counts;
    private Path countedClient;
    private long countedAt;
    private boolean reported = true;

    record Outcome(String text, int color, @Nullable Path folder) {
        Outcome(String text, int color) {
            this(text, color, null);
        }
    }

    static String formatRegions(List<GeoTile> tiles) {
        return tiles.size() == 1
                ? tiles.getFirst().x() + "_" + tiles.getFirst().y()
                : tiles.size() + " selected regions";
    }

    static String formatRegionNames(List<GeoTile> tiles) {
        return tiles.size() == 1 ? "region " + formatRegions(tiles) : "the " + formatRegions(tiles);
    }

    static String tableName(ZoneTable table) {
        return table.getZones() + " zones";
    }

    static boolean isSameFolder(@Nullable Path a, @Nullable Path b) {
        return a != null
                && b != null
                && a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    static String formatDuration(long seconds) {
        if (seconds < 60) {
            return seconds + " s";
        }
        var minutes = (seconds + 30) / 60;
        return minutes < 60 ? minutes + " min" : minutes / 60 + " h " + minutes % 60 + " min";
    }

    GeodataBuildJob getJob() {
        return job;
    }

    boolean isRunning(@Nullable Path folder) {
        var progress = job.getProgress();
        return progress.running() && isSameFolder(progress.folder(), folder);
    }

    boolean progressCard(String id, @Nullable Path folder) {
        if (!isRunning(folder)) {
            return false;
        }
        var progress = job.getProgress();
        if (progress.graph()) {
            if (GeodataWidgets.progress(
                    id, "Building the pathnode graph", progress.stage(), progress.fraction(), "Stop")) {
                job.stop();
            }
            GeodataWidgets.tip(
                    "Builds the server's pathnode graph of every region in the folder. Stopped, it leaves the old"
                            + " graph as it was.");
            return true;
        }
        var tile = progress.tile() == null
                ? ""
                : progress.tile().x() + "_" + progress.tile().y() + " · ";
        var stage = progress.stage().isBlank() ? "starting" : progress.stage().toLowerCase(Locale.ROOT);
        var left = progress.secondsLeft();
        var detail = tile + stage + (left > 0 ? " · " + formatDuration(left) + " left" : "");
        var title = progress.total() == 0
                ? "Finding the client's maps"
                : "Region " + Math.min(progress.done() + 1, progress.total()) + " of " + progress.total();
        if (GeodataWidgets.progress(id, title, detail, progress.overall(), "Stop")) {
            job.stop();
        }
        GeodataWidgets.tip("Regions appear as they are done. A stopped build goes on where it stopped.");
        return true;
    }

    void about(String id) {
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        if (HudWidgets.disclosure(id, "How accurate the build is", aboutOpen)) {
            aboutOpen = !aboutOpen;
        }
        if (!aboutOpen) {
            return;
        }
        ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.MUTED);
        ImGui.pushTextWrapPos(0);
        for (var paragraph : ABOUT) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            ImGui.textUnformatted(paragraph);
        }
        ImGui.popTextWrapPos();
        ImGui.popStyleColor();
    }

    void start(Path client, GeoTile near) {
        job.start(
                client,
                variant,
                getFolder(client),
                format,
                near,
                null,
                false,
                pathNodes(getFolder(client)),
                getStartSources());
        reported = false;
        counts = null;
    }

    void start(Path client, GeoTile near, List<GeoTile> tiles, boolean replace) {
        job.start(
                client,
                variant,
                getFolder(client),
                format,
                near,
                tiles,
                replace,
                pathNodes(getFolder(client)),
                getStartSources());
        reported = false;
        counts = null;
        chosenPlan = null;
    }

    boolean buildActions(String id, Path client, List<GeoTile> chosen, GeoTile near, boolean rebuild) {
        var plan = computePlan(client, chosen);
        var allMissing = getMissing(client);
        var whole = !isPartial(client, chosen);
        var started = false;
        ImGui.beginDisabled(isServerUnknown(getFolder(client)));
        if (!whole && !plan.missing().isEmpty()) {
            if (GeodataWidgets.actionButton(
                    id + "-chosen", Glyph.GEODATA, "Build " + formatRegions(plan.missing()), -1, true)) {
                start(client, near, plan.missing(), false);
                started = true;
            }
            GeodataWidgets.tip("Builds "
                    + formatRegionNames(plan.missing())
                    + " into "
                    + getFolder(client).getFileName()
                    + (plan.built().isEmpty()
                            ? "."
                            : ". The chosen regions that have a file already stay as they are."));
        } else if (!whole && rebuild && !plan.built().isEmpty()) {
            if (GeodataWidgets.actionButton(
                    id + "-rebuild", Glyph.GEODATA, "Rebuild " + formatRegions(plan.built()), -1, true)) {
                askRebuild = plan.built();
            }
            GeodataWidgets.tip("Builds "
                    + formatRegionNames(plan.built())
                    + " again from the client and replaces "
                    + (plan.built().size() == 1 ? "its file" : "their files")
                    + ". It asks first.");
        }
        if (askRebuild != null) {
            ImGui.openPopup(REBUILD_POPUP);
        }
        started |= rebuildConfirm(client, near);
        if (allMissing > 0 && (whole || allMissing > plan.missing().size())) {
            if (!whole) {
                GeodataWidgets.space(GeodataWidgets.ROW);
            }
            var label = allMissing == 1 ? "Build the missing region" : "Build all " + allMissing + " missing regions";
            if (GeodataWidgets.actionButton(id + "-all", Glyph.GEODATA, label, -1, whole)) {
                start(client, near);
                started = true;
            }
            GeodataWidgets.tip("Builds every map of the client that has no region file in "
                    + getFolder(client).getFileName()
                    + " yet, the nearest first. A region takes a few seconds; the whole client takes about ten"
                    + " minutes.");
        }
        ImGui.endDisabled();
        return started;
    }

    boolean isPartial(Path client, List<GeoTile> chosen) {
        var plan = computePlan(client, chosen);
        return !chosen.isEmpty() && plan.missing().size() + plan.built().size() < getBuilt(client) + getMissing(client);
    }

    List<GeoTile> getChosenMissing(Path client, List<GeoTile> chosen) {
        return computePlan(client, chosen).missing();
    }

    record Plan(List<GeoTile> missing, List<GeoTile> built) {}

    void setVariant(WorldVariant world) {
        if (world != variant) {
            counts = null;
            chosenPlan = null;
        }
        variant = world;
    }

    Path getFolder(Path client) {
        return ClientGeodataBuild.outputFolder(client, format, variant);
    }

    void formatSwitch(String id, Path client) {
        formatSwitch(id, client, false);
    }

    void formatSwitch(String id, Path client, boolean guide) {
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Server format");
        if (guide) {
            ImGui.sameLine(0, 4);
            HudWidgets.helpMark("terms/l2off");
        }
        var picked = GeodataWidgets.segmented(id, FORMAT_LABELS, format.ordinal());
        if (picked >= 0 && picked != format.ordinal()) {
            format = GeoFormat.values()[picked];
            counts = null;
        }
        GeodataWidgets.tip(
                "L2OFF writes X_Y_conv.dat for NCsoft's servers. L2J writes X_Y.l2j for L2J Server, the server"
                        + " emulator in Java, and the servers built on it. Each format goes into its own folder in the"
                        + " client: "
                        + ClientGeodataBuild.FOLDER
                        + " or "
                        + ClientGeodataBuild.FOLDER
                        + "_l2j"
                        + (variant.getMapWorld() == WorldVariant.MAIN
                                ? "."
                                : ", with _classic at the end for the Classic maps."));
        startPointChoice(id + "-starts");
        if (format != GeoFormat.L2OFF) {
            return;
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (ImGui.checkbox("With pathnode##" + id, withPathNodes)) {
            withPathNodes = !withPathNodes;
        }
        GeodataWidgets.tip(
                "After the regions, builds the server's pathnode graph, so monsters find paths on an L2OFF server. The"
                        + " graph covers every region in the folder, not only the new ones, and is built again when"
                        + " regions are added. A few regions take seconds; the whole world takes a few minutes.");
        if (withPathNodes) {
            serverChoice(id + "-server", getFolder(client));
        }
    }

    void startPointSources(@Nullable Path scripts) {
        var now = System.nanoTime();
        if (Objects.equals(scripts, scriptFolder) && now - sourcesCheckedAt < 1_000_000_000L) {
            return;
        }
        scriptFolder = scripts;
        sourcesCheckedAt = now;
        npcpos = null;
        try {
            if (scripts != null && Files.isDirectory(scripts)) {
                npcpos = SpawnSource.find(SpawnSource.resolveFolder(scripts), SpawnSource.POSITIONS);
            }
        } catch (RuntimeException unreadable) {
        }
    }

    StartSources getStartSources() {
        return new StartSources(startAtSpawns ? npcpos : null);
    }

    void serverChoice(String id, Path folder) {
        var picked = GeodataWidgets.segmented(id, SERVER_LABELS, server);
        if (picked >= 0) {
            server = picked;
        }
        var follows = followedTable(folder);
        if (server == 0 && follows == null) {
            ImGui.pushTextWrapPos(0);
            ImGui.textColored(GeodataWidgets.WARNING, "The folder has no pathnode.idx to follow, so pick the server.");
            ImGui.popTextWrapPos();
        } else if (server == 0) {
            GeodataWidgets.hint("Server: " + tableName(follows) + ", as in the folder's pathnode.idx.");
        }
        GeodataWidgets.tip(GeodataEditingPanel.SERVER_HELP);
    }

    boolean isServerUnknown(Path folder) {
        return format == GeoFormat.L2OFF && withPathNodes && server == 0 && followedTable(folder) == null;
    }

    boolean pathNodeCallout(String id, @Nullable Path folder, boolean offerNew) {
        if (folder == null || job.getProgress().running()) {
            return false;
        }
        var check = PATH_NODE_CHECKS.getStatus(folder);
        if (check == null) {
            return false;
        }
        var status = check.status();
        if (status.state() == State.STALE) {
            var changed = status.changed().size();
            GeodataWidgets.space(GeodataWidgets.ROW);
            var pressed = GeodataWidgets.callout(
                    id,
                    Glyph.GEODATA,
                    "Pathnode out of date",
                    changed + (changed == 1 ? " region changed" : " regions changed"),
                    "Rebuild");
            GeodataWidgets.tip("The server's pathnode graph in "
                    + folder.getFileName()
                    + " was built from other regions: "
                    + String.join(", ", status.changed().subList(0, Math.min(8, changed)))
                    + (changed > 8 ? " and " + (changed - 8) + " more" : "")
                    + ". Rebuild builds it again for every region in the folder, for the same server.");
            if (pressed) {
                job.rebuildPathNodes(folder, status.table(), false);
                reported = false;
            }
            return pressed;
        }
        if (!check.regions() || status.state() == State.CURRENT) {
            return false;
        }
        if (!offerNew || status.state() == State.UNKNOWN) {
            var replace = status.state() == State.UNKNOWN;
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (GeodataWidgets.actionButton(
                    id + "-quiet",
                    Glyph.GEODATA,
                    replace ? "Rebuild pathnode graph" : "Build pathnode graph",
                    -1,
                    false)) {
                askGraph = folder;
                graphReplaces = replace;
                graphBackup = true;
            }
            GeodataWidgets.tip(
                    replace
                            ? "The folder's pathnode graph was not built by Cruma, so the viewer cannot tell whether"
                                  + " it matches the regions. This builds a new one for every region in the folder. It"
                                  + " asks first."
                            : "The folder has no pathnode graph. This builds one for every region in the folder, so"
                                    + " monsters find paths on an L2OFF server. It asks first.");
            if (askGraph != null) {
                ImGui.openPopup(GRAPH_POPUP);
            }
            return drawGraphConfirm();
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        var table = chosenTable(folder);
        ImGui.beginDisabled(table == null);
        var pressed =
                GeodataWidgets.callout(id, Glyph.GEODATA, "No pathnode graph", "Needed for monster paths", "Build");
        ImGui.endDisabled();
        GeodataWidgets.tip("Builds the server's pathnode graph of every region in " + folder.getFileName()
                + ". A few regions take seconds; the whole world takes a few minutes.");
        if (table == null) {
            serverChoice(id + "-server", folder);
        }
        if (pressed && table != null) {
            job.rebuildPathNodes(folder, table, false);
            reported = false;
        }
        return pressed && table != null;
    }

    int getBuilt(Path client) {
        return countRegions(client)[0];
    }

    int getMissing(Path client) {
        return countRegions(client)[1];
    }

    @Nullable
    Outcome getOutcome(@Nullable Path folder) {
        var progress = job.getProgress();
        var graphOnly = progress.total() == GeodataBuildJob.GRAPH_ONLY;
        if (reported || progress.running() || progress.total() == 0 || !isSameFolder(progress.folder(), folder)) {
            return null;
        }
        reported = true;
        if (!progress.error().isBlank()) {
            return new Outcome(
                    (progress.graph() ? "The pathnode graph could not be built: " : "The build stopped with an error: ")
                            + progress.error(),
                    GeodataWidgets.BLOCKED);
        }
        if (graphOnly) {
            return progress.stopped()
                    ? new Outcome("Stopped; the pathnode graph stays as it was.", GeodataWidgets.WARNING)
                    : new Outcome(
                            "Built the pathnode graph of " + regionCount(progress.graphRegions()) + ".",
                            GeodataWidgets.OPEN,
                            progress.folder());
        }
        if (progress.stopped()) {
            return new Outcome(
                    "Stopped after " + progress.done() + " of " + progress.total() + " regions.",
                    GeodataWidgets.WARNING,
                    progress.written() > 0 ? progress.folder() : null);
        }
        var built = "Built "
                + progress.written()
                + (progress.written() == 1 ? " region" : " regions")
                + " from the client"
                + (progress.graphRegions() > 0
                        ? " and the pathnode graph of " + regionCount(progress.graphRegions())
                        : "");
        if (progress.failed() > 0) {
            return new Outcome(
                    built
                            + "; "
                            + progress.failed()
                            + (progress.failed() == 1 ? " region" : " regions")
                            + " could not be built, the log says why.",
                    GeodataWidgets.WARNING,
                    progress.folder());
        }
        return new Outcome(built + ".", GeodataWidgets.OPEN, progress.folder());
    }

    private Plan computePlan(Path client, List<GeoTile> chosen) {
        var now = System.nanoTime();
        if (chosenPlan == null
                || !chosen.equals(plannedFor)
                || !client.equals(plannedClient)
                || plannedFormat != format
                || now - plannedAt > 1_000_000_000L) {
            var missing = new ArrayList<GeoTile>();
            var built = new ArrayList<GeoTile>();
            try {
                var wanted = Set.copyOf(chosen);
                var folder = getFolder(client);
                for (var tile : ClientGeodataBuild.findRegions(
                        client, variant, chosen.isEmpty() ? ANY_TILE : chosen.getFirst())) {
                    if (wanted.contains(tile)) {
                        (Files.isRegularFile(folder.resolve(format.getFileName(tile))) ? built : missing).add(tile);
                    }
                }
            } catch (IOException unreadable) {
            }
            chosenPlan = new Plan(List.copyOf(missing), List.copyOf(built));
            plannedFor = List.copyOf(chosen);
            plannedClient = client;
            plannedFormat = format;
            plannedAt = now;
        }
        return chosenPlan;
    }

    private boolean rebuildConfirm(Path client, GeoTile near) {
        var io = ImGui.getIO();
        ImGui.setNextWindowPos(io.getDisplaySizeX() / 2, io.getDisplaySizeY() / 2, ImGuiCond.Always, .5f, .5f);
        ImGui.setNextWindowSize(Math.max(280, Math.min(420, io.getDisplaySizeX() - 32)), 0);
        if (!ImGui.beginPopupModal(
                REBUILD_POPUP,
                ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.AlwaysAutoResize)) {
            return false;
        }
        Motion.dialog();
        var tiles = askRebuild;
        askRebuild = null;
        if (tiles != null) {
            confirming = tiles;
        }
        if (confirming == null || confirming.isEmpty()) {
            ImGui.closeCurrentPopup();
            ImGui.endPopup();
            return false;
        }
        ImGui.pushTextWrapPos(0);
        ImGui.textUnformatted("Rebuild " + formatRegionNames(confirming) + "?");
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.textColored(
                GeodataWidgets.MUTED,
                (confirming.size() == 1 ? "Its file in " : "Their files in ")
                        + getFolder(client).getFileName()
                        + " are built again from the client and replaced. A file that cannot be built stays as it is.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.FAINT, "Other regions in the folder stay as they are.");
        ImGui.popTextWrapPos();
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var width = GeodataWidgets.columnWidth(2);
        var started = false;
        if (HudWidgets.button("geodata-rebuild-yes", null, "Rebuild", width, 28, true, true)) {
            start(client, near, confirming, true);
            started = true;
            confirming = null;
            ImGui.closeCurrentPopup();
        }
        ImGui.sameLine();
        if (HudWidgets.button("geodata-rebuild-no", null, "Cancel", width, 28, false, true)
                || ImGui.isKeyPressed(ImGuiKey.Escape)
                || GeodataWidgets.wasClickedOutside()) {
            confirming = null;
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
        return started;
    }

    private void startPointChoice(String id) {
        if (npcpos == null) {
            return;
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Start points");
        if (ImGui.checkbox("NPC spawns##" + id + "-spawns", startAtSpawns)) {
            startAtSpawns = !startAtSpawns;
        }
        GeodataWidgets.tip("Also walks from where " + npcpos
                + " places a fixed NPC that the build gives no floor, such as a closed area under the ground"
                + " that players reach by teleport. Only those places get start points; every other layer"
                + " stays as it is.");
    }

    @Nullable
    private PathNodes pathNodes(Path folder) {
        if (format != GeoFormat.L2OFF || !withPathNodes) {
            return null;
        }
        return new PathNodes(chosenTable(folder));
    }

    private boolean drawGraphConfirm() {
        var io = ImGui.getIO();
        ImGui.setNextWindowPos(io.getDisplaySizeX() / 2, io.getDisplaySizeY() / 2, ImGuiCond.Always, .5f, .5f);
        ImGui.setNextWindowSize(Math.max(280, Math.min(420, io.getDisplaySizeX() - 32)), 0);
        if (!ImGui.beginPopupModal(
                GRAPH_POPUP,
                ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.AlwaysAutoResize)) {
            return false;
        }
        Motion.dialog();
        if (askGraph != null) {
            graphFolder = askGraph;
            askGraph = null;
        }
        if (graphFolder == null) {
            ImGui.closeCurrentPopup();
            ImGui.endPopup();
            return false;
        }
        var folder = graphFolder;
        ImGui.pushTextWrapPos(0);
        ImGui.textUnformatted((graphReplaces ? "Rebuild the pathnode graph of " : "Build a pathnode graph for ")
                + folder.getFileName() + "?");
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.textColored(
                GeodataWidgets.MUTED,
                graphReplaces
                        ? "Replaces pathnode.idx, pathnode.bin and pathnode.txt with a graph of every region in the"
                                + " folder. The region files stay as they are."
                        : "Writes pathnode.idx, pathnode.bin and pathnode.txt for every region in the folder. The"
                                + " region files stay as they are.");
        ImGui.popTextWrapPos();
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Server");
        serverChoice("geodata-graph-server", folder);
        if (graphReplaces) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (ImGui.checkbox("Back up the files it replaces##geodata-graph-backup", graphBackup)) {
                graphBackup = !graphBackup;
            }
            GeodataWidgets.tip(
                    "Copies the old pathnode files into a new folder next to this one, named after it and the time.");
        }
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var table = chosenTable(folder);
        var width = GeodataWidgets.columnWidth(2);
        var started = false;
        ImGui.beginDisabled(table == null);
        if (HudWidgets.button("geodata-graph-yes", null, graphReplaces ? "Rebuild" : "Build", width, 28, true, true)) {
            job.rebuildPathNodes(folder, table, graphReplaces && graphBackup);
            reported = false;
            started = true;
            graphFolder = null;
            ImGui.closeCurrentPopup();
        }
        ImGui.endDisabled();
        ImGui.sameLine();
        if (HudWidgets.button("geodata-graph-no", null, "Cancel", width, 28, false, true)
                || ImGui.isKeyPressed(ImGuiKey.Escape)
                || GeodataWidgets.wasClickedOutside()) {
            graphFolder = null;
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
        return started;
    }

    private int[] countRegions(Path client) {
        var now = System.nanoTime();
        if (counts == null
                || !client.equals(countedClient)
                || countedFormat != format
                || now - countedAt > 1_000_000_000L) {
            counts = new int[2];
            countedClient = client;
            countedFormat = format;
            countedAt = now;
            try {
                var all = ClientGeodataBuild.findRegions(client, variant, ANY_TILE);
                var missing =
                        ClientGeodataBuild.findMissingRegions(client, variant, getFolder(client), format, ANY_TILE);
                counts[0] = all.size() - missing.size();
                counts[1] = missing.size();
            } catch (IOException unreadable) {
            }
        }
        return counts;
    }

    @Nullable
    private static ZoneTable chosenTable(Path folder) {
        return switch (server) {
            case 1 -> ZoneTable.Y10_TO_25;
            case 2 -> ZoneTable.Y10_TO_26;
            default -> followedTable(folder);
        };
    }

    private static @Nullable ZoneTable followedTable(Path folder) {
        var check = PATH_NODE_CHECKS.getStatus(folder);
        return check == null ? null : check.status().table();
    }

    private static String regionCount(int regions) {
        return regions + (regions == 1 ? " region" : " regions");
    }
}
