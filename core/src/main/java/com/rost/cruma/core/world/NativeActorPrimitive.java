package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UActor;
import com.rost.cruma.core.unreal.UActor.BrushActor;

public final class NativeActorPrimitive {
    private NativeActorPrimitive() {}

    public enum Source {
        MESH,
        STATIC_MESH,
        ANTI_PORTAL,
        BRUSH,
        DEFAULT,
        NONE
    }

    public record References(
            boolean brushActor,
            boolean mesh,
            boolean staticMesh,
            boolean antiPortal,
            boolean brush,
            boolean engineDefault) {}

    public static Source initialSource(UActor actor, boolean engineDefaultPresent) {
        var brushActor = actor instanceof BrushActor;
        return initialSource(new References(
                brushActor,
                !actor.getMesh().isNull(),
                !actor.getStaticMesh().isNull(),
                !actor.getAntiPortal().isNull(),
                brushActor && !((BrushActor) actor).getBrush().isNull(),
                engineDefaultPresent));
    }

    public static Source initialSource(References references) {
        if (references.brushActor()) {
            if (references.brush()) {
                return Source.BRUSH;
            }
        } else {
            if (references.mesh()) {
                return Source.MESH;
            }
            if (references.staticMesh()) {
                return Source.STATIC_MESH;
            }
            if (references.antiPortal()) {
                return Source.ANTI_PORTAL;
            }
        }
        return references.engineDefault() ? Source.DEFAULT : Source.NONE;
    }
}
