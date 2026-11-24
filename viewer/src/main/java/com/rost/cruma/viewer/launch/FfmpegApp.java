package com.rost.cruma.viewer.launch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.Platform;

public final class FfmpegApp {
    private static final double BITS_PER_PIXEL = .12;
    private static final long LIST_SECONDS = 20;

    private static volatile @Nullable Path found;
    private static volatile boolean searched;

    private FfmpegApp() {}

    public record Encoder(String name, List<String> options) {
        public Encoder {
            options = List.copyOf(options);
        }
    }

    public record Job(int width, int height, int rate, boolean flip, Path sound, Path output) {}

    public static @Nullable Path find() {
        if (!searched) {
            found = search(
                    Platform.get(),
                    Path.of(System.getProperty("user.home")),
                    System::getenv,
                    candidate -> Files.isRegularFile(candidate) && Files.isExecutable(candidate),
                    FfmpegApp::listByPrefix);
            searched = true;
        }
        return found;
    }

    public static String installHint() {
        return installHint(Platform.get());
    }

    static String installHint(Platform platform) {
        return switch (platform) {
            case MACOSX -> "Install ffmpeg to export a video: brew install ffmpeg";
            case WINDOWS -> "Install ffmpeg to export a video: winget install Gyan.FFmpeg";
            default -> "Install ffmpeg to export a video, such as: sudo apt install ffmpeg";
        };
    }

    public static Encoder encoder(Path ffmpeg, int width, int height, int rate) throws IOException {
        var process = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-encoders")
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();
        var text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!process.waitFor(LIST_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("ffmpeg did not list its encoders");
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new IOException("Stopped while ffmpeg listed its encoders", stopped);
        }
        var encoder = choose(listed(text), Platform.get(), width, height, rate);
        if (encoder == null) {
            throw new IOException("This ffmpeg has no H.264 encoder. " + installHint());
        }
        return encoder;
    }

    static Set<String> listed(String encoders) {
        var names = new LinkedHashSet<String>();
        var started = false;
        for (var line : encoders.split("\\R")) {
            var text = line.strip();
            if (text.startsWith("---")) {
                started = true;
                continue;
            }
            var parts = text.split("\\s+");
            if (started && parts.length >= 2 && parts[0].length() == 6) {
                names.add(parts[1]);
            }
        }
        return names;
    }

    static @Nullable Encoder choose(Set<String> listed, Platform platform, int width, int height, int rate) {
        var bits = Long.toString(Math.round(width * (double) height * rate * BITS_PER_PIXEL));
        for (var candidate : candidates(platform, bits)) {
            if (listed.contains(candidate.name())) {
                return candidate;
            }
        }
        return null;
    }

    public static List<String> arguments(Path ffmpeg, Encoder encoder, Job job) {
        var filter = (job.flip() ? "vflip," : "")
                + "scale=out_color_matrix=bt709:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p,"
                + "setparams=range=tv:color_primaries=bt709:color_trc=bt709:colorspace=bt709";
        var arguments = new ArrayList<>(List.of(
                ffmpeg.toString(),
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-f",
                "rawvideo",
                "-pix_fmt",
                "rgba",
                "-video_size",
                job.width() + "x" + job.height(),
                "-framerate",
                Integer.toString(job.rate()),
                "-i",
                "pipe:0",
                "-i",
                job.sound().toString(),
                "-map",
                "0:v:0",
                "-map",
                "1:a:0",
                "-vf",
                filter,
                "-c:v",
                encoder.name()));
        arguments.addAll(encoder.options());
        arguments.addAll(List.of(
                "-pix_fmt",
                "yuv420p",
                "-colorspace",
                "bt709",
                "-color_primaries",
                "bt709",
                "-color_trc",
                "bt709",
                "-color_range",
                "tv",
                "-c:a",
                "aac",
                "-b:a",
                "192k",
                "-shortest",
                "-movflags",
                "+faststart",
                "-f",
                "mp4",
                job.output().toString()));
        return arguments;
    }

    static @Nullable Path search(
            Platform platform,
            Path home,
            Function<String, @Nullable String> environment,
            Predicate<Path> runnable,
            Function<Path, List<Path>> folders) {
        var name = platform == Platform.WINDOWS ? "ffmpeg.exe" : "ffmpeg";
        var candidates = new ArrayList<Path>();
        switch (platform) {
            case MACOSX -> {
                for (var folder : List.of("/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin")) {
                    candidates.add(Path.of(folder, name));
                }
            }
            case WINDOWS -> {
                var local = environment.apply("LOCALAPPDATA");
                if (local != null) {
                    candidates.add(Path.of(local, "Microsoft", "WinGet", "Links", name));
                    for (var packageFolder : folders.apply(Path.of(local, "Microsoft", "WinGet", "Packages"))) {
                        for (var build : folders.apply(packageFolder)) {
                            candidates.add(build.resolve("bin").resolve(name));
                        }
                    }
                }
                for (var variable : List.of("ProgramFiles", "ProgramFiles(x86)")) {
                    var root = environment.apply(variable);
                    if (root != null) {
                        candidates.add(Path.of(root, "ffmpeg", "bin", name));
                    }
                }
                var chocolatey = environment.apply("ChocolateyInstall");
                if (chocolatey != null) {
                    candidates.add(Path.of(chocolatey, "bin", name));
                }
                candidates.add(home.resolve("scoop").resolve("shims").resolve(name));
            }
            default -> {
                for (var folder : List.of("/usr/bin", "/usr/local/bin", "/snap/bin")) {
                    candidates.add(Path.of(folder, name));
                }
            }
        }
        var pathVariable = environment.apply("PATH");
        if (pathVariable != null) {
            var separator = platform == Platform.WINDOWS ? ";" : ":";
            for (var entry : pathVariable.split(separator)) {
                if (!entry.isBlank()) {
                    candidates.add(Path.of(entry, name));
                }
            }
        }
        for (var candidate : candidates) {
            if (runnable.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Encoder> candidates(Platform platform, String bits) {
        var candidates = new ArrayList<Encoder>();
        candidates.add(new Encoder("libx264", List.of("-preset", "medium", "-crf", "18")));
        switch (platform) {
            case MACOSX -> candidates.add(new Encoder("h264_videotoolbox", List.of("-b:v", bits)));
            case WINDOWS -> {
                candidates.add(nvidia());
                candidates.add(intel());
                candidates.add(new Encoder("h264_amf", List.of("-rc", "cqp", "-qp_i", "20", "-qp_p", "20")));
                candidates.add(new Encoder("h264_mf", List.of("-b:v", bits)));
            }
            default -> {
                candidates.add(nvidia());
                candidates.add(intel());
            }
        }
        candidates.add(new Encoder("libopenh264", List.of("-b:v", bits)));
        return candidates;
    }

    private static Encoder nvidia() {
        return new Encoder("h264_nvenc", List.of("-preset", "p5", "-rc", "vbr", "-cq", "19", "-b:v", "0"));
    }

    private static Encoder intel() {
        return new Encoder("h264_qsv", List.of("-global_quality", "20"));
    }

    private static List<Path> listByPrefix(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (var entries = Files.list(folder)) {
            return entries.filter(entry -> entry.getFileName()
                                    .toString()
                                    .toLowerCase(Locale.ROOT)
                                    .contains("ffmpeg")
                            || Files.isDirectory(entry))
                    .sorted(Comparator.comparing(
                                    (Path entry) -> entry.getFileName().toString())
                            .reversed())
                    .toList();
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
    }
}
