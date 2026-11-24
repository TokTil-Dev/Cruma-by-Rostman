package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UObjectRef.Optional;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.List;

public final class UPolys extends UObject {
    private List<Polygon> polygons = List.of();

    public UPolys(UArchive archive) {
        super(archive);
    }

    @Override
    public void deserialize() {
        super.deserialize();
        var count = archive.readInt();
        archive.readInt();
        if (count < 0 || count > archive.getRemainingInObject() / 58) {
            throw new IllegalStateException("Invalid Polys count " + count + " in " + getFullName());
        }
        var values = new ArrayList<Polygon>(count);
        for (var i = 0; i < count; i++) {
            values.add(Polygon.read(archive));
        }
        polygons = List.copyOf(values);
    }

    public List<Polygon> getPolygons() {
        return polygons;
    }

    public record Polygon(
            Vec3 base,
            Vec3 normal,
            Vec3 textureU,
            Vec3 textureV,
            List<Vec3> vertices,
            long flags,
            UObjectRef<UObject, Optional> actor,
            UObjectRef<UMaterial, Optional> material,
            String itemName,
            int link,
            int brushPolygonIndex,
            float lightMapScale,
            int licenseTailIndex) {
        public static Polygon read(UArchive archive) {
            var count = archive.readIndex();
            if (count < 0 || count > 16) {
                throw new IllegalStateException("Invalid native FPoly vertex count " + count);
            }
            var base = archive.readVector();
            var normal = archive.readVector();
            var textureU = archive.readVector();
            var textureV = archive.readVector();
            var vertices = new ArrayList<Vec3>(count);
            for (var i = 0; i < count; i++) {
                vertices.add(archive.readVector());
            }
            var flags = archive.readUInt32();
            var actor = UObjectRef.<UObject>optional();
            actor.fromArchive(archive);
            var material = UObjectRef.<UMaterial>optional();
            material.fromArchive(archive);
            var itemName = archive.readName();
            var link = archive.readIndex();
            var brushPolygon = archive.readIndex();
            var version = archive.getHeader().getFileVersion();
            if (version < 78) {
                var panU = archive.readInt16();
                var panV = archive.readInt16();
                var inverseU = 1f
                        / (textureU.getX() * textureU.getX()
                                + textureU.getY() * textureU.getY()
                                + textureU.getZ() * textureU.getZ());
                var inverseV = 1f
                        / (textureV.getX() * textureV.getX()
                                + textureV.getY() * textureV.getY()
                                + textureV.getZ() * textureV.getZ());
                for (var component = 0; component < 3; component++) {
                    var value = base.get(component) - textureU.get(component) * inverseU * panU;
                    base.set(component, value - inverseV * textureV.get(component) * panV);
                }
            }
            var lightMapScale = version >= 106 ? archive.readFloat() : 32f;
            var licenseTailIndex = archive.getHeader().getLicenseVersion() > 21 ? archive.readInt() : -1;
            return new Polygon(
                    base,
                    normal,
                    textureU,
                    textureV,
                    List.copyOf(vertices),
                    flags,
                    actor,
                    material,
                    itemName,
                    link,
                    brushPolygon,
                    lightMapScale,
                    licenseTailIndex);
        }
    }
}
