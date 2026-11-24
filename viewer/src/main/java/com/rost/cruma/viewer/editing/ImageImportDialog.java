package com.rost.cruma.viewer.editing;

import static com.rost.cruma.viewer.editing.EditorUi.tip;

import com.rost.cruma.core.editing.TexturePackageWriter;
import com.rost.cruma.viewer.ui.GeodataWidgets;
import com.rost.cruma.viewer.ui.HudWidgets;
import com.rost.cruma.viewer.ui.Motion;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.jetbrains.annotations.Nullable;

final class ImageImportDialog {
    static final String POPUP = "Import an image##editingImageImport";

    static final List<String> PATTERNS = List.of("*.png", "*.jpg", "*.jpeg", "*.bmp", "*.dds");

    private final ImString packageName = new ImString("CrumaTextures", 128);
    private final ImString textureName = new ImString(128);
    private @Nullable Path file;
    private @Nullable Path resized;
    private int width;
    private int height;
    private String problem = "";
    private String target = "";
    private List<String> actors = List.of();
    private int slot;
    private boolean opening;

    record Request(Path image, String packageName, String textureName, List<String> actors, int slot) {}

    static String textureName(Path image) {
        return TexturePackageWriter.textureName(image);
    }

    static int nearestPowerOfTwo(int size) {
        if (size <= 1) {
            return 1;
        }
        var lower = Integer.highestOneBit(size);
        var upper = lower << 1;
        var nearest = size - lower < upper - size ? lower : upper;
        return Math.min(4096, nearest);
    }

    static boolean isPowerOfTwo(int size) {
        return size > 0 && size <= 4096 && Integer.bitCount(size) == 1;
    }

    static int[] imageSize(Path image) throws IOException {
        if (image.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dds")) {
            var header = new byte[20];
            try (var input = Files.newInputStream(image)) {
                if (input.readNBytes(header, 0, header.length) < header.length) {
                    throw new IOException("The DDS header is cut short.");
                }
            }
            var buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.getInt(0) != 0x20534444) {
                throw new IOException("The file is not a DDS image.");
            }
            return new int[] {buffer.getInt(16), buffer.getInt(12)};
        }
        var read = ImageIO.read(image.toFile());
        if (read == null) {
            throw new IOException("The file is not a PNG, JPEG or BMP image.");
        }
        return new int[] {read.getWidth(), read.getHeight()};
    }

    static Path resize(Path image, int width, int height) throws IOException {
        var source = ImageIO.read(image.toFile());
        if (source == null) {
            throw new IOException("The file is not a PNG, JPEG or BMP image.");
        }
        var target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        var directory = Files.createTempDirectory("cruma-image-");
        var output = directory.resolve(textureName(image) + ".png");
        if (!ImageIO.write(target, "png", output.toFile())) {
            throw new IOException("PNG encoding is unavailable.");
        }
        directory.toFile().deleteOnExit();
        output.toFile().deleteOnExit();
        return output;
    }

    void open(Path image, List<String> targets, int targetSlot, String description) {
        file = image;
        resized = null;
        actors = List.copyOf(targets);
        slot = targetSlot;
        target = description;
        textureName.set(textureName(image));
        problem = "";
        try {
            var size = imageSize(image);
            width = size[0];
            height = size[1];
        } catch (IOException unreadable) {
            width = height = 0;
            problem = "Cruma cannot read this image: " + unreadable.getMessage();
        }
        opening = true;
    }

    boolean isOpen() {
        return file != null;
    }

    @Nullable
    Request draw() {
        if (opening) {
            opening = false;
            ImGui.openPopup(POPUP);
        }
        var io = ImGui.getIO();
        ImGui.setNextWindowPos(io.getDisplaySizeX() / 2, io.getDisplaySizeY() / 2, ImGuiCond.Always, .5f, .5f);
        ImGui.setNextWindowSize(Math.max(300, Math.min(440, io.getDisplaySizeX() - 32)), 0);
        if (!ImGui.beginPopupModal(
                POPUP,
                ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.AlwaysAutoResize)) {
            if (file != null && !opening) {
                file = null;
            }
            return null;
        }
        Motion.dialog();
        var image = file;
        if (image == null) {
            ImGui.closeCurrentPopup();
            ImGui.endPopup();
            return null;
        }
        GeodataWidgets.value("File", image.getFileName().toString());
        tip(image.toString());
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.value("For", target);
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var column = GeodataWidgets.columnWidth(2);
        GeodataWidgets.columns(
                () -> GeodataWidgets.compactText("Package", "##importPackage", "CrumaTextures", packageName, column),
                () -> GeodataWidgets.compactText("Texture", "##importTexture", "Texture name", textureName, column));
        tip("The new .utx package that holds the texture, kept for this session, and the texture's name in it."
                + " Choose a package name the client does not have.");
        var size = resized != null
                ? new int[] {nearestPowerOfTwo(width), nearestPowerOfTwo(height)}
                : new int[] {width, height};
        var square = isPowerOfTwo(size[0]) && isPowerOfTwo(size[1]);
        if (!problem.isEmpty()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.note(problem, GeodataWidgets.BLOCKED);
        } else if (resized != null) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.note(
                    "Resized from "
                            + width
                            + " × "
                            + height
                            + " to "
                            + size[0]
                            + " × "
                            + size[1]
                            + " with bilinear filtering.",
                    GeodataWidgets.OPEN);
        } else if (!square) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.note(
                    width + " × " + height
                            + " is not a power of two on each side, as the client needs, such as 256 × 512.",
                    GeodataWidgets.WARNING);
            var dds = image.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dds");
            GeodataWidgets.space(GeodataWidgets.ROW);
            ImGui.beginDisabled(dds || width <= 0);
            if (GeodataWidgets.actionButton(
                    "importResize",
                    null,
                    "Resize to " + nearestPowerOfTwo(width) + " × " + nearestPowerOfTwo(height),
                    -1,
                    false)) {
                try {
                    resized = resize(image, nearestPowerOfTwo(width), nearestPowerOfTwo(height));
                } catch (IOException failed) {
                    problem = "The image could not be resized: " + failed.getMessage();
                }
            }
            ImGui.endDisabled();
            tip(
                    dds
                            ? "A DDS file keeps its compressed levels, so it cannot be resized here. Save it at a"
                                    + " power-of-two size."
                            : "Scale the image to the nearest power of two on each side, with bilinear filtering,"
                                    + " before it is imported. The file stays as it is.");
        }
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var half = GeodataWidgets.columnWidth(2);
        var cancel = HudWidgets.button("importCancel", null, "Cancel", half, EditorUi.BUTTON_HEIGHT, false, true);
        ImGui.sameLine();
        var ready = problem.isEmpty()
                && (square || resized != null)
                && !packageName.get().isBlank()
                && !textureName.get().isBlank();
        ImGui.beginDisabled(!ready);
        var accepted = HudWidgets.button("importAccept", null, "Import", half, EditorUi.BUTTON_HEIGHT, true, true);
        ImGui.endDisabled();
        tip(
                ready
                        ? "Make a new package with this texture and give it to " + target
                                + ", as one Undo step. The package goes into the export."
                        : "Choose an image with power-of-two sides, a package and a texture name.");
        Request request = null;
        if (accepted) {
            request = new Request(
                    resized != null ? resized : image,
                    packageName.get().strip(),
                    textureName.get().strip(),
                    actors,
                    slot);
        }
        if (accepted || cancel || ImGui.isKeyPressed(ImGuiKey.Escape) || GeodataWidgets.wasClickedOutside()) {
            file = null;
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
        return request;
    }
}
