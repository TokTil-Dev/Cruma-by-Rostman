package com.rost.cruma.core.npc;

import com.rost.cruma.core.texture.DxtMipCodec;
import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import com.rost.cruma.core.unreal.UMaterial.UTexture;
import com.rost.cruma.core.unreal.UPackage;
import com.rost.cruma.core.unreal.UPackageLoader;
import com.rost.cruma.core.world.Entity.TextureFormat;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class ClientIcons {
    private final UPackageLoader packages;
    private final Map<String, Optional<UPackage>> opened = new HashMap<>();

    public ClientIcons(Path client) {
        this.packages = new UPackageLoader(
                client, List.of(new USearchConfig("SysTextures", "utx"), new USearchConfig("Textures", "utx")));
    }

    public record Icon(int width, int height, byte[] rgba) {}

    public record Texture(String name, int width, int height) {}

    public static @Nullable Icon decodeIcon(UTexture texture) {
        var mips = texture.getMips();
        if (mips == null || mips.length == 0 || mips[0].getData().length == 0) {
            return null;
        }
        var width = texture.getUSize();
        var height = texture.getVSize();
        var data = mips[0].getData();
        var pixels = width * height;
        return switch (texture.getFormat()) {
            case TEXF_DXT1 ->
                new Icon(width, height, DxtMipCodec.decodeToRgba(TextureFormat.TEXTURE_DXT1, width, height, data));
            case TEXF_DXT3 ->
                new Icon(width, height, DxtMipCodec.decodeToRgba(TextureFormat.TEXTURE_DXT3, width, height, data));
            case TEXF_DXT5 ->
                new Icon(width, height, DxtMipCodec.decodeToRgba(TextureFormat.TEXTURE_DXT5, width, height, data));
            case TEXF_RGBA8 -> {
                var rgba = new byte[pixels * 4];
                for (var i = 0; i < pixels; i++) {
                    rgba[i * 4] = data[i * 4 + 2];
                    rgba[i * 4 + 1] = data[i * 4 + 1];
                    rgba[i * 4 + 2] = data[i * 4];
                    rgba[i * 4 + 3] = data[i * 4 + 3];
                }
                yield new Icon(width, height, rgba);
            }
            case TEXF_P8 -> {
                var palette = texture.getPalette().get();
                if (palette == null) {
                    yield null;
                }
                var colors = palette.getColors();
                var rgba = new byte[pixels * 4];
                for (var i = 0; i < pixels; i++) {
                    var index = data[i] & 0xff;
                    if (texture.isMasked() && index == 0) {
                        continue;
                    }
                    var color = colors[index];
                    rgba[i * 4] = color.r();
                    rgba[i * 4 + 1] = color.g();
                    rgba[i * 4 + 2] = color.b();
                    rgba[i * 4 + 3] = texture.isAlphaTexture() ? color.a() : (byte) 255;
                }
                yield new Icon(width, height, rgba);
            }
            default -> null;
        };
    }

    public List<Texture> loadTextures(String packageName) {
        var textures = new ArrayList<Texture>();
        try {
            var uPackage = opened.computeIfAbsent(
                    packageName.toLowerCase(Locale.ROOT), unused -> packages.loadPackageIfPresent(packageName));
            if (uPackage.isEmpty()) {
                return textures;
            }
            for (var export : uPackage.get().getArchive().getExportMap()) {
                if ("Texture".equals(export.getClassName())
                        && uPackage.get().findObject("Texture", export.getObjectName()) instanceof UTexture texture) {
                    textures.add(new Texture(export.getObjectName(), texture.getUSize(), texture.getVSize()));
                }
            }
        } catch (RuntimeException failure) {
            LogManager.getLogger(ClientIcons.class)
                    .debug("The textures of {} failed: {}", packageName, failure.toString());
        }
        textures.sort(Comparator.comparing(Texture::name, String.CASE_INSENSITIVE_ORDER));
        return textures;
    }

    public @Nullable Icon load(String reference) {
        var name = reference.strip();
        var dot = name.indexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return null;
        }
        var packageName = name.substring(0, dot);
        var objectName = name.substring(name.lastIndexOf('.') + 1);
        try {
            var uPackage = opened.computeIfAbsent(
                    packageName.toLowerCase(Locale.ROOT), unused -> packages.loadPackageIfPresent(packageName));
            if (uPackage.isEmpty()) {
                return null;
            }
            var object = uPackage.get().findObject("Texture", objectName);
            return object instanceof UTexture texture ? decodeIcon(texture) : null;
        } catch (RuntimeException failure) {
            LogManager.getLogger(ClientIcons.class).debug("Icon {} failed: {}", reference, failure.toString());
            return null;
        }
    }
}
