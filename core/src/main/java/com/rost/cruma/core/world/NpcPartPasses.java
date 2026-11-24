package com.rost.cruma.core.world;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public record NpcPartPasses(
        AuthoredMaterialPassProgram program, @Nullable AuthoredFramebuffer framebuffer) {
    public NpcPartPasses {
        Objects.requireNonNull(program);
    }
}
