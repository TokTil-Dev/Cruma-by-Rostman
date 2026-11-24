package com.rost.cruma.core.unreal;

import java.nio.file.Path;

public final class ClientFiles {
    private ClientFiles() {}

    public static boolean isAppleDouble(Path file) {
        var name = file.getFileName();
        return name != null && name.toString().startsWith("._");
    }
}
