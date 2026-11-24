package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GeodataWidgets.space;

import com.rost.cruma.core.export.NpcExport;
import com.rost.cruma.core.export.NpcExport.Race;
import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.npc.ClientIcons.Icon;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.unreal.EffectClasses.Effect;
import com.rost.cruma.core.unreal.SkillEffects;
import com.rost.cruma.core.unreal.SkillEffects.Action;
import com.rost.cruma.core.unreal.SkillEffects.Phase;
import com.rost.cruma.core.unreal.SkillEffects.Skill;
import com.rost.cruma.core.world.EffectParts;
import com.rost.cruma.core.world.EmitterSound;
import com.rost.cruma.core.world.NpcAnimation;
import com.rost.cruma.core.world.PlayerFigures;
import com.rost.cruma.core.world.TextureQuality;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.editing.EffectEditController;
import com.rost.cruma.viewer.editing.EffectEditController.BuiltSkill;
import com.rost.cruma.viewer.editing.EffectEditController.Rebuilt;
import com.rost.cruma.viewer.geodata.ClientFloorGrid;
import com.rost.cruma.viewer.npc.NpcIcons;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.ScenePlayback;
import com.rost.cruma.viewer.render.SpawnOverlayState;
import com.rost.cruma.viewer.render.SpawnOverlayState.LivePose;
import com.rost.cruma.viewer.render.SpawnOverlayState.ModelInstance;
import com.rost.cruma.viewer.render.api.EffectPreview;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import com.rost.cruma.viewer.spawn.RaceOffers;
import com.rost.cruma.viewer.spawn.SpawnModelData;
import com.rost.cruma.viewer.spawn.SpawnModels;
import com.rost.cruma.viewer.spawn.SpawnModels.Animated;
import com.rost.cruma.viewer.spawn.SpawnModels.Status;
import com.rost.cruma.viewer.spawn.SpawnStyle;
import com.rost.cruma.viewer.ui.CharacterForm.Look;
import com.rost.cruma.viewer.ui.EffectEditor.Host;
import com.rost.cruma.viewer.ui.GeodataWorkspace.Viewport;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.SkillShow.Cast;
import com.rost.cruma.viewer.ui.SkillShow.Pawn;
import com.rost.cruma.viewer.ui.SkillShow.Sound;
import glm_.vec3.Vec3;
import imgui.ImColor;
import imgui.ImGui;
import imgui.ImGuiListClipper;
import imgui.flag.ImDrawFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiPopupFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class EffectsPanel {
    private static final int EFFECT = 0xFF5CDBF1;
    private static final float TITLE_ICON = 26;
    private static final float[] REPEAT_PAUSES = {0f, .5f, 1f};
    private static final double[] SPEEDS = {.1, .25, .5, 1, 1.5, 2, 3};
    private static final float LONGEST_EFFECT = 20f;
    private static final float MEASURE_STEP = 1f / 30f;
    private static final float PLAY_BUTTON = 28;
    private static final float PLAY_GROUPS_GAP = 8;
    private static final float SPEED_WIDTH = 64;
    private static final String DRAG_TIP = " Drag the field left or right, or double-click it to type.";
    private static final int PLACE_FRAMES = 20;
    private static final float AHEAD = 500f;
    private static final float FRAME_DISTANCE = 210f;
    private static final float HELP_MARK = 18f;
    private static final List<String> EXAMPLES = List.of("at_shock_blast_ta");
    private static final float FRAME_HEIGHT = 40f;
    private static final double FRAME_PITCH = -18;
    private static final float SKILL_DISTANCE = 300f;
    private static final float EDITED_WIDTH = 64;

    private final ImString search = new ImString(64);
    private final ImGuiListClipper clipper = new ImGuiListClipper();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "effects-loader");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Effect> listed = new ArrayList<>();
    private final EffectEditor editor = new EffectEditor(new Host() {
        @Override
        public List<Effect> getEffects() {
            return effects;
        }

        @Override
        public List<Skill> getSkills() {
            return skills;
        }

        @Override
        public String getSkillName(Skill skill) {
            return EffectsPanel.this.skillName(skill);
        }

        @Override
        public long getTexture(String path) {
            return icons == null ? 0 : icons.getTexture(path);
        }

        @Override
        public long getPicture(String key, Icon icon) {
            return icons == null ? 0 : icons.getPicture(key, icon);
        }

        @Override
        public void choose(Effect effect) {
            EffectsPanel.this.choose(effect);
        }

        @Override
        public void reread() {
            rereadWanted = true;
        }

        @Override
        public @Nullable EffectParts getShown() {
            return shown;
        }

        @Override
        public void pause() {
            EffectsPanel.this.pause();
        }
    });
    private final SkillEditor skillEditor = new SkillEditor(new SkillEditor.Host() {
        @Override
        public @Nullable EffectEditController getController() {
            return editor.getController();
        }

        @Override
        public List<Effect> getEffects() {
            return effects;
        }

        @Override
        public void openEffect(String path) {
            effectToOpen = path;
        }
    });
    private @Nullable Path client;
    private TextureQuality quality = TextureQuality.FULL;
    private WorldVariant variant = WorldVariant.MAIN;
    private @Nullable UnrealLoader loader;
    private @Nullable NpcIcons icons;
    private @Nullable CompletableFuture<Listing> listing;

    private record Listing(List<Effect> effects, List<Skill> skills) {}

    private List<Effect> effects = List.of();
    private String listedQuery = "";
    private boolean listDirty = true;
    private String message = "";
    private @Nullable Effect selected;
    private final EffectLoad load = new EffectLoad();
    private @Nullable EffectParts shown;
    private @Nullable Vec3 anchor;
    private int placeFrames = -1;
    private boolean framePending;
    private float spentSeconds;
    private float effectTime;
    private float effectLength;
    private boolean effectEndSeen;
    private float keepTime = -1f;
    private boolean comparing;
    private @Nullable EffectParts editedParts;
    private @Nullable EffectParts originalParts;
    private @Nullable String originalFor;
    private @Nullable CompletableFuture<@Nullable EffectParts> loadingOriginal;
    private boolean rereadWanted;
    private @Nullable String chooseSkillWhenListed;
    private @Nullable String effectToOpen;
    private @Nullable ScenePlayback playback;
    private boolean pausedHere;
    private boolean speedSetHere;
    private float repeatPause = .5f;
    private boolean loop = true;
    private boolean scrollToSelected;
    private float rowsHeight;
    private String rowsId = "";
    private float rowsTop;
    private float rowsScroll;
    private @Nullable Viewport viewport;
    private @Nullable String chooseWhenListed;
    private final EffectCharacter caster = new EffectCharacter("effect-character");
    private final EffectCharacter target = new EffectCharacter("effect-target");
    private boolean targetSelf;
    private boolean targetShown;
    private boolean withoutWorld;
    private boolean skyShown;
    private boolean characterOpen = true;
    private float choiceLeft;
    private float choiceTop;
    private float choiceWidth;
    private float whoTop;
    private float foldTop;
    private float spotTop;
    private float heldLeft;
    private float heldTop;
    private Spot spot = Spot.CHEST;
    private @Nullable SpawnModels models;
    private @Nullable SpawnModels figures;

    private @Nullable CompletableFuture<@Nullable NpcAnimation> standInLoading;
    private @Nullable NpcAnimation standIn;
    private float figureSeconds;
    private @Nullable LivePose targetPose;
    private @Nullable Animated targetPoseOf;
    private Map<String, Vec3> casterBones = Map.of();
    private Map<String, Vec3> targetBones = Map.of();
    private int effectHandle = -1;
    private @Nullable Vec3 effectAt;
    private ClientCatalog catalog = ClientCatalog.empty();
    private @Nullable SpawnOverlayState stage;
    private float characterYaw;
    private @Nullable String placedFor;
    private boolean skillMode;
    private List<Skill> skills = List.of();
    private final List<Skill> listedSkills = new ArrayList<>();
    private @Nullable String listedSkillQuery;
    private @Nullable List<Skill> listedSkillsOf;
    private @Nullable Skill selectedSkill;
    private @Nullable CompletableFuture<SkillShow> loadingSkill;
    private @Nullable SkillShow show;
    private @Nullable String startedFor;
    private float keepSkillTime = -1f;
    private boolean comparingSkill;
    private @Nullable Skill editedSkill;
    private Map<String, EffectParts> editedSkillParts = Map.of();
    private @Nullable CompletableFuture<List<Sound>> loadingSounds;
    private List<Sound> sounds = List.of();
    private String soundsFor = "";
    private boolean onGround;
    private @Nullable CompletableFuture<Void> loadingFloors;
    private final Set<String> floorsAsked = new HashSet<>();
    private float skillApart;
    private @Nullable Vec3 casterStands;
    private @Nullable Vec3 targetStands;
    private float settledApart;
    private boolean distanceHeld;
    private final SkillCasting casting = new SkillCasting();
    private final int[] levelDraft = new int[1];
    private final int[] speedDraft = new int[1];
    private boolean levelHeld;
    private boolean speedHeld;
    private boolean closed = true;
    private @Nullable ExportSettings exportSettings;
    private @Nullable Order order;
    private boolean frameWanted;

    public record Order(
            @Nullable Effect effect,
            @Nullable Skill skill,
            @Nullable Integer level,
            @Nullable Integer speed,
            @Nullable Integer distance,
            @Nullable Who caster,
            @Nullable Who target,
            @Nullable Spot spot,
            @Nullable Vec3 at,
            @Nullable Boolean ground,
            @Nullable Boolean repeat,
            boolean frame) {}

    public record Who(boolean on, int npc, @Nullable String figure, boolean self) {
        public static final Who NOBODY = new Who(false, 0, null);
        public static final Who SELF = new Who(false, 0, null, true);

        public Who(boolean on, int npc, @Nullable String figure) {
            this(on, npc, figure, false);
        }
    }

    private record CharacterNote(String text, int color) {}

    public enum Spot {
        FEET("Feet", 0f),
        CHEST("Chest", .62f, "bip01 spine2", "bip01 spine1", "bip01 spine"),
        HEAD("Head", .92f, "bip01 head"),
        RIGHT_HAND("R hand", .55f, "bip01 r hand", "weapon r bone"),
        LEFT_HAND("L hand", .55f, "bip01 l hand", "weapon l bone", "shield l bone");

        final String label;
        final float share;
        final String[] bones;

        Spot(String label, float share, String... bones) {
            this.label = label;
            this.share = share;
            this.bones = bones;
        }

        Vec3 on(SpawnModelData model) {
            return on(model.bones(), model.height());
        }

        Vec3 on(Map<String, Vec3> places, float height) {
            for (var bone : bones) {
                var place = places.get(bone);
                if (place != null) {
                    return place;
                }
            }
            return new Vec3(0f, 0f, height * share);
        }
    }

    static float playButton(float inner, int commands) {
        var buttons = 4 + commands;
        return Math.min(PLAY_BUTTON, (inner - PLAY_GROUPS_GAP - 4 * (buttons - 2)) / buttons);
    }

    static String phaseName(Phase phase) {
        return SkillCasting.phaseName(phase);
    }

    static String kinds(Effect effect) {
        var parts = new ArrayList<String>();
        if (effect.sprites() > 0) {
            parts.add(effect.sprites() + (effect.sprites() == 1 ? " sprite" : " sprites"));
        }
        if (effect.meshes() > 0) {
            parts.add(effect.meshes() + (effect.meshes() == 1 ? " mesh" : " meshes"));
        }
        if (effect.beams() > 0) {
            parts.add(effect.beams() + (effect.beams() == 1 ? " beam" : " beams"));
        }
        if (effect.undrawn() > 0) {
            parts.add(effect.undrawn() + " not drawn");
        }
        return String.join(" · ", parts);
    }

    public void withoutWorld(boolean none) {
        withoutWorld = none;
    }

    public boolean hidesSky() {
        return withoutWorld && !skyShown;
    }

    public boolean isSkyShown() {
        return skyShown;
    }

    public void showSky(boolean shown) {
        skyShown = shown;
    }

    public void setClient(@Nullable Path root, TextureQuality quality, WorldVariant variant) {
        if (Objects.equals(root, client)) {
            return;
        }
        client = root;
        this.quality = quality;
        this.variant = variant;
        editor.client(root, quality, variant);
        skillEditor.forget();
        loader = null;
        listing = null;
        if (icons != null) {
            icons.close();
        }
        icons = root == null ? null : new NpcIcons(root);
        effects = List.of();
        skills = List.of();
        selectedSkill = null;
        show = null;
        listDirty = true;
        selected = null;
        shown = null;
        message = "";
        if (figures != null) {
            figures.close();
            figures = null;
        }
        standInLoading = null;
        standIn = null;
        caster.forgetClient();
        target.forgetClient();
        loadingFloors = null;
        floorsAsked.clear();
    }

    public void setCharacters(SpawnModels models, ClientCatalog catalog, SpawnOverlayState stage) {
        this.models = models;
        this.catalog = catalog;
        casting.setCatalog(catalog);
        this.stage = stage;
    }

    public void setEditsFolder(@Nullable Path folder) {
        editor.dataRoot(folder);
    }

    public void setDialogs(FontManager fonts, @Nullable ExportSettings settings) {
        exportSettings = settings;
        editor.dialogs(fonts, settings);
    }

    public Path getNewExportFolder() {
        return EffectExportDialog.newFolder(exportSettings, EffectExportDialog.NAME);
    }

    public Path getScriptFolder() {
        return ClassSourceDialog.defaultFolder(exportSettings);
    }

    public void chooseWhenListed(String effect) {
        chooseWhenListed = effect;
    }

    public void play(Order next) {
        order = next;
    }

    public List<Effect> getEffectList() {
        return effects;
    }

    public @Nullable Effect getExample() {
        for (var name : EXAMPLES) {
            for (var effect : effects) {
                if (effect.name().equalsIgnoreCase(name)) {
                    return effect;
                }
            }
        }
        return effects.isEmpty() ? null : effects.getFirst();
    }

    public boolean areSkillsShown() {
        return skillMode;
    }

    public List<Skill> getSkillList() {
        return skills;
    }

    public List<Effect> copyList() {
        var running = editor.getController();
        var result = new ArrayList<Effect>();
        if (running != null) {
            for (var copy : running.getEdits().copies().values()) {
                result.add(editor.findEffectOf(copy));
            }
        }
        return result;
    }

    public void copyRenamed(String before, String after) {
        editor.renamed(before, after);
    }

    public boolean isListing() {
        return client == null || listing != null || effects.isEmpty() && message.isEmpty();
    }

    public String getListProblem() {
        return message;
    }

    public SkillCasting getCasting() {
        return casting;
    }

    public @Nullable EffectEditController getEditController() {
        return editor.getController();
    }

    public void installed(boolean restored) {
        editor.installed(restored);
    }

    public boolean hasUnexportedEdits() {
        var running = editor.getController();
        return running != null && running.hasEdits() && running.isUnexported();
    }

    public Map<String, Object> report(ViewerRenderBackend backend) {
        var preview = backend.getEffectPreview();
        var data = new LinkedHashMap<String, Object>();
        if (preview == null) {
            data.put("ready", true);
            data.put("state", "Effects cannot be played with this renderer.");
            return data;
        }
        if (isListing() || !message.isEmpty()) {
            data.put("ready", !message.isEmpty());
            data.put("state", message.isEmpty() ? "Reading the client's effects…" : message);
            return data;
        }
        data.put("mode", skillMode ? "skill" : "effect");
        if (skillMode) {
            reportSkill(data, preview);
        } else {
            reportEffect(data, preview);
        }
        if (anchor != null) {
            data.put("at", point(anchor));
        }
        data.put("repeat", loop);
        return data;
    }

    public void drawPanel(Camera camera, ViewerRenderBackend backend, ScenePlayback playback) {
        if (closed) {
            closed = false;
            reopen();
        }
        playOn(camera, backend, playback);
        var preview = backend.getEffectPreview();
        if (icons != null) {
            icons.poll(backend.getUiRenderer());
        }
        var footer = editor.isFooterShown();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.beginChild("##effectsBody", 0, footer ? -GeodataWidgets.SAVE_BAR : 0, false);
        ImGui.popStyleVar();
        if (preview == null) {
            ImGui.textColored(GeodataWidgets.FAINT, "Effects cannot be played with this renderer.");
        } else if (client == null) {
            ImGui.textColored(GeodataWidgets.FAINT, "No game client is open.");
        } else if (listing != null) {
            ImGui.textColored(GeodataWidgets.FAINT, "Reading the client's effects…");
        } else if (effects.isEmpty()) {
            ImGui.textColored(
                    GeodataWidgets.FAINT, message.isEmpty() ? "The client has no LineageEffect package." : message);
            space(GeodataWidgets.BLOCK);
            HudWidgets.guideLink("What effects are and where the client keeps them", "effects/what-are-effects");
        } else {
            drawBody(camera, preview);
        }
        ImGui.dummy(0, 8);
        ImGui.endChild();
        if (footer) {
            editor.drawFooter();
        }
        editor.drawDialogs();
    }

    public void playOn(Camera camera, ViewerRenderBackend backend, ScenePlayback playback) {
        if (closed) {
            return;
        }
        this.playback = playback;
        var seconds = (float) playback.getLastDelta();
        var preview = backend.getEffectPreview();
        if (rereadWanted) {
            reread(preview);
        }
        startListing();
        pollListing();
        takeOrder(camera, preview);
        pollEditor(preview);
        dressAsked(caster);
        dressAsked(target);
        updateFigure(caster);
        updateFigure(target);
        if (skillMode) {
            pollSkill(preview);
            place(camera, backend, preview);
            playSkill(camera, preview, seconds);
        } else {
            pollLoading(preview);
            place(camera, backend, preview);
            standCharacter(preview, seconds);
            replayWhenSpent(preview, seconds);
            timeEffect(preview, seconds);
            if (framePending && anchor != null) {
                framePending = false;
                frame(camera);
            }
        }
        if (frameWanted && anchor != null && (skillMode ? startedFor != null : placedFor != null)) {
            frameWanted = false;
            frame(camera, skillMode && casterStands != null ? casterStands : anchor);
        }
    }

    public void dispose() {
        editor.client(null, quality, variant);
    }

    public void close(ViewerRenderBackend backend) {
        editor.flush();
        var preview = backend.getEffectPreview();
        if (preview != null && preview.isShown()) {
            preview.clear();
        }
        if (preview != null && startedFor != null) {
            preview.stopSounds();
        }
        compareSkill(false);
        keepSkillTime = -1f;
        if (playback != null && pausedHere && playback.isPaused()) {
            playback.setPaused(false);
        }
        if (playback != null && speedSetHere) {
            playback.setSpeed(1d);
        }
        closed = true;
        pausedHere = false;
        speedSetHere = false;
        comparing = false;
        editedParts = null;
        shown = null;
        anchor = null;
        placeFrames = -1;
        placedFor = null;
        startedFor = null;
        if (stage != null) {
            stage.staged = List.of();
        }
    }

    public boolean handleEscape(ViewerRenderBackend backend) {
        if (ImGui.isPopupOpen("", ImGuiPopupFlags.AnyPopupId | ImGuiPopupFlags.AnyPopupLevel)) {
            return true;
        }
        if (skillMode ? selectedSkill == null : selected == null) {
            return false;
        }
        if (skillMode) {
            selectedSkill = null;
            show = null;
        } else {
            selected = null;
        }
        close(backend);
        return true;
    }

    public void drawViewport(Viewport viewport, boolean open, boolean helpOpen) {
        this.viewport = viewport;
        if (!open || helpOpen || (skillMode ? selectedSkill == null : selected == null)) {
            return;
        }
        var items = new ArrayList<String[]>();
        if (skillMode ? !skillEditor.isOpen() : !editor.isOpen()) {
            items.add(new String[] {"Up Down", skillMode ? "next skill" : "next effect"});
        }
        items.add(new String[] {"R", "replay"});
        items.add(new String[] {"P", playback != null && playback.isPaused() ? "play" : "pause"});
        items.add(new String[] {", .", "step"});
        items.add(new String[] {"F", "frame"});
        if (editor.isFooterShown()) {
            items.add(new String[] {EffectEditor.getUndoKey(), "undo"});
        }
        items.add(new String[] {"Esc", "deselect"});
        var draw = ImGui.getBackgroundDrawList();
        draw.pushClipRect(viewport.left(), viewport.top(), viewport.right(), viewport.bottom(), false);
        GeodataWidgets.hintStrip(draw, viewport, "EFFECTS", items, new ArrayList<>());
        draw.popClipRect();
    }

    public @Nullable String findMissingRace(Race race) {
        var root = client;
        return root == null ? null : RaceOffers.findMissing(race, root, variant);
    }

    private void reopen() {
        if (skillMode) {
            if (show != null && anchor == null) {
                placeFrames = 0;
            }
        } else if (selected != null) {
            choose(selected);
        }
    }

    private void reread(@Nullable EffectPreview preview) {
        rereadWanted = false;
        chooseWhenListed = selected == null ? null : selected.name();
        chooseSkillWhenListed = selectedSkill == null ? null : selectedSkill.className();
        selected = null;
        selectedSkill = null;
        show = null;
        startedFor = null;
        shown = null;
        placedFor = null;
        originalFor = null;
        originalParts = null;
        loader = null;
        listing = null;
        effects = List.of();
        skills = List.of();
        listDirty = true;
        message = "";
        if (preview != null) {
            preview.clear();
        }
    }

    private void pollEditor(@Nullable EffectPreview preview) {
        var rebuilt = editor.poll();
        skillEditor.poll();
        followSkill();
        if (comparing && !ImGui.isMouseDown(0)) {
            compare(false, preview);
        }
        if (comparingSkill && !ImGui.isMouseDown(0)) {
            compareSkill(false);
        }
        var picked = selected;
        if (picked != null && editor.isLost(picked, effects)) {
            var renamed = editor.findRenamed(picked);
            if (renamed != null) {
                choose(renamed);
                return;
            }
            selected = null;
            editor.watch(null);
            shown = null;
            placedFor = null;
            if (preview != null && !skillMode) {
                preview.clear();
            }
            return;
        }
        pollOriginal(picked);
        if (rebuilt != null
                && picked != null
                && rebuilt.classPath().equalsIgnoreCase(picked.path())
                && !load.overtake(picked, rebuilt.parts())) {
            if (skillMode) {
                shown = rebuilt.parts();
                effectLength = 0f;
            } else {
                useRebuilt(rebuilt, preview);
            }
        }
    }

    private void followSkill() {
        var running = editor.getController();
        if (running == null) {
            return;
        }
        var current = show;
        running.watchSkill(skillMode && current != null && loadingSkill == null ? current.getSkill() : null);
        var built = running.takeRebuiltSkill();
        if (built != null
                && current != null
                && built.className().equals(current.getSkill().className())) {
            useBuiltSkill(built, current);
        }
    }

    private void useBuiltSkill(BuiltSkill built, SkillShow current) {
        var skill = built.skill() == null ? current.getSkill() : built.skill();
        var parts = built.skill() == null ? current.getClientParts() : built.parts();
        if (comparingSkill) {
            editedSkill = skill;
            editedSkillParts = parts;
        } else if (!current.plays(skill, parts)) {
            playAtSameMoment(current, skill, parts);
        }
    }

    private void playAtSameMoment(SkillShow current, Skill skill, Map<String, EffectParts> parts) {
        current.play(skill, parts);
        keepSkillTime = current.getTime();
        startedFor = null;
    }

    private void compareSkill(boolean held) {
        if (held == comparingSkill) {
            return;
        }
        comparingSkill = held;
        var current = show;
        if (current == null) {
            return;
        }
        if (held) {
            editedSkill = current.getPlayed();
            editedSkillParts = current.getParts();
            playAtSameMoment(current, current.getSkill(), current.getClientParts());
        } else if (editedSkill != null) {
            playAtSameMoment(current, editedSkill, editedSkillParts);
            editedSkill = null;
            editedSkillParts = Map.of();
        }
    }

    private void useRebuilt(Rebuilt rebuilt, @Nullable EffectPreview preview) {
        if (comparing) {
            editedParts = rebuilt.parts();
            return;
        }
        show(rebuilt.parts(), preview);
        if (rebuilt.settled()) {
            effectLength = 0f;
        }
    }

    private void show(@Nullable EffectParts parts, @Nullable EffectPreview preview) {
        keepTime = effectTime;
        shown = parts;
        placedFor = null;
        if (preview != null && (parts == null || parts.isEmpty())) {
            preview.clear();
        }
    }

    private void pollOriginal(@Nullable Effect picked) {
        var wanted = picked != null && editor.isEdited(picked.path()) ? editor.getSourceOf(picked.path()) : null;
        if (wanted != null && !wanted.equals(originalFor) && loadingOriginal == null && loader != null) {
            var source = byKey(wanted);
            var opened = loader;
            originalFor = wanted;
            originalParts = null;
            if (source != null) {
                loadingOriginal = CompletableFuture.supplyAsync(() -> opened.loadEffect(source), worker);
            }
        }
        var pending = loadingOriginal;
        if (pending != null && pending.isDone()) {
            loadingOriginal = null;
            try {
                originalParts = pending.join();
            } catch (RuntimeException failure) {
                LogManager.getLogger(EffectsPanel.class)
                        .warn("The original of {} could not be read", originalFor, failure);
            }
        }
    }

    private void compare(boolean held, @Nullable EffectPreview preview) {
        if (held == comparing || held && originalParts == null) {
            return;
        }
        comparing = held;
        if (held) {
            editedParts = shown;
            show(originalParts, preview);
        } else {
            show(editedParts, preview);
            editedParts = null;
        }
    }

    private void startListing() {
        var root = client;
        if (root == null || listing != null || !effects.isEmpty() || !message.isEmpty()) {
            return;
        }
        var quality = this.quality;
        var variant = this.variant;
        listing = CompletableFuture.supplyAsync(
                () -> {
                    var opened = new UnrealLoader(root, new Options(false, 0), true, quality);
                    opened.worldVariant(variant);
                    loader = opened;
                    return new Listing(opened.listEffects(), opened.listSkills());
                },
                worker);
    }

    private void pollListing() {
        var pending = listing;
        if (pending == null || !pending.isDone()) {
            return;
        }
        listing = null;
        try {
            var listed = pending.join();
            effects = listed.effects();
            skills = listed.skills();
            listDirty = true;
            if (effects.isEmpty()) {
                message = "The client has no LineageEffect package.";
            }
            var wanted = chooseWhenListed;
            chooseWhenListed = null;
            if (wanted != null) {
                effects.stream()
                        .filter(effect -> effect.path().equalsIgnoreCase(wanted)
                                || effect.name().equalsIgnoreCase(wanted))
                        .findFirst()
                        .ifPresent(this::choose);
            }
            var wantedSkill = chooseSkillWhenListed;
            chooseSkillWhenListed = null;
            if (wantedSkill != null) {
                skills.stream()
                        .filter(skill -> skill.className().equals(wantedSkill))
                        .findFirst()
                        .ifPresent(this::chooseSkill);
            }
        } catch (RuntimeException failure) {
            message = "The client's effects could not be read.";
            LogManager.getLogger(EffectsPanel.class).warn("Effects unavailable", failure);
        }
    }

    private void choose(Effect effect) {
        if (effect.equals(selected) && (shown != null || load.isRunning())) {
            return;
        }
        selected = effect;
        scrollToSelected = true;
        comparing = false;
        editedParts = null;
        var opened = loader;
        if (opened == null) {
            return;
        }
        editor.watch(effect.path());
        load.start(
                effect,
                editor.isEdited(effect.path())
                        ? editor.build(effect.path())
                        : CompletableFuture.supplyAsync(() -> opened.loadEffect(effect), worker));
    }

    private void pollLoading(@Nullable EffectPreview preview) {
        if (!load.isDone()) {
            return;
        }
        EffectParts parts;
        try {
            parts = load.take();
        } catch (RuntimeException failure) {
            LogManager.getLogger(EffectsPanel.class).warn("Effect {} could not be read", load.getEffect(), failure);
            parts = null;
        }
        if (!Objects.equals(load.getEffect(), selected)) {
            if (selected != null) {
                choose(selected);
            }
            return;
        }
        shown = parts;
        keepTime = -1f;
        effectLength = 0f;
        restartEffectClock();
        if (preview == null) {
            return;
        }
        preview.clear();
        placedFor = null;
        if (parts != null && !parts.isEmpty() && anchor == null) {
            placeFrames = 0;
        }
    }

    private void place(Camera camera, ViewerRenderBackend backend, @Nullable EffectPreview preview) {
        if (placeFrames < 0) {
            return;
        }
        var origin = camera.getCursorRayOrigin(0, 0);
        var direction = camera.getCursorRayDirection(0, 0);
        var hit = backend.getSceneRayDistance(0, 0, 20_000);
        Vec3 point = null;
        if (Float.isFinite(hit) && hit > 0) {
            point = new Vec3(
                    origin.getX() + direction.getX() * hit,
                    origin.getY() + direction.getY() * hit,
                    origin.getZ() + direction.getZ() * hit);
        } else if (++placeFrames >= PLACE_FRAMES) {
            point = new Vec3(
                    origin.getX() + direction.getX() * AHEAD,
                    origin.getY() + direction.getY() * AHEAD,
                    origin.getZ() + direction.getZ() * AHEAD);
        }
        if (point == null) {
            return;
        }
        placeFrames = -1;
        framePending = !(Float.isFinite(hit) && hit > 0);
        anchor = point;
        characterYaw = (float) Math.atan2(origin.getY() - point.getY(), origin.getX() - point.getX());
        placedFor = null;
    }

    private void standCharacter(@Nullable EffectPreview preview, float seconds) {
        var at = anchor;
        var parts = shown;
        var model = characterModel(caster);
        var bones = model == null ? Map.<String, Vec3>of() : model.bones();
        var figure = caster.figure;
        var moving = figure != null && model == figure.model() ? caster.pose : null;
        if (moving != null && at != null) {
            figureSeconds += seconds;
            var animation = figure.animation();
            var moment = SkillShow.waiting(animation, figureSeconds);
            var pose = animation.getPose(moment.sequence(), moment.seconds());
            moving.set(figure.vertices(pose));
            bones = figure.bones(pose);
        }
        if (stage != null) {
            stage.staged = model == null || at == null
                    ? List.of()
                    : List.of(new ModelInstance(model, at.getX(), at.getY(), at.getZ(), characterYaw, 1f, moving));
        }
        if (preview == null || at == null || parts == null || parts.isEmpty()) {
            return;
        }
        if (model == null && isReadingCharacter(caster)) {
            return;
        }
        var key = parts.effect().path()
                + "|"
                + at
                + "|"
                + (model == null ? "ground" : model.key() + "|" + spot + "|" + characterYaw);
        var position = at;
        var yaw = 0f;
        if (model != null) {
            var local = spot.on(bones, model.height());
            var cos = (float) Math.cos(characterYaw);
            var sin = (float) Math.sin(characterYaw);
            position = new Vec3(
                    at.getX() + local.getX() * cos - local.getY() * sin,
                    at.getY() + local.getX() * sin + local.getY() * cos,
                    at.getZ() + local.getZ());
            yaw = characterYaw;
        }
        if (!key.equals(placedFor)) {
            placedFor = key;
            preview.clear();
            effectHandle = preview.add(parts, position, yaw);
            effectAt = position;
            if (effectLength == 0f) {
                effectLength = measureLength(preview);
            }
            restartEffectClock();
            if (keepTime >= 0f) {
                seekEffect(preview, Math.min(keepTime, LONGEST_EFFECT));
                keepTime = -1f;
            }
        } else if (!position.equals(effectAt)) {
            preview.move(effectHandle, position);
            effectAt = position;
        }
    }

    private @Nullable SpawnModelData characterModel(EffectCharacter who) {
        if (!who.onCharacter) {
            return null;
        }
        if (who.figure != null && who.figureFor.equals(wantedFigure(who))) {
            return who.figure.model();
        }
        if (who.playerCharacter || who.characterId <= 0 || models == null || isReadingCharacter(who)) {
            return null;
        }
        return models.getModel(who.characterId);
    }

    private boolean isReadingCharacter(EffectCharacter who) {
        if (!who.onCharacter) {
            return false;
        }
        if (who.figureLoading != null || who.wantedRace != null) {
            return true;
        }
        return !who.playerCharacter
                && who.characterId > 0
                && models != null
                && models.getStatus(who.characterId) == Status.LOADING;
    }

    private String wantedFigure(EffectCharacter who) {
        if (!who.onCharacter) {
            return "";
        }
        if (who.playerCharacter) {
            return who.characterForm.getKey();
        }
        if (who.characterId <= 0 || models == null || models.getModel(who.characterId) == null) {
            return "";
        }
        return "npc:" + who.characterId;
    }

    private void updateFigure(EffectCharacter who) {
        var wanted = wantedFigure(who);
        var pending = who.figureLoading;
        if (pending != null) {
            if (!pending.isDone()) {
                return;
            }
            who.figureLoading = null;
            Animated loaded = null;
            try {
                loaded = pending.join();
            } catch (RuntimeException failure) {
                LogManager.getLogger(EffectsPanel.class)
                        .warn("Character {} could not be read", who.figureLoadingFor, failure);
            }
            who.figure = loaded;
            who.figureFor = who.figureLoadingFor;
            figureSeconds = 0;
            who.pose = loaded == null ? null : new LivePose(loaded.model().vertices());
            placedFor = null;
            startedFor = null;
        }
        if (wanted.equals(who.figureFor) || wanted.isEmpty() || client == null) {
            return;
        }
        var source = figures();
        who.figureLoadingFor = wanted;
        if (who.playerCharacter) {
            var character = who.characterForm.getCharacter();
            if (character == null) {
                return;
            }
            who.figureLoading = CompletableFuture.supplyAsync(
                    () -> source.buildAnimatedCharacter(character.face(), character.outfit()), worker);
        } else {
            var id = who.characterId;
            var outfit = models == null ? null : models.getOutfit(id);
            who.figureLoading = CompletableFuture.supplyAsync(() -> source.readAnimated(id, outfit), worker);
        }
    }

    private @Nullable NpcAnimation standIn() {
        var pending = standInLoading;
        if (pending == null) {
            if (client == null) {
                return null;
            }
            var source = figures();
            var face = NpcExport.RACES.getFirst().face();
            standInLoading = CompletableFuture.supplyAsync(
                    () -> {
                        var built = source.buildAnimatedCharacter(face, UnrealLoader.Outfit.BARE);
                        return built == null ? null : built.animation();
                    },
                    worker);
            return null;
        }
        if (standIn == null && pending.isDone()) {
            try {
                standIn = pending.join();
            } catch (RuntimeException failure) {
                LogManager.getLogger(EffectsPanel.class).warn("The stand-in figure could not be read", failure);
            }
        }
        return standIn;
    }

    private boolean isReadingStandIn() {
        var pending = standInLoading;
        return pending != null && !pending.isDone();
    }

    private SpawnModels figures() {
        if (figures == null) {
            figures = new SpawnModels(client, variant);
        }
        return figures;
    }

    private void replayWhenSpent(@Nullable EffectPreview preview, float seconds) {
        if (preview == null || !preview.isShown() || !loop || !preview.isFinished()) {
            spentSeconds = 0;
            return;
        }
        spentSeconds += seconds;
        if (spentSeconds >= repeatPause) {
            preview.replay();
            restartEffectClock();
        }
    }

    private void timeEffect(@Nullable EffectPreview preview, float seconds) {
        if (preview == null || !preview.isShown()) {
            return;
        }
        effectTime += seconds;
        if (!effectEndSeen && preview.isFinished()) {
            effectEndSeen = true;
            effectLength = Math.max(effectLength, effectTime);
        }
    }

    private void restartEffectClock() {
        effectTime = 0f;
        effectEndSeen = false;
        spentSeconds = 0;
    }

    private void seekEffect(EffectPreview preview, float seconds) {
        preview.replay();
        restartEffectClock();
        var target = Math.max(0f, seconds);
        while (effectTime < target - 1e-5f) {
            var step = Math.min(SkillShow.SEEK_STEP, target - effectTime);
            preview.advance(step);
            effectTime += step;
        }
    }

    private void seekSkill(EffectPreview preview, float seconds) {
        if (show != null && startedFor != null) {
            show.seek(preview, seconds);
        }
    }

    private void pause() {
        if (playback != null && !playback.isPaused()) {
            playback.setPaused(true);
            pausedHere = true;
        }
    }

    private void togglePause() {
        if (playback == null) {
            return;
        }
        if (playback.isPaused()) {
            playback.setPaused(false);
            pausedHere = false;
        } else {
            pause();
        }
    }

    private boolean pauseKeyPressed() {
        return ImGui.isKeyPressed(ImGuiKey.P, false)
                && playback != null
                && playback.claimPauseKey(ImGui.getFrameCount());
    }

    private void stepForward() {
        if (playback != null) {
            playback.step();
            pausedHere = true;
        }
    }

    private void stepBack(float time, DoubleConsumer seek) {
        pause();
        seek.accept(Math.max(0f, time - SkillShow.SEEK_STEP));
    }

    private record Command(
            String id,
            Glyph glyph,
            String tip,
            @Nullable String key,
            boolean on,
            Runnable action,
            @Nullable Consumer<Boolean> held) {
        Command(String id, Glyph glyph, String tip, @Nullable String key, Runnable action) {
            this(id, glyph, tip, key, false, action, null);
        }

        Command(String id, Glyph glyph, String tip, @Nullable String key, boolean on, Runnable action) {
            this(id, glyph, tip, key, on, action, null);
        }
    }

    private void drawPlayback(
            String id,
            float inner,
            float time,
            float length,
            float[] marks,
            String what,
            DoubleConsumer seek,
            List<Command> commands) {
        var current = playback;
        if (current == null) {
            return;
        }
        var left = ImGui.getCursorScreenPosX();
        var top = ImGui.getCursorScreenPosY();
        var paused = current.isPaused();
        var button = playButton(inner, commands.size());
        if (HudWidgets.button(id + "/back", Glyph.STEP_BACK, "", button, 26, false, true)) {
            stepBack(time, seek);
        }
        GeodataWidgets.tip("One frame back: the moment a 60th of a second earlier, paused.", ",");
        ImGui.sameLine(0, 4);
        if (HudWidgets.button(id + "/play", paused ? Glyph.PLAY : Glyph.PAUSE, "", button, 26, paused, true)) {
            togglePause();
        }
        GeodataWidgets.tip(
                paused ? "Play on from this moment." : "Pause on this moment. The map's effects pause too.", "P");
        ImGui.sameLine(0, 4);
        if (HudWidgets.button(id + "/step", Glyph.STEP, "", button, 26, false, true)) {
            stepForward();
        }
        GeodataWidgets.tip("One frame on: plays a 60th of a second, then pauses.", ".");
        ImGui.sameLine(0, 4);
        if (HudWidgets.button(id + "/repeat", Glyph.REPEAT, "", button, 26, loop, true)) {
            loop = !loop;
        }
        GeodataWidgets.tip(
                loop
                        ? "Repeat is on: the "
                                + what
                                + " starts again "
                                + (repeatPause > 0f ? pauseLabel(repeatPause) + " after it ends" : "as soon as it ends")
                                + ". Click to play it once. The menu of the speed sets the pause."
                        : "Repeat is off: the " + what + " plays once. Click to play it again and again.");
        var commandsLeft = left + inner - commands.size() * (button + 4) + 4;
        for (var i = 0; i < commands.size(); i++) {
            var command = commands.get(i);
            ImGui.sameLine();
            ImGui.setCursorScreenPos(commandsLeft + i * (button + 4), top);
            if (HudWidgets.button(command.id(), command.glyph(), "", button, 26, command.on(), true)) {
                command.action().run();
            }
            if (command.held() != null) {
                heldLeft = ImGui.getItemRectMinX();
                heldTop = ImGui.getItemRectMinY();
                command.held().accept(ImGui.isItemActive());
            }
            if (command.key() == null) {
                GeodataWidgets.tip(command.tip());
            } else {
                GeodataWidgets.tip(command.tip(), command.key());
            }
        }
        space(GeodataWidgets.ROW);
        var timeTop = ImGui.getCursorScreenPosY();
        var picked = HudWidgets.timeline(id + "/time", time, length, marks, inner - SPEED_WIDTH - 8);
        GeodataWidgets.tip(
                marks.length > 2
                        ? "The moment shown. Press or drag on it to see another. The marks are the shots and the hits."
                        : marks.length > 0
                                ? "The moment shown. Press or drag on it to see another. The marks are the shot and"
                                        + " the hit."
                                : "The moment shown. Press or drag on it to see another.");
        ImGui.sameLine();
        ImGui.setCursorScreenPos(left + inner - SPEED_WIDTH, timeTop + (HudWidgets.CONTROL_HEIGHT - 26) / 2);
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        if (HudWidgets.button(
                id + "/speed",
                null,
                speedLabel(current.getSpeed()) + "     ",
                SPEED_WIDTH,
                26,
                current.getSpeed() != 1d,
                true)) {
            ImGui.openPopup("##" + id + "/speedMenu");
        }
        HudWidgets.glyph(
                ImGui.getWindowDrawList(), Glyph.CHEVRON, x + SPEED_WIDTH - 22, y + 5, 16, GeodataWidgets.MUTED);
        GeodataWidgets.tip(
                "How fast the effects play, from 0.1x to watch them slowly to 3x, and how long a repeat waits after"
                        + " the "
                        + what + " ends. The map's effects play as fast.");
        if (ImGui.beginPopup("##" + id + "/speedMenu")) {
            ImGui.textColored(GeodataWidgets.MUTED, "Speed");
            for (var speed : SPEEDS) {
                if (ImGui.selectable(speedLabel(speed), speed == current.getSpeed())) {
                    current.setSpeed(speed);
                    speedSetHere = speed != 1d;
                }
            }
            ImGui.separator();
            ImGui.textColored(GeodataWidgets.MUTED, "Pause before a repeat");
            for (var pause : REPEAT_PAUSES) {
                if (ImGui.selectable(pause > 0f ? pauseLabel(pause) : "None", pause == repeatPause)) {
                    repeatPause = pause;
                }
            }
            ImGui.endPopup();
        }
        ImGui.setCursorScreenPos(left, timeTop + HudWidgets.CONTROL_HEIGHT);
        ImGui.dummy(0, 0);
        if (!Float.isNaN(picked)) {
            pause();
            seek.accept(picked);
        }
    }

    private List<Command> commands(Command replay, Command here, Command frame) {
        return withoutWorld ? List.of(replay, frame) : List.of(replay, here, frame);
    }

    private List<Command> commands(Command replay, Command here, Command ground, Command frame) {
        return withoutWorld ? List.of(replay, frame) : List.of(replay, here, ground, frame);
    }

    private void drawBody(Camera camera, EffectPreview preview) {
        var opening = effectToOpen;
        if (opening != null) {
            effectToOpen = null;
            switchMode(false, preview);
            editor.openEffect(opening);
        }
        var mode = GeodataWidgets.segmented("effect-mode", new String[] {"Effects", "Skills"}, skillMode ? 1 : 0);
        if (mode >= 0 && mode == 1 != skillMode) {
            switchMode(mode == 1, preview);
        }
        GeodataWidgets.tip(
                "Effects plays one effect class. Skills plays the effects of a skill's cast between two characters, as"
                        + " the client's skill package orders them.");
        space(GeodataWidgets.BLOCK);
        editor.handleKeys();
        if (skillMode) {
            drawSkills(camera, preview);
            return;
        }
        handleKeys(camera, preview);
        editor.drawNotice();
        var picked = selected;
        if (picked != null) {
            drawCard(picked, camera, preview);
            space(GeodataWidgets.BLOCK);
            if (editor.isOpen()) {
                editor.draw(picked);
                return;
            }
        }
        GeodataWidgets.searchField(
                "##effectSearch", "Search effects by name", search, ImGui.getContentRegionAvailX(), false);
        GeodataWidgets.tip(
                "Part of a class name, such as shock_blast or _ta. A package name, such as LineageEffect2, lists its"
                        + " effects.");
        var query = search.get().trim().toLowerCase(Locale.ROOT);
        filter(query);
        editor.drawCopies(query, picked);
        GeodataWidgets.section(
                listed.size() == effects.size()
                        ? "EFFECTS · " + count(effects.size())
                        : "EFFECTS · " + count(listed.size()) + " OF " + count(effects.size()));
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/emitter");
        space(GeodataWidgets.ROW);
        if (listed.isEmpty()) {
            ImGui.textColored(GeodataWidgets.FAINT, "No effect matches.");
            return;
        }
        var rowHeight = SpawnPanelWidgets.listRowHeight() + 2;
        var step = beginRows("##effectRows", picked == null ? -1 : listed.indexOf(picked), rowHeight);
        clipper.begin(listed.size(), step);
        while (clipper.step()) {
            for (var i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
                row(listed.get(i), query, rowHeight);
            }
        }
        clipper.end();
        ImGui.endChild();
    }

    private void switchMode(boolean skills, EffectPreview preview) {
        skillMode = skills;
        preview.clear();
        preview.stopSounds();
        placedFor = null;
        startedFor = null;
        if (stage != null) {
            stage.staged = List.of();
        }
    }

    private float beginRows(String id, int selectedRow, float rowHeight) {
        var step = rowHeight - 2 + ImGui.getStyle().getItemSpacingY();
        var height = Math.max(3 * step, (float)
                Math.floor(ImGui.getContentRegionAvailY() - ImGui.getStyle().getItemSpacingY() - 8));
        var top = ImGui.getCursorScreenPosY();
        var known = id.equals(rowsId);
        var scroll = known ? Math.max(0f, rowsScroll + top - rowsTop) : 0f;
        var follow = (scrollToSelected || height != rowsHeight) && selectedRow >= 0;
        if (known && follow) {
            scroll = inView(scroll, selectedRow * step, step, height);
        }
        if (known && scroll != rowsScroll) {
            ImGui.setNextWindowScroll(-1f, scroll);
        }
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.beginChild(id, 0, height, false);
        ImGui.popStyleVar();
        if (!known && follow) {
            ImGui.setScrollY(inView(ImGui.getScrollY(), selectedRow * step, step, height));
        }
        rowsId = id;
        rowsTop = top;
        rowsHeight = height;
        rowsScroll = ImGui.getScrollY();
        scrollToSelected = false;
        return step;
    }

    private void handleKeys(Camera camera, EffectPreview preview) {
        if (listed.isEmpty()) {
            return;
        }
        var step = 0;
        if (ImGui.isKeyPressed(ImGuiKey.DownArrow, true)) {
            step = 1;
        } else if (ImGui.isKeyPressed(ImGuiKey.UpArrow, true)) {
            step = -1;
        }
        if (step != 0 && !(editor.isOpen() && selected != null)) {
            var index = selected == null ? -1 : listed.indexOf(selected);
            var next = index < 0 ? (step > 0 ? 0 : listed.size() - 1) : Math.clamp(index + step, 0, listed.size() - 1);
            choose(listed.get(next));
        }
        if (ImGui.getIO().getWantTextInput()) {
            return;
        }
        if (ImGui.isKeyPressed(ImGuiKey.R, false)) {
            preview.replay();
            restartEffectClock();
        }
        if (pauseKeyPressed()) {
            togglePause();
        }
        if (ImGui.isKeyPressed(ImGuiKey.Comma, true)) {
            stepBack(effectTime, seconds -> seekEffect(preview, (float) seconds));
        }
        if (ImGui.isKeyPressed(ImGuiKey.Period, true)) {
            stepForward();
        }
        if (ImGui.isKeyPressed(ImGuiKey.F, false)) {
            frame(camera);
        }
    }

    private void row(Effect effect, String query, float rowHeight) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        if (ImGui.invisibleButton("effect/" + effect.path(), width, rowHeight - 2)) {
            choose(effect);
        }
        var draw = ImGui.getWindowDrawList();
        if (ImGui.isItemHovered() || effect.equals(selected)) {
            draw.addRectFilled(
                    x,
                    y,
                    x + width,
                    y + rowHeight - 2,
                    effect.equals(selected) ? ImColor.rgb(30, 58, 70) : GeodataWidgets.HOVER,
                    6f);
        }
        SpawnPanelWidgets.listRow(
                draw,
                x,
                y,
                width,
                effect.undrawn() == effect.emitters() ? 0xFF5C6E7E : EFFECT,
                effect.name(),
                editor.isEdited(effect.path())
                        ? HudWidgets.truncateToWidth(kinds(effect), width - EDITED_WIDTH)
                        : kinds(effect),
                packageLabel(effect),
                query);
        if (editor.isEdited(effect.path())) {
            draw.addText(
                    x + width - ImGui.calcTextSizeX("edited") - 6,
                    y + ImGui.getFontSize() + 6,
                    GeodataWidgets.WARNING,
                    "edited");
        }
    }

    private void drawCard(Effect effect, Camera camera, EffectPreview preview) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        draw.channelsSplit(2);
        draw.channelsSetCurrent(1);
        ImGui.setCursorScreenPos(x + 14, y + 10);
        ImGui.beginGroup();
        var inner = width - 24;
        ImGui.alignTextToFramePadding();
        ImGui.textUnformatted(HudWidgets.truncateToWidth(effect.name(), inner - 90));
        ImGui.sameLine(inner - 86);
        ImGui.beginDisabled(!editor.isReady());
        if (HudWidgets.button("effect-source", Glyph.FILE_CODE, "", 26, 26, false, false)) {
            editor.openSource(effect.path());
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                "Script: the class as a .uc file, rebuilt from the client's compiled class. Copy or save it.");
        ImGui.sameLine(inner - 56);
        ImGui.beginDisabled(!editor.isReady());
        if (HudWidgets.button("effect-edit", Glyph.PENCIL, "", 26, 26, editor.isOpen(), false)) {
            editor.toggle();
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                editor.isOpen()
                        ? "Back to the list of effects. The edits stay."
                        : "Edit this effect, with a live preview. The client does not change until you export and"
                                + " install.");
        ImGui.sameLine(inner - 26);
        if (HudWidgets.button("effect-copy", Glyph.COPY, "", 26, 26, false, false)) {
            ImGui.setClipboardText(effect.path());
        }
        GeodataWidgets.tip("Copy " + effect.path());
        ImGui.textColored(
                GeodataWidgets.MUTED,
                HudWidgets.truncateToWidth(effect.packageName() + " · extends " + effect.superName(), inner));
        ImGui.textColored(GeodataWidgets.MUTED, HudWidgets.truncateToWidth(state(effect, preview), inner - HELP_MARK));
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/particle");
        for (var note : notes(effect)) {
            ImGui.pushTextWrapPos(ImGui.getCursorPosX() + inner);
            ImGui.textColored(GeodataWidgets.WARNING, note);
            ImGui.popTextWrapPos();
        }
        space(GeodataWidgets.ROW);
        var commands = new ArrayList<>(commands(
                new Command("effect-replay", Glyph.RELOAD, "Replay: play the effect again from its start.", "R", () -> {
                    preview.replay();
                    restartEffectClock();
                }),
                new Command(
                        "effect-place",
                        Glyph.PLACE,
                        "Here: move the effect to where the middle of the view meets the ground.",
                        null,
                        () -> placeFrames = 0),
                new Command(
                        "effect-frame",
                        Glyph.FOCUS,
                        "Frame: look at the effect from close by.",
                        "F",
                        () -> frame(camera))));
        if (editor.isEdited(effect.path())) {
            commands.add(
                    commands.size() - 1,
                    new Command(
                            "effect-compare",
                            Glyph.EYE,
                            "Compare: hold to see the client's effect at the same moment. Let go for the edited one.",
                            null,
                            comparing,
                            () -> {},
                            held -> compare(held, preview)));
        }
        drawPlayback(
                "effect-playback",
                inner,
                effectTime,
                effectLength > 0 ? effectLength : Math.max(effectTime, 1f),
                new float[0],
                "effect",
                seconds -> seekEffect(preview, (float) seconds),
                commands);
        space(GeodataWidgets.ROW);
        drawCharacter(inner, true);
        ImGui.endGroup();
        var bottom = ImGui.getItemRectMaxY() + 10;
        draw.channelsSetCurrent(0);
        draw.addRectFilled(x, y, x + width, bottom, GeodataWidgets.CARD, 8f);
        draw.addRectFilled(
                x, y, x + 4, bottom, SpawnStyle.getImguiColor(EFFECT, 1f, 1f), 8f, ImDrawFlags.RoundCornersLeft);
        draw.channelsMerge();
        ImGui.setCursorScreenPos(x, bottom);
        ImGui.dummy(width, 0);
    }

    private String state(Effect effect, EffectPreview preview) {
        if (load.isRunning(effect) || placeFrames >= 0) {
            return "Reading…";
        }
        if (shown == null
                || !comparing && !effect.path().equalsIgnoreCase(shown.effect().path())) {
            return "Could not be read";
        }
        if (shown.isEmpty()) {
            return "Nothing to draw";
        }
        if (isReadingCharacter(caster)) {
            return caster.playerCharacter ? "Reading the character…" : "Reading the NPC…";
        }
        if (!preview.isShown()) {
            return "Not shown";
        }
        var live = preview.getLiveParticles();
        if (preview.isFinished()) {
            return loop ? "Ended · plays again" : "Ended · R plays it again";
        }
        return String.format(Locale.ROOT, "Playing · %,d %s", live, live == 1 ? "particle" : "particles");
    }

    private void drawSkills(Camera camera, EffectPreview preview) {
        if (skills.isEmpty()) {
            ImGui.textColored(GeodataWidgets.FAINT, "The client has no skill package.");
            space(GeodataWidgets.ROW);
            GeodataWidgets.hint(
                    "Skills need System/LineageSkillEffect.u, as from Gracia Final on, or Animations/skill.usk, as"
                            + " from Chronicle 3 to Interlude. C1 and C2 have neither: the effects of their skills are"
                            + " in the code of Engine.dll, where the Viewer cannot read them. Effects still plays each"
                            + " effect class.");
            space(GeodataWidgets.BLOCK);
            HudWidgets.guideLink("How a skill casts its effects", "effects/skill-casts");
            return;
        }
        handleSkillKeys(camera, preview);
        editor.drawSkillNotice();
        var picked = selectedSkill;
        if (picked != null) {
            drawSkillCard(picked, camera, preview);
            space(GeodataWidgets.BLOCK);
            if (skillEditor.isOpen()) {
                skillEditor.draw(picked, show != null && show.getSkill() == picked ? show : null);
                return;
            }
        }
        GeodataWidgets.searchField(
                "##skillSearch", "Search skills by name or id", search, ImGui.getContentRegionAvailX(), false);
        GeodataWidgets.tip("Part of a skill's name, such as hurricane, or its id, such as 1239.");
        var query = search.get().trim().toLowerCase(Locale.ROOT);
        filterSkills(query);
        GeodataWidgets.section(
                listedSkills.size() == skills.size()
                        ? "SKILLS · " + count(skills.size())
                        : "SKILLS · " + count(listedSkills.size()) + " OF " + count(skills.size()));
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/skill");
        space(GeodataWidgets.ROW);
        if (listedSkills.isEmpty()) {
            ImGui.textColored(GeodataWidgets.FAINT, "No skill matches.");
            return;
        }
        var rowHeight = SpawnPanelWidgets.listRowHeight() + 2;
        var step = beginRows("##skillRows", picked == null ? -1 : listedSkills.indexOf(picked), rowHeight);
        clipper.begin(listedSkills.size(), step);
        while (clipper.step()) {
            for (var i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
                skillRow(listedSkills.get(i), query, rowHeight);
            }
        }
        clipper.end();
        ImGui.endChild();
    }

    private void handleSkillKeys(Camera camera, EffectPreview preview) {
        if (listedSkills.isEmpty()) {
            return;
        }
        var step = 0;
        if (ImGui.isKeyPressed(ImGuiKey.DownArrow, true)) {
            step = 1;
        } else if (ImGui.isKeyPressed(ImGuiKey.UpArrow, true)) {
            step = -1;
        }
        if (step != 0 && !(skillEditor.isOpen() && selectedSkill != null)) {
            var index = selectedSkill == null ? -1 : listedSkills.indexOf(selectedSkill);
            var next = index < 0
                    ? (step > 0 ? 0 : listedSkills.size() - 1)
                    : Math.clamp(index + step, 0, listedSkills.size() - 1);
            chooseSkill(listedSkills.get(next));
        }
        if (ImGui.getIO().getWantTextInput()) {
            return;
        }
        if (ImGui.isKeyPressed(ImGuiKey.R, false)) {
            preview.clear();
            startedFor = null;
        }
        if (pauseKeyPressed()) {
            togglePause();
        }
        if (ImGui.isKeyPressed(ImGuiKey.Comma, true) && show != null) {
            stepBack(show.getShownTime(), seconds -> seekSkill(preview, (float) seconds));
        }
        if (ImGui.isKeyPressed(ImGuiKey.Period, true)) {
            stepForward();
        }
        if (ImGui.isKeyPressed(ImGuiKey.F, false)) {
            frame(camera);
        }
    }

    private void skillRow(Skill skill, String query, float rowHeight) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        if (ImGui.invisibleButton("skill/" + skill.className(), width, rowHeight - 2)) {
            chooseSkill(skill);
        }
        var draw = ImGui.getWindowDrawList();
        if (ImGui.isItemHovered() || skill.equals(selectedSkill)) {
            draw.addRectFilled(
                    x,
                    y,
                    x + width,
                    y + rowHeight - 2,
                    skill.equals(selectedSkill) ? ImColor.rgb(30, 58, 70) : GeodataWidgets.HOVER,
                    6f);
        }
        var edited = skillEditor.isEdited(skill);
        var detail = skill.skillId() + " · "
                + SkillCasting.phases(show != null && show.getSkill() == skill ? show.getPlayed() : skill);
        SpawnPanelWidgets.iconListRow(
                draw,
                x,
                y,
                width,
                skillIcon(skill, 1),
                Glyph.SPARKLES,
                skillName(skill),
                edited ? HudWidgets.truncateToWidth(detail, width - EDITED_WIDTH - rowHeight - 6) : detail,
                "",
                query);
        if (edited) {
            draw.addText(
                    x + width - ImGui.calcTextSizeX("edited") - 6,
                    y + ImGui.getFontSize() + 6,
                    GeodataWidgets.WARNING,
                    "edited");
        }
    }

    private long skillIcon(Skill skill, int level) {
        var named = catalog.getSkill(skill.skillId(), level);
        if (named == null) {
            named = catalog.getSkill(skill.skillId(), 1);
        }
        return icons == null || named == null ? 0 : icons.getTexture(named.icon());
    }

    private void drawSkillCard(Skill skill, Camera camera, EffectPreview preview) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        draw.channelsSplit(2);
        draw.channelsSetCurrent(1);
        ImGui.setCursorScreenPos(x + 14, y + 10);
        ImGui.beginGroup();
        var inner = width - 24;
        NpcPanelWidgets.icon(draw, x + 14, y + 10, TITLE_ICON, skillIcon(skill, casting.getLevel()), Glyph.SPARKLES);
        ImGui.dummy(TITLE_ICON, TITLE_ICON);
        ImGui.sameLine(TITLE_ICON + 8);
        ImGui.alignTextToFramePadding();
        ImGui.textUnformatted(HudWidgets.truncateToWidth(skillName(skill), inner - 90 - TITLE_ICON - 8));
        ImGui.sameLine(inner - 86);
        ImGui.beginDisabled(!editor.isReady());
        if (HudWidgets.button("skill-source", Glyph.FILE_CODE, "", 26, 26, false, false)) {
            editor.openSkillSource(skill.className());
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                skill.isVisual()
                        ? "Script: the skill's object in skill.usk as a .t3d text, rebuilt from the client. Copy or"
                                + " save it."
                        : "Script: the skill's class as a .uc file, rebuilt from the client's compiled class. Copy or"
                                + " save it.");
        ImGui.sameLine(inner - 56);
        ImGui.beginDisabled(!editor.isReady());
        if (HudWidgets.button("skill-edit", Glyph.PENCIL, "", 26, 26, skillEditor.isOpen(), false)) {
            skillEditor.toggle();
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                skillEditor.isOpen()
                        ? "Back to the list of skills. The edits stay."
                        : "Edit this skill, with a live preview. The client does not change until you export and"
                                + " install.");
        ImGui.sameLine(inner - 26);
        if (HudWidgets.button("skill-copy", Glyph.COPY, "", 26, 26, false, false)) {
            ImGui.setClipboardText(skill.getFullName());
        }
        GeodataWidgets.tip("Copy " + skill.getFullName());
        var played = show != null && show.getSkill() == skill ? show.getPlayed() : skill;
        var flight = played.flyingTime() > 0 ? String.format(Locale.ROOT, " · flies %.1f s", played.flyingTime()) : "";
        ImGui.textColored(
                GeodataWidgets.MUTED,
                HudWidgets.truncateToWidth(skill.className() + flight + casting.getRangeText(skill), inner));
        var described =
                show != null && show.getSkill() == skill ? show : new SkillShow(skill, Map.of(), castsOf(skill));
        if (described != show) {
            described.speed(casting.getSpeedFactor(skill));
        }
        ImGui.textColored(
                GeodataWidgets.MUTED,
                HudWidgets.truncateToWidth(casting.getTimeLine(skill, described), inner - HELP_MARK));
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/hit-time");
        ImGui.textColored(
                GeodataWidgets.MUTED, HudWidgets.truncateToWidth(SkillCasting.animationLine(described), inner));
        ImGui.textColored(GeodataWidgets.MUTED, HudWidgets.truncateToWidth(skillState(skill, preview), inner));
        for (var phase : Phase.values()) {
            var actions = played.actions(phase);
            if (actions.isEmpty()) {
                continue;
            }
            var names = new ArrayList<String>();
            for (var action : actions) {
                var name = action.effect().substring(action.effect().indexOf('.') + 1);
                names.add(
                        isKnown(show != null && show.getSkill() == skill ? show : null, action)
                                ? name
                                : name + " (missing)");
            }
            ImGui.pushTextWrapPos(ImGui.getCursorPosX() + inner);
            ImGui.textColored(GeodataWidgets.MUTED, phaseName(phase) + ": " + String.join(", ", names));
            ImGui.popTextWrapPos();
        }
        drawUnnamedNote(skill, inner);
        drawCastFields(skill, inner);
        space(GeodataWidgets.ROW);
        var playing = show;
        var commands = new ArrayList<>(commands(
                new Command("skill-replay", Glyph.RELOAD, "Replay: cast the skill again from its start.", "R", () -> {
                    preview.clear();
                    startedFor = null;
                }),
                new Command(
                        "skill-place",
                        Glyph.PLACE,
                        "Here: stand the caster and the target where the middle of the view meets the ground.",
                        null,
                        () -> placeFrames = 0),
                new Command(
                        "skill-ground",
                        Glyph.TERRAIN,
                        "Ground: stand the caster and the target on the nearest surface under them, each on its own,"
                                + " as the client drops a character. They stay where they are across the map, and only"
                                + " their height changes.",
                        null,
                        onGround,
                        () -> onGround = !onGround),
                new Command(
                        "skill-frame",
                        Glyph.FOCUS,
                        "Frame: look at the caster from close by, from the side it was stood facing.",
                        "F",
                        () -> frame(camera))));
        if (comparingSkill || playing != null && playing.getPlayed() != playing.getSkill()) {
            commands.add(
                    commands.size() - 1,
                    new Command(
                            "skill-compare",
                            Glyph.EYE,
                            "Compare: hold to see the skill as the client has it, at the same moment. Let go for the"
                                    + " edited one.",
                            null,
                            comparingSkill,
                            () -> {},
                            this::compareSkill));
        }
        drawPlayback(
                "skill-playback",
                inner,
                playing == null ? 0f : playing.getShownTime(),
                playing == null ? 1f : playing.length(),
                playing == null ? new float[0] : timeMarks(playing),
                "cast",
                seconds -> seekSkill(preview, (float) seconds),
                commands);
        space(GeodataWidgets.ROW);
        drawCharacter(inner, false);
        ImGui.endGroup();
        var bottom = ImGui.getItemRectMaxY() + 10;
        draw.channelsSetCurrent(0);
        draw.addRectFilled(x, y, x + width, bottom, GeodataWidgets.CARD, 8f);
        draw.addRectFilled(
                x, y, x + 4, bottom, SpawnStyle.getImguiColor(EFFECT, 1f, 1f), 8f, ImDrawFlags.RoundCornersLeft);
        draw.channelsMerge();
        ImGui.setCursorScreenPos(x, bottom);
        ImGui.dummy(width, 0);
    }

    private boolean isKnown(@Nullable SkillShow described, Action action) {
        return skillEditor.knows(action.effect())
                || described != null && described.getParts().containsKey(SkillEffects.key(action.effect()));
    }

    private String skillState(Skill skill, EffectPreview preview) {
        if (loadingSkill != null || placeFrames >= 0) {
            return "Reading…";
        }
        if (show == null || show.getSkill() != skill) {
            return "Could not be read";
        }
        if (show.getPlanned() == 0) {
            return "The client has none of its effects";
        }
        var phase = show.getTime() < show.getShotSeconds()
                ? "Casting"
                : show.getTime() < show.getHitSeconds() ? "Flying" : "Hit";
        return String.format(Locale.ROOT, "%s · %,d particles", phase, preview.getLiveParticles());
    }

    private void chooseSkill(Skill skill) {
        if (skill.equals(selectedSkill) && (show != null || loadingSkill != null)) {
            return;
        }
        if (!skill.equals(selectedSkill)) {
            casting.setLevel(1);
        }
        selectedSkill = skill;
        scrollToSelected = true;
        keepSkillTime = -1f;
        comparingSkill = false;
        editedSkill = null;
        editedSkillParts = Map.of();
        var opened = loader;
        if (opened == null) {
            return;
        }
        var byKey = new HashMap<String, Effect>();
        for (var effect : effects) {
            byKey.putIfAbsent(SkillEffects.key(effect.path()), effect);
        }
        var casts = castsOf(skill);
        var running = editor.getController();
        var edited = running != null && EffectEditController.touches(running.getEdits(), skill)
                ? running.buildSkill(skill.className()).exceptionally(failure -> {
                    LogManager.getLogger(EffectsPanel.class)
                            .warn("The edits of skill {} could not be built", skill.className(), failure);
                    return null;
                })
                : CompletableFuture.<@Nullable BuiltSkill>completedFuture(null);
        loadingSkill = CompletableFuture.supplyAsync(
                        () -> {
                            var parts = new HashMap<String, EffectParts>();
                            for (var phase : Phase.values()) {
                                for (var action : skill.actions(phase)) {
                                    var key = SkillEffects.key(action.effect());
                                    var effect = byKey.get(key);
                                    if (parts.containsKey(key)) {
                                        continue;
                                    }
                                    try {
                                        if (effect != null) {
                                            var loaded = opened.loadEffect(effect);
                                            if (loaded != null && !loaded.isEmpty()) {
                                                parts.put(key, loaded);
                                            }
                                        } else {
                                            var flying = opened.loadProjectile(action.effect());
                                            if (flying != null) {
                                                parts.put(key, flying);
                                            }
                                        }
                                    } catch (RuntimeException failure) {
                                        LogManager.getLogger(EffectsPanel.class)
                                                .warn(
                                                        "Effect {} of skill {} could not be read",
                                                        action.effect(),
                                                        skill.className(),
                                                        failure);
                                    }
                                }
                            }
                            return new SkillShow(skill, parts, casts);
                        },
                        worker)
                .thenCombine(edited, (loaded, built) -> {
                    if (built != null && built.skill() != null) {
                        loaded.play(built.skill(), built.parts());
                    }
                    return loaded;
                });
    }

    private void pollSkill(@Nullable EffectPreview preview) {
        var pending = loadingSkill;
        if (pending == null || !pending.isDone()) {
            return;
        }
        loadingSkill = null;
        SkillShow loaded;
        try {
            loaded = pending.join();
        } catch (RuntimeException failure) {
            LogManager.getLogger(EffectsPanel.class).warn("Skill {} could not be read", selectedSkill, failure);
            loaded = null;
        }
        if (loaded != null && loaded.getSkill() != selectedSkill) {
            if (selectedSkill != null) {
                chooseSkill(selectedSkill);
            }
            return;
        }
        show = loaded;
        if (loaded != null) {
            loaded.casts(castsOf(loaded.getSkill()));
        }
        startedFor = null;
        if (preview != null) {
            preview.clear();
        }
        if (anchor == null) {
            placeFrames = 0;
        }
    }

    private void playSkill(Camera camera, @Nullable EffectPreview preview, float seconds) {
        var at = anchor;
        var current = show;
        if (at == null || preview == null || current == null || selectedSkill == null) {
            if (stage != null) {
                stage.staged = List.of();
            }
            return;
        }
        var model = characterModel(caster);
        var self = castsOnCaster(current.getPlayed());
        var targetWho = target.onCharacter && !self ? target : caster;
        var targetModel = characterModel(targetWho);
        if (model == null && isReadingCharacter(caster) || targetModel == null && isReadingCharacter(targetWho)) {
            return;
        }
        var skillCast = casting.getCast(current.getSkill());
        var radii = new Pawn(at, 0f, model).radius() + new Pawn(at, 0f, targetModel).radius();
        skillApart = self
                ? 0f
                : skillCast == null ? SKILL_DISTANCE : casting.getDistance(current.getSkill(), skillCast) + radii;
        if (!distanceHeld) {
            settledApart = skillApart;
        }
        var stand = SkillShow.stand(at, characterYaw, skillApart);
        if (framePending) {
            framePending = false;
            frame(camera);
        }
        var casterFeet = grounded(stand.caster(), model);
        var targetFeet = self ? casterFeet : grounded(stand.target(), targetModel);
        casterStands = casterFeet;
        targetStands = targetFeet;
        var casterYaw = self
                ? characterYaw
                : (float) Math.atan2(targetFeet.getY() - casterFeet.getY(), targetFeet.getX() - casterFeet.getX());
        var targetYaw = casterYaw + (float) Math.PI;
        casterBones = model == null ? Map.of() : model.bones();
        targetBones = targetModel == null ? Map.of() : targetModel.bones();
        var casterFigure = caster.figure;
        var moving = casterFigure != null && model == casterFigure.model();
        var laidOutBy = moving ? casterFigure.animation() : standIn();
        if (!moving && laidOutBy == null && isReadingStandIn()) {
            return;
        }
        if (moving) {
            var animation = casterFigure.animation();
            var cast = current.getCasterMoment(animation);
            var castPose = animation.getPose(cast.sequence(), cast.seconds());
            caster.pose.set(casterFigure.vertices(castPose));
            casterBones = casterFigure.bones(castPose);
        } else if (laidOutBy != null) {
            current.getCasterMoment(laidOutBy, false);
        }
        var targetFigure = targetWho.figure;
        var targetMoving = targetFigure != null && targetModel == targetFigure.model();
        if (targetMoving) {
            if (targetPoseOf != targetFigure) {
                targetPoseOf = targetFigure;
                targetPose = new LivePose(targetFigure.model().vertices());
            }
            figureSeconds += seconds;
            var animation = targetFigure.animation();
            var wait = SkillShow.waiting(animation, figureSeconds);
            var waitPose = animation.getPose(wait.sequence(), wait.seconds());
            targetPose.set(targetFigure.vertices(waitPose));
            targetBones = targetFigure.bones(waitPose);
        }
        if (stage != null) {
            var standing = new ArrayList<ModelInstance>();
            if (model != null) {
                standing.add(new ModelInstance(
                        model,
                        casterFeet.getX(),
                        casterFeet.getY(),
                        casterFeet.getZ(),
                        casterYaw,
                        1f,
                        moving ? caster.pose : null));
            }
            if (targetModel != null && !self) {
                standing.add(new ModelInstance(
                        targetModel,
                        targetFeet.getX(),
                        targetFeet.getY(),
                        targetFeet.getZ(),
                        targetYaw,
                        1f,
                        targetMoving ? targetPose : null));
            }
            stage.staged = List.copyOf(standing);
        }
        var castSounds = skillSounds(current.getSkill());
        if (castSounds == null) {
            return;
        }
        var key = current.getSkill().className()
                + "|"
                + at
                + "|"
                + (model == null ? "none" : model.key())
                + "|"
                + (targetModel == null ? "none" : targetModel.key())
                + "|"
                + settledApart
                + "|"
                + (onGround ? floorsRevision() : -1)
                + "|"
                + self
                + "|"
                + soundsFor;
        if (!key.equals(startedFor)) {
            startedFor = key;
            preview.clear();
            preview.stopSounds();
            current.casts(castsOf(current.getSkill()));
            current.setSounds(castSounds);
            current.speed(casting.getSpeedFactor(current.getSkill()));
            var casterPawn = new Pawn(casterFeet, casterYaw, model, () -> casterBones);
            current.start(
                    casterPawn, self ? casterPawn : new Pawn(targetFeet, targetYaw, targetModel, () -> targetBones));
            if (laidOutBy != null) {
                current.getCasterMoment(laidOutBy, moving);
            }
            if (keepSkillTime >= 0f) {
                current.seek(preview, Math.min(keepSkillTime, LONGEST_EFFECT));
                keepSkillTime = -1f;
            }
        }
        current.repeatPause(repeatPause);
        current.update(preview, seconds, loop);
    }

    private Vec3 grounded(Vec3 feet, @Nullable SpawnModelData model) {
        if (!onGround || stage == null) {
            return feet;
        }
        readFloorsAt(feet);
        return SkillShow.onFloor(feet, stage.floors, model);
    }

    private long floorsRevision() {
        return stage == null ? 0 : stage.floors.getRevision();
    }

    private void readFloorsAt(Vec3 place) {
        if (loadingFloors != null && loadingFloors.isDone()) {
            loadingFloors = null;
        }
        var opened = loader;
        if (loadingFloors != null
                || opened == null
                || stage == null
                || stage.floors.covers(place.getX(), place.getY())) {
            return;
        }
        var tile = WorldTile.at(place.getX(), place.getY());
        var name = tile.x() + "_" + tile.y();
        if (!floorsAsked.add(name)) {
            return;
        }
        var floors = stage.floors;
        loadingFloors = CompletableFuture.runAsync(
                () -> {
                    try {
                        var grid = ClientFloorGrid.build(opened.loadMap(name), () -> false);
                        if (grid != null) {
                            floors.put(grid);
                        }
                    } catch (Exception failure) {
                        LogManager.getLogger(EffectsPanel.class)
                                .warn("The floors of map {} could not be read", name, failure);
                    }
                },
                worker);
    }

    private @Nullable String voiceFigure() {
        var figure = caster.onCharacter && caster.playerCharacter ? caster.characterForm.getFigure() : null;
        return figure == null ? null : PlayerFigures.getLookFigure(figure);
    }

    private @Nullable List<Sound> skillSounds(Skill skill) {
        var figure = voiceFigure();
        var level = casting.getLevel();
        var key = skill.skillId() + "|" + level + "|" + figure + "|" + System.identityHashCode(catalog);
        if (!key.equals(soundsFor)) {
            soundsFor = key;
            sounds = List.of();
            var opened = loader;
            var wanted = catalog.getSkillSounds(skill.skillId(), level, figure);
            loadingSounds = opened == null || wanted.isEmpty()
                    ? null
                    : CompletableFuture.supplyAsync(
                            () -> {
                                var read = new ArrayList<Sound>();
                                for (var sound : wanted) {
                                    var clip = opened.loadSound(sound.sound());
                                    if (clip != null) {
                                        read.add(new Sound(
                                                new EmitterSound(
                                                        clip,
                                                        sound.radius(),
                                                        sound.volume(),
                                                        1f,
                                                        1f,
                                                        false,
                                                        false,
                                                        sound.delay(),
                                                        0f,
                                                        0f,
                                                        0f),
                                                sound.moment(),
                                                sound.onTarget()));
                                    }
                                }
                                return List.copyOf(read);
                            },
                            worker);
        }
        var pending = loadingSounds;
        if (pending != null) {
            if (!pending.isDone()) {
                return null;
            }
            loadingSounds = null;
            try {
                sounds = pending.join();
            } catch (RuntimeException failure) {
                LogManager.getLogger(EffectsPanel.class)
                        .warn("Sounds of skill {} could not be read", skill.skillId(), failure);
                sounds = List.of();
            }
        }
        return sounds;
    }

    private void drawUnnamedNote(Skill skill, float width) {
        if (!skill.isVisual()) {
            return;
        }
        var cast = casting.getCast(skill);
        if (cast == null || SkillEffects.key(cast.effect()).equals(SkillEffects.key(skill.className()))) {
            return;
        }
        space(GeodataWidgets.ROW);
        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + width);
        ImGui.textColored(
                GeodataWidgets.WARNING,
                cast.effect().isBlank()
                        ? "Skillgrp names no visual effect for this skill at this level, so the game draws it in the"
                                + " code of Engine.dll and never plays " + skill.className()
                                + ". An edit of it changes nothing in the game."
                        : "Skillgrp names " + cast.effect() + " for this skill at this level, so the game plays that"
                                + " and never " + skill.className() + ".");
        ImGui.popTextWrapPos();
    }

    private void filterSkills(String query) {
        if (query.equals(listedSkillQuery) && listedSkillsOf == skills) {
            return;
        }
        listedSkillQuery = query;
        listedSkillsOf = skills;
        listedSkills.clear();
        for (var skill : skills) {
            if (query.isEmpty()
                    || Integer.toString(skill.skillId()).equals(query)
                    || skillName(skill).toLowerCase(Locale.ROOT).contains(query)
                    || skill.className().toLowerCase(Locale.ROOT).contains(query)) {
                listedSkills.add(skill);
            }
        }
    }

    private List<Cast> castsOf(Skill skill) {
        return casting.getCasts(skill, caster.onCharacter && !caster.playerCharacter ? caster.characterId : 0);
    }

    private void drawCastFields(Skill skill, float width) {
        var last = catalog.getLastCastLevel(skill.skillId());
        var cast = casting.getCast(skill);
        var speed = cast == null || !cast.fixed();
        var distance = cast != null && !castsOnCaster(skill);
        if (last <= 1 && !speed && !distance) {
            return;
        }
        space(GeodataWidgets.ROW);
        var spacing = ImGui.getStyle().getItemSpacingX();
        var third = (width - 2 * spacing) / 3;
        var fields = new ArrayList<BooleanSupplier>();
        if (last > 1) {
            fields.add(() -> levelField(skill, last, third));
        }
        if (speed) {
            fields.add(() -> speedField(cast == null || cast.magic(), third));
        }
        if (distance) {
            fields.add(() -> distanceField(skill, cast, third));
        }
        GeodataWidgets.columns(
                fields.size() * third + (fields.size() - 1) * spacing, fields.toArray(BooleanSupplier[]::new));
        if (distance && casting.getDistance(skill, cast) > cast.range()) {
            space(GeodataWidgets.ROW);
            ImGui.pushTextWrapPos(ImGui.getCursorPosX() + width);
            ImGui.textColored(
                    GeodataWidgets.WARNING,
                    "Beyond the range of " + cast.range() + ": the server makes the caster run closer "
                            + "first, so the game never casts from here.");
            ImGui.popTextWrapPos();
        }
    }

    private boolean levelField(Skill skill, int last, float width) {
        if (!levelHeld) {
            levelDraft[0] = casting.getLevel();
        }
        var left = GeodataWidgets.compactDrag("Level", "##skill-level", levelDraft, width, .1f, 1, last);
        levelHeld = ImGui.isItemActive();
        GeodataWidgets.tip("The level of the skill, up to " + last
                + ". Levels can cast for other times or with other animations." + DRAG_TIP);
        if (left) {
            casting.setLevel(levelDraft[0]);
            if (show != null && show.getSkill() == skill) {
                show.casts(castsOf(skill));
            }
            startedFor = null;
        }
        return left;
    }

    private boolean speedField(boolean magic, float width) {
        if (!speedHeld) {
            speedDraft[0] = magic ? casting.getCastingSpeed() : casting.getAttackSpeed();
        }
        var left = GeodataWidgets.compactDrag(
                magic ? "Casting Spd" : "Atk. Spd",
                "##skill-speed",
                speedDraft,
                width,
                2,
                SkillCasting.MIN_SPEED,
                SkillCasting.MAX_SPEED);
        speedHeld = ImGui.isItemActive();
        GeodataWidgets.tip((magic
                        ? "The caster's Casting Spd. The server casts magic for its hit time × 333 / Casting Spd: 333"
                                + " casts for the time in Skillgrp, 666 for half of it. Spiritshots cut the time by"
                                + " about 30% on most servers."
                        : "The caster's Atk. Spd. The server casts physical skills, dances and songs for their hit"
                                + " time × 333 / Atk. Spd: 333 casts for the time in Skillgrp, 666 for half of it.")
                + DRAG_TIP);
        if (left) {
            if (magic) {
                casting.setCastingSpeed(speedDraft[0]);
            } else {
                casting.setAttackSpeed(speedDraft[0]);
            }
            startedFor = null;
        }
        return left;
    }

    private boolean distanceField(Skill skill, ClientCatalog.Cast cast, float width) {
        casting.getDistance(skill, cast);
        var furthest = SkillCasting.furthest(cast);
        var left = GeodataWidgets.compactDrag(
                "Distance", "##skill-distance", casting.distance, width, Math.max(1, furthest / 400f), 0, furthest);
        distanceHeld = ImGui.isItemActive();
        GeodataWidgets.tip("How far the target stands from the caster, between their bodies: the skill's cast range of "
                + cast.range()
                + " at first, up to twice as far. The server lets a caster cast from anywhere inside the range. The"
                + " caster stays where it stands."
                + DRAG_TIP);
        return left;
    }

    private String skillName(Skill skill) {
        return casting.getSkillName(skill);
    }

    private @Nullable Effect byKey(String effectPath) {
        var key = SkillEffects.key(effectPath);
        for (var effect : effects) {
            if (SkillEffects.key(effect.path()).equals(key)) {
                return effect;
            }
        }
        return null;
    }

    private void drawCharacter(float width, boolean withSpot) {
        if (!withSpot && hasTarget()) {
            whoTop = ImGui.getCursorScreenPosY();
            var picked = GeodataWidgets.segmented(
                    "effect-who", new String[] {"Caster", "Target"}, targetShown ? 1 : 0, width);
            if (picked >= 0) {
                targetShown = picked == 1;
            }
            GeodataWidgets.tip("Whose choices show below. The caster and the target can be different characters.");
            space(GeodataWidgets.ROW);
        }
        var who = chosen();
        var forTarget = who == target;
        choiceLeft = ImGui.getCursorScreenPosX();
        choiceTop = ImGui.getCursorScreenPosY();
        choiceWidth = width;
        if (forTarget) {
            drawTargetChoice(width);
        } else {
            var current = CharacterChoice.of(who.onCharacter, who.playerCharacter);
            var next = CharacterChoice.clicked(
                    GeodataWidgets.segmented("effect-on", CharacterChoice.labels(withSpot), current.ordinal(), width),
                    current);
            if (next != null) {
                who.onCharacter = next.onCharacter();
                who.playerCharacter = next.isPlayerCharacter(who.playerCharacter);
                placedFor = null;
                startedFor = null;
            }
            if (ImGui.isItemHovered()) {
                var choices = CharacterChoice.values();
                var hovered = choices[GeodataWidgets.hoveredSegment(choiceLeft, width, choices.length)];
                GeodataWidgets.tip(hovered.getTip(withSpot));
            }
        }
        if (!who.onCharacter) {
            return;
        }
        space(GeodataWidgets.ROW);
        if (who.playerCharacter && client != null) {
            who.characterForm.read(figures(), client, variant, worker);
        }
        foldTop = ImGui.getCursorScreenPosY();
        var summary = who.playerCharacter ? who.characterForm.getSummary() : npcSummary();
        if (SpawnPanelWidgets.groupRow(
                "effect-character-fold",
                characterOpen,
                "Character",
                summary,
                "",
                "",
                SpawnPanelWidgets.listRowHeight(),
                width)) {
            characterOpen = !characterOpen;
        }
        GeodataWidgets.tip((characterOpen
                        ? "Fold the character's choices, to keep the list under the card in reach."
                        : "Show the character's choices.")
                + "\n\n" + summary);
        if (characterOpen) {
            space(GeodataWidgets.ROW);
            if (who.playerCharacter) {
                drawPlayerCharacter(width);
            } else {
                drawNpcCharacter(width);
            }
        }
        var note = characterNote(chosen());
        if (note != null) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(note.text(), note.color(), width);
        }
        if (!withSpot) {
            return;
        }
        space(GeodataWidgets.ROW);
        var spots = Spot.values();
        var labels = new String[spots.length];
        for (var i = 0; i < spots.length; i++) {
            labels[i] = spots[i].label;
        }
        spotTop = ImGui.getCursorScreenPosY();
        var picked = GeodataWidgets.segmented("effect-spot", labels, spot.ordinal(), width);
        if (picked >= 0 && picked != spot.ordinal()) {
            spot = spots[picked];
            placedFor = null;
        }
        GeodataWidgets.tip(
                "Where the effect hangs: the feet, the chest, the head or a hand of the character's skeleton. It"
                    + " follows the bone as the character moves. A skeleton without such a bone gives a place at that"
                    + " height on the middle of the body.");
    }

    private void drawTargetChoice(float width) {
        var current = TargetChoice.of(targetSelf, target.onCharacter, target.playerCharacter);
        var next = TargetChoice.clicked(
                GeodataWidgets.segmented("effect-target-on", TargetChoice.labels(), current.ordinal(), width), current);
        if (next != null) {
            targetSelf = next.onSelf();
            target.onCharacter = next.onCharacter();
            target.playerCharacter = next.isPlayerCharacter(target.playerCharacter);
            placedFor = null;
            startedFor = null;
        }
        if (ImGui.isItemHovered()) {
            var choices = TargetChoice.values();
            GeodataWidgets.tip(choices[GeodataWidgets.hoveredSegment(choiceLeft, width, choices.length)].getTip());
        }
    }

    private boolean hasTarget() {
        return skillMode && selectedSkill != null && !casting.onSelf(selectedSkill);
    }

    private boolean castsOnCaster(Skill skill) {
        return targetSelf || casting.onSelf(skill);
    }

    private EffectCharacter chosen() {
        return targetShown && hasTarget() ? target : caster;
    }

    private String npcSummary() {
        var id = chosen().characterId;
        var npc = id > 0 ? catalog.getNpc(id) : null;
        return CharacterChoice.npcSummary(id, npc == null ? null : npc.name());
    }

    private void drawNpcCharacter(float width) {
        var who = chosen();
        var filter = GeodataWidgets.searchList(
                "##effect-character", npcSummary(), who.characterSearch, "Search NPCs by name or id", width);
        if (filter != null) {
            var query = filter.trim().toLowerCase(Locale.ROOT);
            var rows = 0;
            for (var candidate : catalog.getNpcs()) {
                if (rows >= 300) {
                    break;
                }
                var name = candidate.name();
                if (!query.isEmpty()
                        && !name.toLowerCase(Locale.ROOT).contains(query)
                        && !Integer.toString(candidate.id()).equals(query)) {
                    continue;
                }
                rows++;
                if (GeodataWidgets.listItem(
                        "effect-npc/" + candidate.id(),
                        name.isEmpty() ? "NPC " + candidate.id() : name,
                        Integer.toString(candidate.id()),
                        candidate.id() == who.characterId)) {
                    who.characterId = candidate.id();
                    placedFor = null;
                }
            }
            ImGui.endCombo();
        }
        GeodataWidgets.tip(
                "The NPC that stands at the place. NPCs built like players, such as guards, stand in their armour.");
    }

    private void drawPlayerCharacter(float width) {
        if (client != null) {
            chosen().characterForm.drawUnderHeader(false, width);
        }
    }

    private @Nullable CharacterNote characterNote(EffectCharacter who) {
        if (who.playerCharacter) {
            if (who.figureLoading != null) {
                return new CharacterNote("Reading the character…", GeodataWidgets.MUTED);
            }
            if (who.figure == null && !who.figureFor.isEmpty() && who.figureFor.equals(who.characterForm.getKey())) {
                return new CharacterNote(
                        "This character cannot be built from the client's player parts, so the effect stays on the"
                                + " ground.",
                        GeodataWidgets.WARNING);
            }
            return null;
        }
        if (who.characterId <= 0 || models == null) {
            return null;
        }
        var status = models.getStatus(who.characterId);
        if (status == Status.LOADING || who.figureLoading != null) {
            return new CharacterNote("Reading the NPC's model…", GeodataWidgets.MUTED);
        }
        if (status != Status.READY) {
            return new CharacterNote(
                    "The client draws no model for this NPC, so the effect stays on the ground.",
                    GeodataWidgets.WARNING);
        }
        if (who.figure == null && who.figureFor.equals("npc:" + who.characterId)) {
            return new CharacterNote(
                    "The NPC has no animation the viewer can read, so it stands still.", GeodataWidgets.MUTED);
        }
        return null;
    }

    private void takeOrder(Camera camera, @Nullable EffectPreview preview) {
        var next = order;
        if (next == null || isListing()) {
            return;
        }
        order = null;
        frameWanted = next.frame();
        if (next.caster() != null) {
            stand(caster, next.caster());
        }
        if (next.target() != null) {
            stand(target, next.target());
            targetSelf = next.target().self();
        }
        if (next.spot() != null) {
            spot = next.spot();
        }
        if (next.repeat() != null) {
            loop = next.repeat();
        }
        if (next.ground() != null) {
            onGround = next.ground();
        }
        var at = next.at();
        if (at != null) {
            var eye = camera.getPosition();
            anchor = at;
            placeFrames = -1;
            framePending = false;
            characterYaw = (float) Math.atan2(eye.getY() - at.getY(), eye.getX() - at.getX());
        }
        var skill = next.skill();
        var effect = next.effect();
        if (skill != null) {
            if (!skillMode && preview != null) {
                switchMode(true, preview);
            }
            skillMode = true;
            chooseSkill(skill);
            if (next.level() != null) {
                casting.setLevel(Math.clamp(next.level(), 1, casting.getLastLevel(skill)));
                if (show != null && show.getSkill() == skill) {
                    show.casts(castsOf(skill));
                }
            }
            if (next.speed() != null) {
                casting.getSpeed(skill, next.speed());
            }
            var cast = casting.getCast(skill);
            if (next.distance() != null && cast != null) {
                casting.getDistance(skill, cast, next.distance());
            }
        } else if (effect != null) {
            if (skillMode && preview != null) {
                switchMode(false, preview);
            }
            skillMode = false;
            choose(effect);
        }
        placedFor = null;
        startedFor = null;
        keepSkillTime = -1f;
    }

    private void stand(EffectCharacter who, Who choice) {
        who.onCharacter = choice.on();
        who.playerCharacter = choice.on() && choice.figure() != null;
        if (choice.npc() > 0) {
            who.characterId = choice.npc();
        }
        who.wantedRace = who.playerCharacter ? choice.figure() : null;
    }

    private void dressAsked(EffectCharacter who) {
        var figure = who.wantedRace;
        if (figure == null || client == null) {
            return;
        }
        who.characterForm.read(figures(), client, variant, worker);
        if (who.characterForm.getData() != null) {
            who.characterForm.show(new Look("", figure, 0, Map.of()));
            who.wantedRace = null;
            placedFor = null;
            startedFor = null;
        }
    }

    private void reportEffect(Map<String, Object> data, EffectPreview preview) {
        var picked = selected;
        if (picked == null) {
            data.put("ready", order == null);
            data.put("state", "No effect is chosen.");
            return;
        }
        var waiting = order != null
                || load.isRunning()
                || placeFrames >= 0
                || shown != null && !shown.isEmpty() && placedFor == null;
        data.put("ready", !waiting);
        data.put("effect", picked.path());
        data.put("kinds", kinds(picked));
        data.put("state", state(picked, preview));
        var notes = notes(picked);
        if (!notes.isEmpty()) {
            data.put("notes", notes);
        }
        data.put("edited", editor.isEdited(picked.path()));
        data.put("on", who(caster));
        if (caster.onCharacter) {
            data.put("spot", spot.label);
        }
        var note = characterNote(caster);
        if (note != null) {
            data.put("character", note.text());
        }
        data.put("time", round(effectTime));
        if (effectLength > 0) {
            data.put("length", round(effectLength));
        }
    }

    private void reportSkill(Map<String, Object> data, EffectPreview preview) {
        var skill = selectedSkill;
        if (skill == null) {
            data.put("ready", order == null);
            data.put("state", "No skill is chosen.");
            return;
        }
        var current = show != null && show.getSkill() == skill ? show : null;
        var waiting =
                order != null || loadingSkill != null || placeFrames >= 0 || current != null && startedFor == null;
        data.put("ready", !waiting);
        data.put("skill", skill.skillId());
        data.put("name", skillName(skill));
        data.put("class", skill.className());
        data.put("level", casting.getLevel());
        data.put("levels", casting.getLastLevel(skill));
        data.put("state", skillState(skill, preview));
        var described = current != null ? current : new SkillShow(skill, Map.of(), castsOf(skill));
        if (described != current) {
            described.speed(casting.getSpeedFactor(skill));
        }
        var played = described.getPlayed();
        data.put("cast", casting.getTimeLine(skill, described) + casting.getRangeText(skill));
        data.put("animation", SkillCasting.animationLine(described));
        if (played.flyingTime() > 0) {
            data.put("flies", round(played.flyingTime()));
        }
        if (!casting.isFixed(skill)) {
            data.put(casting.isMagic(skill) ? "casting_spd" : "atk_spd", casting.getSpeed(skill));
        }
        var cast = casting.getCast(skill);
        var self = castsOnCaster(played);
        if (cast != null && !self) {
            var distance = casting.getDistance(skill, cast);
            data.put("distance", distance);
            data.put("range", cast.range());
            if (distance > cast.range()) {
                data.put(
                        "note",
                        "Beyond the range of " + cast.range()
                                + ": the server makes the caster run closer first, so the game never casts from here.");
            }
        }
        var phases = new LinkedHashMap<String, List<String>>();
        for (var phase : Phase.values()) {
            var counts = new LinkedHashMap<String, Integer>();
            for (var action : played.actions(phase)) {
                var name = action.effect().substring(action.effect().indexOf('.') + 1);
                counts.merge(isKnown(described, action) ? name : name + " (missing)", 1, Integer::sum);
            }
            var names = new ArrayList<String>();
            counts.forEach((name, count) -> names.add(count == 1 ? name : name + " ×" + count));
            if (!names.isEmpty()) {
                phases.put(phaseName(phase), names);
            }
        }
        data.put("effects", phases);
        var figure = voiceFigure();
        var heard = new ArrayList<String>();
        for (var sound : catalog.getSkillSounds(skill.skillId(), casting.getLevel(), figure)) {
            heard.add(sound.sound());
        }
        if (!heard.isEmpty()) {
            data.put("sounds", heard);
        }
        data.put("caster", who(caster));
        if (!self) {
            data.put("target", target.onCharacter ? who(target) : "copy of the caster");
        } else if (targetSelf && !casting.onSelf(played)) {
            data.put("target", "self");
        }
        for (var who : self ? List.of(caster) : List.of(caster, target)) {
            var note = characterNote(who);
            if (note != null) {
                data.put(who == caster ? "caster_note" : "target_note", note.text());
            }
        }
        data.put("ground", onGround);
        if (current != null && startedFor != null) {
            data.put("time", round(current.getTime()));
            data.put("length", round(current.length()));
            data.put("shot", round(current.getShotSeconds()));
            data.put("hit", round(current.getHitSeconds()));
            var lives = new LinkedHashMap<String, Double>();
            current.getLives().forEach((name, seconds) -> lives.put(name, round(seconds)));
            if (!lives.isEmpty()) {
                data.put("cast_lasts", lives);
            }
            var shots = current.getShotTimes();
            if (shots.length > 1) {
                data.put("shots", rounded(shots));
                data.put("hits", rounded(current.getHitTimes()));
            }
            if (casterStands != null) {
                data.put("caster_at", point(casterStands));
            }
            if (targetStands != null && !self) {
                data.put("target_at", point(targetStands));
            }
        }
    }

    private String who(EffectCharacter who) {
        if (!who.onCharacter) {
            return "nobody";
        }
        if (who.playerCharacter) {
            return who.wantedRace != null ? "Reading the races…" : who.characterForm.getSummary();
        }
        var npc = who.characterId > 0 ? catalog.getNpc(who.characterId) : null;
        return CharacterChoice.npcSummary(who.characterId, npc == null ? null : npc.name());
    }

    private void frame(Camera camera) {
        frame(camera, anchor);
    }

    private void frame(Camera camera, @Nullable Vec3 preview) {
        if (preview == null) {
            return;
        }
        var look = camera.getPerspectiveLookDir();
        var yaw = Math.atan2(look.getY(), look.getX());
        if (skillMode) {
            yaw = characterYaw + Math.PI;
        }
        var distance = (double) FRAME_DISTANCE;
        var pitch = Math.toRadians(FRAME_PITCH);
        var back = Math.cos(pitch) * distance;
        var target = new Vec3(preview.getX(), preview.getY(), preview.getZ() + FRAME_HEIGHT);
        camera.setPosition(
                (float) (target.getX() - Math.cos(yaw) * back), (float) (target.getY() - Math.sin(yaw) * back), (float)
                        (target.getZ() - Math.sin(pitch) * distance));
        camera.setDirection(Math.toDegrees(yaw), FRAME_PITCH);
    }

    private void filter(String query) {
        if (!listDirty && query.equals(listedQuery)) {
            return;
        }
        listDirty = false;
        listedQuery = query;
        listed.clear();
        for (var effect : effects) {
            if (query.isEmpty()
                    || effect.name().toLowerCase(Locale.ROOT).contains(query)
                    || effect.packageName().toLowerCase(Locale.ROOT).equals(query)) {
                listed.add(effect);
            }
        }
    }

    private String packageLabel(Effect effect) {
        return effects.isEmpty() || effects.getFirst().packageName().equals(effect.packageName())
                ? ""
                : effect.packageName();
    }

    private static float measureLength(EffectPreview preview) {
        var time = 0f;
        var length = 0f;
        while (time < LONGEST_EFFECT) {
            preview.advance(MEASURE_STEP);
            time += MEASURE_STEP;
            if (preview.isFinished()) {
                length = time;
                break;
            }
        }
        preview.replay();
        return length;
    }

    private static String pauseLabel(float seconds) {
        return (seconds == Math.rint(seconds) ? Integer.toString((int) seconds) : Float.toString(seconds)) + " s";
    }

    private static String speedLabel(double speed) {
        return (speed == Math.rint(speed) ? Integer.toString((int) speed) : Double.toString(speed)) + "x";
    }

    private static float inView(float scroll, float top, float step, float height) {
        if (top < scroll) {
            return top;
        }
        return top + step > scroll + height ? top + step - height : scroll;
    }

    private static String point(Vec3 at) {
        return Math.round(at.getX()) + "," + Math.round(at.getY()) + "," + Math.round(at.getZ());
    }

    private static double round(float value) {
        return Math.round(value * 100) / 100.0;
    }

    private static List<Double> rounded(float[] values) {
        var list = new ArrayList<Double>(values.length);
        for (var value : values) {
            list.add(round(value));
        }
        return list;
    }

    private static float[] timeMarks(SkillShow show) {
        var shots = show.getShotTimes();
        var hits = show.getHitTimes();
        var marks = Arrays.copyOf(shots, shots.length + hits.length);
        System.arraycopy(hits, 0, marks, shots.length, hits.length);
        return marks;
    }

    private static List<String> notes(Effect effect) {
        var notes = new ArrayList<String>();
        if (effect.undrawn() > 0) {
            notes.add(String.format(
                    Locale.ROOT,
                    "%d spark, trail or ribbon %s not drawn.",
                    effect.undrawn(),
                    effect.undrawn() == 1 ? "emitter is" : "emitters are"));
        }
        if (effect.scripted()) {
            notes.add("Its script can change it in the game, and the viewer does not run scripts.");
        }
        return notes;
    }

    private static String count(int count) {
        return String.format(Locale.ROOT, "%,d", count);
    }
}
