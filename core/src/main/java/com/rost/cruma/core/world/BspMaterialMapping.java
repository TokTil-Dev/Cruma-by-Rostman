package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UMaterial;
import org.jetbrains.annotations.Nullable;

public final class BspMaterialMapping {
    private BspMaterialMapping() {}

    public static int[] getSize(@Nullable UMaterial material) {
        return new int[] {BspRenderGeometry.mappingSize(material, false), BspRenderGeometry.mappingSize(material, true)
        };
    }

    public static int @Nullable [] getSize(UArchiveLoader loader, String path) {
        var dot = path.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        var archive = loader.loadArchiveIfPresent(path.substring(0, dot));
        if (archive == null) {
            return null;
        }
        var objects = archive.getObjectLoader();
        var exports = archive.getExportMap();
        for (var index = 1; index <= exports.size(); index++) {
            var source = objects.peekExportSourcePath(index);
            if (source != null && source.equalsIgnoreCase(path)) {
                return objects.exportObject(exports.get(index - 1)) instanceof UMaterial material
                        ? getSize(material)
                        : null;
            }
        }
        return null;
    }
}
