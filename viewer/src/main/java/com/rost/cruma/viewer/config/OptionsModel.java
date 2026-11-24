package com.rost.cruma.viewer.config;

import com.rost.cruma.core.world.MapEventState;
import com.rost.cruma.core.world.MapEventState.Clock;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.config.Config.Capture.ImageFormat;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigRenderOptions;
import com.typesafe.config.ConfigUtil;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueFactory;
import com.typesafe.config.parser.ConfigDocumentFactory;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class OptionsModel {
    public static final String SCREENSHOT_FOLDER = "interface.screenshot_folder";
    private static final String CAMERA_START = "viewer.camera.start";
    private static final String LOCATIONS = "viewer.camera.locations";
    private static final String WORLD_MARK = "@";

    private final ConfigLoader loader;
    private final Path file;
    private final String original;
    private final com.typesafe.config.Config originalSettings;
    private final com.typesafe.config.Config effective;
    private final @Nullable Path selectedClient;
    private final Map<String, ConfigValue> changes = new LinkedHashMap<>();
    private @Nullable List<WorldVariant> selectedClientWorlds;
    private final Map<String, String> worldFolders = new HashMap<>();

    public OptionsModel(ConfigLoader loader) throws IOException {
        this(loader, null, true);
    }

    public OptionsModel(ConfigLoader loader, @Nullable Path selectedClient) throws IOException {
        this(loader, selectedClient, false);
    }

    private OptionsModel(ConfigLoader loader, @Nullable Path selectedClient, boolean configuredClient)
            throws IOException {
        this.loader = loader;
        loader.getEffectiveSettings();
        file = loader.getConfigDirectory().resolve(ConfigLoader.USER_FILE);
        original = Files.readString(file);
        originalSettings = loader.loadUserSettingsFrom(original);
        var snapshot = loader.loadSettingsFrom(originalSettings);
        if (configuredClient) {
            selectedClient = Path.of(snapshot.getString("viewer.world.client_path"));
        }
        this.selectedClient =
                selectedClient == null || selectedClient.toString().isBlank() ? null : selectedClient;
        effective = ClientProfiles.apply(snapshot, this.selectedClient, loader.getConfigDirectory());
        if (this.selectedClient != null) {
            var base = loader.getConfigDirectory();
            for (var world : WorldVariant.values()) {
                worldFolders.put(
                        ClientProfiles.GEODATA_PATH + WORLD_MARK + world.getKey(),
                        ClientProfiles.geodataPath(effective, this.selectedClient, base, world));
                worldFolders.put(
                        ClientProfiles.SCRIPT_PATH + WORLD_MARK + world.getKey(),
                        ClientProfiles.scriptPath(effective, this.selectedClient, base, world));
            }
        }
    }

    public record Field(String path, ConfigValue value, List<String> description) {}

    public @Nullable Path getSelectedClient() {
        return selectedClient;
    }

    public List<Field> getFields() {
        var fields = new ArrayList<Field>();
        collect(effective.root(), "", fields);
        fields.removeIf(field ->
                field.path().equals(ClientProfiles.LIST) || field.path().startsWith(ClientProfiles.LIST + "."));
        fields.sort(Comparator.comparing(Field::path));
        return List.copyOf(fields);
    }

    public void set(String path, Object value) {
        setValue(path, ConfigValueFactory.fromAnyRef(value));
    }

    public void setValue(String path, ConfigValue value) {
        if (path.matches("(?:viewer|capture)\\.world\\.(?:min|max)_[xy]_tile")) {
            double raw = ConfigFactory.empty().withValue("tile", value).getDouble("tile");
            if (!Double.isFinite(raw) || raw != Math.rint(raw) || raw < Integer.MIN_VALUE || raw > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Enter a whole tile number.");
            }
            int number = (int) raw;
            boolean minimum = path.contains(".min_");
            String other = path.replace(minimum ? ".min_" : ".max_", minimum ? ".max_" : ".min_");
            int bound = ((Number) getValue(other).unwrapped()).intValue();
            if (minimum ? number > bound : number < bound) {
                throw new IllegalArgumentException((minimum ? "Minimum must be at most " : "Maximum must be at least ")
                        + bound + ". Change the other bound first to move the range.");
            }
        }
        recordChange(path, value);
        if (path.equals("capture.output.radar.format")) {
            var format = ImageFormat.parse(value.unwrapped().toString());
            var filename =
                    Path.of(getValue("capture.output.radar.file").unwrapped().toString());
            recordChange(
                    "capture.output.radar.file",
                    ConfigValueFactory.fromAnyRef(format.getImagePath(filename).toString()));
        }
    }

    public void resetToDefaults(Collection<String> paths) {
        var defaults =
                ConfigFactory.parseResources(ConfigLoader.DEFAULTS_RESOURCE).resolve();
        for (var path : paths) {
            if (!defaults.hasPath(path)) {
                continue;
            }
            if (path.equals(CAMERA_START) && !isKnownStart(defaults.getString(path))) {
                continue;
            }
            recordChange(path, defaults.getValue(path));
        }
    }

    public ConfigValue getValue(String path) {
        var changed = changes.get(changeKey(path));
        return changed != null ? changed : storedValue(path);
    }

    public Path getCaptureFolder() {
        if (selectedClient == null) {
            return configuredCaptureFolder();
        }
        var folder = UserPaths.resolve(
                getValue("capture.output.directory").unwrapped().toString(), loader.getConfigDirectory());
        return Boolean.TRUE.equals(getValue("capture.output.client_subfolder").unwrapped())
                ? folder.resolve(UserPaths.clientFolder(selectedClient, getVariant()))
                : folder;
    }

    public Path getScreenshotFolder() {
        var typed = getValue(SCREENSHOT_FOLDER).unwrapped().toString();
        return Config.screenshotFolder(
                typed.isBlank() ? null : UserPaths.resolve(typed, loader.getConfigDirectory()),
                configuredCaptureFolder());
    }

    public Path getExportFolder() {
        var typed = getValue(ExportSettings.FOLDER).unwrapped().toString();
        return typed.isBlank()
                ? ExportSettings.defaultFolder(Path.of(System.getProperty("user.home")))
                : UserPaths.resolve(typed, loader.getConfigDirectory());
    }

    public Path getScriptsFolder() {
        var typed = getValue(ExportSettings.SCRIPTS_FOLDER).unwrapped().toString();
        return typed.isBlank()
                ? getExportFolder().resolve(ExportSettings.SCRIPTS_NAME)
                : UserPaths.resolve(typed, loader.getConfigDirectory());
    }

    public WorldVariant getVariant() {
        return WorldVariant.parse(getValue(ClientProfiles.VARIANT).unwrapped().toString());
    }

    public List<WorldVariant> getSelectedClientWorlds() {
        if (selectedClientWorlds == null) {
            try {
                selectedClientWorlds =
                        selectedClient == null ? List.of(WorldVariant.MAIN) : WorldVariant.findWorlds(selectedClient);
            } catch (RuntimeException unreadable) {
                selectedClientWorlds = List.of(WorldVariant.MAIN);
            }
        }
        return selectedClientWorlds;
    }

    public CaptureRadarLayout getCaptureRadarLayout() {
        return CaptureRadarLayout.forBounds(
                intValue("capture.output.radar.tile_resolution"),
                intValue("capture.world.min_x_tile"),
                intValue("capture.world.max_x_tile"),
                intValue("capture.world.min_y_tile"),
                intValue("capture.world.max_y_tile"));
    }

    public void updateLocation(int index, String name, List<Double> position) {
        var locations = new ArrayList<>((ConfigList) getValue("viewer.camera.locations"));
        validateLocation(locations, index, name, position);
        var previous = ((ConfigObject) locations.get(index)).toConfig().getString("name");
        locations.set(index, ConfigValueFactory.fromMap(Map.of("name", name, "position", position)));
        setValue("viewer.camera.locations", ConfigValueFactory.fromIterable(locations));
        if (getValue("viewer.camera.start").unwrapped().toString().equalsIgnoreCase(previous)) {
            set("viewer.camera.start", name);
        }
    }

    public void addLocation(String name, List<Double> position) {
        var locations = new ArrayList<>((ConfigList) getValue("viewer.camera.locations"));
        name = name.strip();
        validateLocation(locations, -1, name, position);
        locations.add(ConfigValueFactory.fromMap(Map.of("name", name, "position", position)));
        setValue("viewer.camera.locations", ConfigValueFactory.fromIterable(locations));
    }

    public void removeLocation(int index) {
        var locations = new ArrayList<>((ConfigList) getValue("viewer.camera.locations"));
        var removed = ((ConfigObject) locations.remove(index)).toConfig();
        if (getValue("viewer.camera.start").unwrapped().toString().equalsIgnoreCase(removed.getString("name"))) {
            var p = removed.getDoubleList("position");
            set("viewer.camera.start", p.get(0) + ", " + p.get(1) + ", " + p.get(2));
        }
        setValue("viewer.camera.locations", ConfigValueFactory.fromIterable(locations));
    }

    public int getChangeCount() {
        return changes.size();
    }

    public boolean hasChanges() {
        return !changes.isEmpty();
    }

    public String validate() {
        try {
            validatedDocument();
            return "";
        } catch (RuntimeException failure) {
            return failure.getMessage() == null ? "Invalid settings" : failure.getMessage();
        }
    }

    public void save() throws IOException {
        String contents;
        try {
            contents = validatedDocument();
        } catch (RuntimeException failure) {
            throw new ConfigurationProblem(
                    failure.getMessage() == null ? "Invalid settings" : failure.getMessage(), failure);
        }
        if (!Files.readString(file).equals(original)) {
            throw new ConfigurationProblem(
                    "The settings changed outside this window. Close it and open it again before saving.");
        }
        var temporary = Files.createTempFile(file.getParent(), ".options-", ".tmp");
        try {
            Files.writeString(temporary, contents);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            loader.reload();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path configuredCaptureFolder() {
        var folder = UserPaths.resolve(
                getValue("capture.output.directory").unwrapped().toString(), loader.getConfigDirectory());
        var client = getValue("capture.world.client_path").unwrapped().toString();
        return Boolean.TRUE.equals(getValue("capture.output.client_subfolder").unwrapped()) && !client.isBlank()
                ? folder.resolve(UserPaths.clientFolder(Path.of(client), getVariant()))
                : folder;
    }

    private void recordChange(String path, ConfigValue value) {
        var key = changeKey(path);
        if ((isWorldFolder(path) || effective.hasPathOrNull(path))
                && storedValue(path).equals(value)) {
            changes.remove(key);
        } else {
            changes.put(key, value);
        }
    }

    private boolean isWorldFolder(String path) {
        return selectedClient != null
                && (path.equals(ClientProfiles.GEODATA_PATH) || path.equals(ClientProfiles.SCRIPT_PATH));
    }

    private String changeKey(String path) {
        return isWorldFolder(path) && getVariant() != WorldVariant.MAIN
                ? path + WORLD_MARK + getVariant().getKey()
                : path;
    }

    private ConfigValue storedValue(String path) {
        if (!isWorldFolder(path)) {
            return effective.getValue(path);
        }
        return ConfigValueFactory.fromAnyRef(
                worldFolders.get(path + WORLD_MARK + getVariant().getKey()));
    }

    private boolean isKnownStart(String start) {
        for (var location : (ConfigList) getValue(LOCATIONS)) {
            if (((ConfigObject) location).toConfig().getString("name").equalsIgnoreCase(start)) {
                return true;
            }
        }
        var parts = start.trim().split("[\\s,]+");
        if (parts.length != 3) {
            return false;
        }
        try {
            for (var part : parts) {
                Float.parseFloat(part);
            }
            return true;
        } catch (NumberFormatException notNumbers) {
            return false;
        }
    }

    private int intValue(String path) {
        return ((Number) getValue(path).unwrapped()).intValue();
    }

    private com.typesafe.config.Config candidate() {
        var result = originalSettings;
        for (var entry : persistenceChanges().entrySet()) {
            result = result.withValue(entry.getKey(), entry.getValue());
        }
        return ClientProfiles.apply(loader.loadSettingsFrom(result), selectedClient, loader.getConfigDirectory());
    }

    private String validatedDocument() {
        var candidate = candidate();
        Config.parseConfig(candidate, loader.getConfigDirectory());
        var updates = persistenceChanges();
        var options = ConfigRenderOptions.concise().setJson(true);
        try {
            var document = ConfigDocumentFactory.parseString(original);
            if (document.render().equals(original)) {
                for (var entry : updates.entrySet()) {
                    document = document.withValueText(
                            entry.getKey(), entry.getValue().render(options));
                }
                var rendered = document.render();
                validateDocument(rendered, candidate, updates, false);
                return rendered;
            }
        } catch (RuntimeException unsupportedDocument) {
        }
        var overrides = new StringBuilder("\n");
        for (var entry : updates.entrySet()) {
            var path = ConfigUtil.joinPath(ConfigUtil.splitPath(entry.getKey()));
            if (entry.getValue() instanceof ConfigObject) {
                overrides.append(path).append(" = null\n");
            }
            overrides
                    .append(path)
                    .append(" = ")
                    .append(entry.getValue().render(options))
                    .append('\n');
        }
        var rendered = original + overrides;
        RuntimeException appendFailure;
        try {
            validateDocument(rendered, candidate, updates, false);
            return rendered;
        } catch (RuntimeException failure) {
            appendFailure = failure;
        }
        for (var position = original.lastIndexOf('}');
                position >= 0;
                position = original.lastIndexOf('}', position - 1)) {
            rendered = original.substring(0, position) + overrides + original.substring(position);
            try {
                validateDocument(rendered, candidate, updates, true);
                return rendered;
            } catch (RuntimeException invalidPosition) {
            }
        }
        throw appendFailure;
    }

    private void validateDocument(
            String text, com.typesafe.config.Config expected, Map<String, ConfigValue> updates, boolean entireConfig) {
        var actual = ClientProfiles.apply(loader.loadSettingsFrom(text), selectedClient, loader.getConfigDirectory());
        Config.parseConfig(actual, loader.getConfigDirectory());
        for (var path : updates.keySet()) {
            if (!actual.getValue(path).equals(expected.getValue(path))) {
                throw new ConfigurationProblem("Saved setting would differ from the requested value: " + path);
            }
        }
        if (entireConfig && !actual.root().equals(expected.root())) {
            throw new ConfigurationProblem("Cannot preserve the existing configuration while applying these settings.");
        }
    }

    private Map<String, ConfigValue> persistenceChanges() {
        var result = new LinkedHashMap<String, ConfigValue>();
        MapEventState events = null;
        for (var change : changes.entrySet()) {
            var mark = change.getKey().indexOf(WORLD_MARK);
            var world = mark < 0
                    ? WorldVariant.MAIN
                    : WorldVariant.parse(change.getKey().substring(mark + 1));
            var entry = mark < 0 ? change : Map.entry(change.getKey().substring(0, mark), change.getValue());
            if (entry.getKey().equals(ClientProfiles.GEODATA_PATH)) {
                var folder = ConfigFactory.empty()
                        .withValue("folder", entry.getValue())
                        .getString("folder");
                if (selectedClient == null) {
                    if (!folder.isBlank()) {
                        throw new ConfigurationProblem("Choose a game client before saving its geodata folder.");
                    }
                    continue;
                }
                result.put(
                        ClientProfiles.geodataSettingPath(
                                effective, selectedClient, loader.getConfigDirectory(), world),
                        entry.getValue());
                continue;
            }
            if (entry.getKey().equals(ClientProfiles.SCRIPT_PATH)) {
                var folder = ConfigFactory.empty()
                        .withValue("folder", entry.getValue())
                        .getString("folder");
                if (selectedClient == null) {
                    if (!folder.isBlank()) {
                        throw new ConfigurationProblem("Choose a game client before saving its script folder.");
                    }
                    continue;
                }
                result.put(
                        ClientProfiles.scriptSettingPath(effective, selectedClient, loader.getConfigDirectory(), world),
                        entry.getValue());
                continue;
            }
            if (entry.getKey().equals(ClientProfiles.TIME_WINDOWS)
                    || entry.getKey().equals(ClientProfiles.CAPTURE_TIME_WINDOWS)) {
                var clock = Clock.parse(String.valueOf(entry.getValue().unwrapped()));
                if (clock == null) {
                    throw new ConfigurationProblem("Effects by time of day must be clock, always or off.");
                }
                if (selectedClient == null) {
                    if (clock != Clock.FOLLOW) {
                        throw new ConfigurationProblem(
                                "Choose a game client before setting its effects by time of day.");
                    }
                    continue;
                }
                events = (events == null
                                ? ClientProfiles.mapEvents(effective, selectedClient, loader.getConfigDirectory())
                                : events)
                        .withClockForAll(clock);
                continue;
            }
            if (entry.getKey().equals(ClientProfiles.CASTLE_SIDES)
                    || entry.getKey().equals(ClientProfiles.CAPTURE_CASTLE_SIDES)) {
                var side = MapEventChoices.castleSide(
                        String.valueOf(entry.getValue().unwrapped()));
                if (selectedClient == null) {
                    if (side != null && side != MapEventState.BOTH_SIDES) {
                        throw new ConfigurationProblem("Choose a game client before setting its castle sides.");
                    }
                    continue;
                }
                if (side != null) {
                    events = (events == null
                                    ? ClientProfiles.mapEvents(effective, selectedClient, loader.getConfigDirectory())
                                    : events)
                            .withCastleSideForAll(side);
                }
                continue;
            }
            if (entry.getKey().equals(ClientProfiles.VARIANT) && selectedClient != null) {
                result.put(
                        ClientProfiles.variantSettingPath(effective, selectedClient, loader.getConfigDirectory()),
                        entry.getValue());
                continue;
            }
            result.put(entry.getKey(), entry.getValue());
        }
        if (events != null) {
            result.put(
                    ClientProfiles.mapEventsSettingPath(effective, selectedClient, loader.getConfigDirectory()),
                    MapEventChoices.toConfig(events));
        }
        return result;
    }

    private static void collect(ConfigObject object, String prefix, List<Field> fields) {
        for (var entry : object.entrySet()) {
            var path = prefix + entry.getKey();
            if (entry.getValue() instanceof ConfigObject nested && !nested.isEmpty()) {
                collect(nested, path + ".", fields);
            } else {
                fields.add(new Field(
                        path, entry.getValue(), entry.getValue().origin().comments()));
            }
        }
    }

    private static void validateLocation(List<ConfigValue> locations, int index, String name, List<Double> position) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("Give this location a name.");
        }
        for (int i = 0; i < locations.size(); i++) {
            if (i != index
                    && ((ConfigObject) locations.get(i))
                            .toConfig()
                            .getString("name")
                            .equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("A location with this name already exists.");
            }
        }
        if (position.size() != 3 || position.stream().anyMatch(n -> !Double.isFinite(n))) {
            throw new IllegalArgumentException("Enter three finite coordinates.");
        }
    }
}
