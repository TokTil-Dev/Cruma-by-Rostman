package com.rost.cruma.viewer.launch;

import java.nio.file.Path;
import org.jetbrains.annotations.Nullable;

public record MenuStart(Path client, String tiles, @Nullable String place) {}
