package com.rost.cruma.viewer.launch;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.Platform;

public final class OwnProgram {
    private static final String MAIN_CLASS = "com.rost.cruma.viewer.Cruma";
    private static final String FIRST_THREAD = "-XstartOnFirstThread";
    private static final List<String> LEFT_OUT =
            List.of("-agentlib:", "-agentpath:", "-javaagent:", "-Xrunjdwp", "-Djava.io.tmpdir=");

    private OwnProgram() {}

    public static List<String> command(List<String> arguments) {
        return command(
                System.getProperty("jpackage.app-path"),
                Path.of(System.getProperty("java.home")),
                System.getProperty("java.class.path"),
                ManagementFactory.getRuntimeMXBean().getInputArguments(),
                Platform.get(),
                arguments);
    }

    public static String recordingOptions(@Nullable String present, Path folder) {
        var options = "\"-Djava.io.tmpdir=" + folder + "\" -Dstdout.encoding=UTF-8";
        return present == null || present.isBlank() ? options : present + " " + options;
    }

    static List<String> command(
            @Nullable String appPath,
            Path javaHome,
            String classPath,
            List<String> options,
            Platform platform,
            List<String> arguments) {
        var command = new ArrayList<String>();
        if (appPath != null && !appPath.isBlank()) {
            command.add(appPath);
        } else {
            command.add(javaHome.resolve("bin").resolve("java").toString());
            if (platform == Platform.MACOSX && !options.contains(FIRST_THREAD)) {
                command.add(FIRST_THREAD);
            }
            for (var option : options) {
                if (LEFT_OUT.stream().noneMatch(option::startsWith)) {
                    command.add(option);
                }
            }
            command.add("-cp");
            command.add(classPath);
            command.add(MAIN_CLASS);
        }
        command.addAll(arguments);
        return command;
    }
}
