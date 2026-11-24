package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import java.nio.file.Path;
import java.util.List;
import org.apache.logging.log4j.LogManager;

public enum ModelLightRule {
    INTERLUDE(false, false),
    HIGH_FIVE(true, false),
    BEAST(true, true);

    private static final int LICENSEE_FROM_GRACIA_FINAL = 37;

    private final boolean sunScale;
    private final boolean beastScale;

    ModelLightRule(boolean sunScale, boolean beastScale) {
        this.sunScale = sunScale;
        this.beastScale = beastScale;
    }

    public static ModelLightRule detect(Path clientRoot, MaterialClient material) {
        if (material != MaterialClient.UNKNOWN) {
            return BEAST;
        }
        var loader = new UArchiveLoader(clientRoot, List.of(new USearchConfig("System", "u")));
        try {
            var engine = loader.loadArchive("Engine");
            return engine == null ? INTERLUDE : of(engine.getHeader().getLicenseVersion());
        } catch (RuntimeException unreadable) {
            LogManager.getLogger(ModelLightRule.class).debug("No licensee in Engine.u: {}", unreadable.toString());
            return INTERLUDE;
        } finally {
            loader.clearArchives();
        }
    }

    static ModelLightRule of(int engineLicensee) {
        return engineLicensee >= LICENSEE_FROM_GRACIA_FINAL ? HIGH_FIVE : INTERLUDE;
    }

    public boolean isSunScaled() {
        return sunScale;
    }

    public boolean isBeastScaled() {
        return beastScale;
    }
}
