package com.rost.cruma.core.unreal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class MapPawnClasses {
    public static final String PAWN_CLASS = "LineageWarrior.LineagePawn";

    private static final int MOST_PARENTS = 8;

    private MapPawnClasses() {}

    public static @Nullable List<Integer> findChain(UArchive archive, int classReference) {
        var chain = new ArrayList<Integer>();
        var at = classReference;
        while (at > 0 && chain.size() < MOST_PARENTS) {
            if (at > archive.getExportMap().size()) {
                return null;
            }
            var export = archive.getExportMap().get(at - 1);
            if (!UArchive.NONE_NAME.equals(export.getClassName()) || chain.contains(at)) {
                return null;
            }
            chain.add(at);
            at = readSuperField(archive, at);
        }
        return at < 0 && isPawnClass(archive, at) ? List.copyOf(chain) : null;
    }

    public static List<UProperty> getDefaults(UArchive archive, int classIndex) {
        var defaults = EffectClasses.defaults(archive, classIndex);
        return defaults == null ? List.of() : defaults;
    }

    public static Set<String> getVariables(UArchive archive, List<Integer> chain) {
        var names = new HashSet<String>();
        var exports = archive.getExportMap();
        for (var export : exports) {
            if (chain.contains(export.getPackageIndex())
                    && export.getClassName().endsWith("Property")) {
                names.add(export.getObjectName().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static int readSuperField(UArchive archive, int classIndex) {
        var export = archive.getExportMap().get(classIndex - 1);
        var saved = archive.getObjectEnd();
        try {
            archive.setPosition(export.getSerialOffset());
            archive.setObjectEnd(export.getSerialOffset() + export.getSerialSize());
            return archive.readIndex();
        } catch (RuntimeException unreadable) {
            return 0;
        } finally {
            archive.setObjectEnd(saved);
        }
    }

    private static boolean isPawnClass(UArchive archive, int reference) {
        var imports = archive.getImportMap();
        if (-reference > imports.size()) {
            return false;
        }
        var imported = imports.get(-reference - 1);
        var outer = imported.getPackageIndex();
        if (outer >= 0 || -outer > imports.size()) {
            return false;
        }
        var path = imports.get(-outer - 1).getObjectName() + "." + imported.getObjectName();
        return path.equalsIgnoreCase(PAWN_CLASS) && "Class".equalsIgnoreCase(imported.getClassName());
    }
}
