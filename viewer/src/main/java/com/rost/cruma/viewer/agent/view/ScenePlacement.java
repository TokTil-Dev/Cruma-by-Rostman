package com.rost.cruma.viewer.agent.view;

import com.rost.cruma.core.editing.ActorRecord;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.viewer.render.EditorEffectStage;
import com.rost.cruma.viewer.render.api.DebugSceneObject;
import com.rost.cruma.viewer.render.api.DebugSceneObject.Category;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public record ScenePlacement(
        @Nullable String actor,
        String mesh,
        String className,
        Category category,
        Bounds3f bounds,
        DebugSceneObject row) {
    private static final String NO_ACTOR = "Unavailable";
    private static final String UNNAMED_MESH = "/entity-";

    public static ScenePlacement of(DebugSceneObject row) {
        var path = row.actorName();
        var actor = path == null
                        || path.isBlank()
                        || NO_ACTOR.equals(path)
                        || path.endsWith(ViewerRenderBackend.EDITOR_SURFACES)
                ? null
                : path;
        var mesh = switch (row.category()) {
            case CSG -> row.materialName();
            case TERRAIN -> "Terrain of " + row.owner().substring(row.owner().lastIndexOf('/') + 1);
            case STATIC_MESH -> row.assetName();
        };
        return new ScenePlacement(actor, mesh, classOf(actor, row.category()), row.category(), row.bounds(), row);
    }

    static String key(DebugSceneObject row) {
        var actor = row.actorName();
        if (row.category() == Category.STATIC_MESH && actor != null && !actor.isBlank() && !NO_ACTOR.equals(actor)) {
            return actor;
        }
        var key = row.renderObjectId() + "/" + row.instanceOrdinal() + "/" + row.sourcePartOrdinal();
        return row.category() == Category.STATIC_MESH ? key : key + "/" + row.surfaceOrdinal();
    }

    static boolean isDecoration(DebugSceneObject row) {
        return row.category() == Category.STATIC_MESH
                && NO_ACTOR.equals(row.actorName())
                && !row.assetName().contains(UNNAMED_MESH);
    }

    static boolean isObject(DebugSceneObject row) {
        return row.category() == Category.STATIC_MESH
                && !NO_ACTOR.equals(row.actorName())
                && !EditorEffectStage.isEffectRow(row);
    }

    static String shortName(String path) {
        var end = Math.max(path.lastIndexOf('.'), path.lastIndexOf('/'));
        return path.substring(end + 1);
    }

    static String classOf(String name) {
        var end = name.length();
        while (end > 0 && Character.isDigit(name.charAt(end - 1))) {
            end--;
        }
        return end == 0 ? name : name.substring(0, end);
    }

    boolean merged() {
        return row.sourcePartOrdinal() >= 0;
    }

    public ScenePlacement with(DebugSceneObject other) {
        var box = other.bounds();
        var union = new Bounds3f(
                Math.min(bounds.minX(), box.minX()),
                Math.min(bounds.minY(), box.minY()),
                Math.min(bounds.minZ(), box.minZ()),
                Math.max(bounds.maxX(), box.maxX()),
                Math.max(bounds.maxY(), box.maxY()),
                Math.max(bounds.maxZ(), box.maxZ()));
        var named = mesh.contains(UNNAMED_MESH) ? other.assetName() : mesh;
        return new ScenePlacement(actor, named, className, category, union, row);
    }

    ScenePlacement with(@Nullable ActorRecord record) {
        if (record == null) {
            return this;
        }
        var saved = record.meshSourcePath();
        return new ScenePlacement(
                actor, saved == null || saved.isBlank() ? mesh : saved, record.className(), category, bounds, row);
    }

    String name() {
        if (actor != null) {
            return actor;
        }
        return category == Category.TERRAIN ? mesh : row.shortName();
    }

    String tile() {
        var tile = WorldTile.at(bounds.centerX(), bounds.centerY());
        return tile.x() + "_" + tile.y();
    }

    String point() {
        return Math.round(bounds.centerX()) + "," + Math.round(bounds.centerY()) + "," + Math.round(bounds.centerZ());
    }

    double distanceTo(double x, double y, double z) {
        var dx = Math.max(0, Math.max(bounds.minX() - x, x - bounds.maxX()));
        var dy = Math.max(0, Math.max(bounds.minY() - y, y - bounds.maxY()));
        var dz = Math.max(0, Math.max(bounds.minZ() - z, z - bounds.maxZ()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    boolean matches(@Nullable String name, @Nullable String wantedMesh, @Nullable String wantedClass) {
        return holds(actor == null ? row.shortName() : actor, name)
                && holds(mesh, wantedMesh)
                && holds(className, wantedClass);
    }

    private static boolean holds(String text, @Nullable String wanted) {
        return wanted == null || text.toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT));
    }

    private static String classOf(@Nullable String actor, Category category) {
        return switch (category) {
            case TERRAIN -> "TerrainInfo";
            case CSG -> "BSP surface";
            case STATIC_MESH -> actor == null ? "StaticMeshActor" : classOf(shortName(actor));
        };
    }
}
