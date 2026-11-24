package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeTerrainTimeLighting.Result;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.TerrainNativeCodec.Sector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TerrainTimeMapCodec {
    public static final int WIDTH = 257;
    public static final int SAMPLES = WIDTH * WIDTH;
    private static final int MAX_FRAMES = 64;
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private final byte[] payload;
    private final List<Frame> frames;

    private TerrainTimeMapCodec(byte[] payload, List<Frame> frames) {
        this.payload = payload;
        this.frames = List.copyOf(frames);
    }

    public record Consistency(long comparedSamples, long mismatchingSamples, int bakedSectors) {}

    private record Frame(float time, int samplesOffset) {}

    public static TerrainTimeMapCodec read(byte[] bytes, List<String> names) throws IOException {
        Objects.requireNonNull(bytes);
        Objects.requireNonNull(names);
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Terrain time-map property exceeds the editor memory bound");
        }
        var payload = bytes.clone();
        var cursor = new Cursor(payload, 0, payload.length);
        var count = cursor.readIndex();
        if (count < 0 || count > MAX_FRAMES) {
            throw new IOException("Unaudited Terrain time-map count");
        }
        var frames = new ArrayList<Frame>(count);
        for (var i = 0; i < count; i++) {
            var seenTime = false;
            var time = 0f;
            var samplesOffset = -1;
            while (true) {
                var nameIndex = cursor.readIndex();
                if (nameIndex < 0 || nameIndex >= names.size()) {
                    throw new IOException("Invalid Terrain time-map property name");
                }
                var name = names.get(nameIndex);
                if (name.equals("None")) {
                    break;
                }
                var info = cursor.readU8();
                var type = info & 15;
                if ((info & 128) != 0 || type != 4 && type != 9) {
                    throw new IOException("Unaudited Terrain time-map property tag");
                }
                var length = switch ((info >>> 4) & 7) {
                    case 0 -> 1;
                    case 1 -> 2;
                    case 2 -> 4;
                    case 3 -> 12;
                    case 4 -> 16;
                    case 5 -> cursor.readU8();
                    case 6 -> cursor.readU16();
                    default -> cursor.readI32();
                };
                if (length < 0 || length > cursor.end - cursor.position) {
                    throw new IOException("Truncated Terrain time-map property");
                }
                var end = cursor.position + length;
                if (name.equals("Time") && type == 4 && length == 4 && !seenTime) {
                    time = Float.intBitsToFloat(cursor.readI32());
                    seenTime = true;
                    if (!Float.isFinite(time) || time < 0 || time >= 24) {
                        throw new IOException("Invalid Terrain time-map time");
                    }
                } else if (name.equals("Intensity") && type == 9 && samplesOffset < 0) {
                    var values = new Cursor(payload, cursor.position, end);
                    if (values.readIndex() != SAMPLES || end - values.position != SAMPLES) {
                        throw new IOException("Unaudited Terrain time-map raster dimensions");
                    }
                    samplesOffset = values.position;
                    cursor.position = end;
                } else {
                    throw new IOException("Unknown or duplicate Terrain time-map property: " + name);
                }
                if (cursor.position != end) {
                    throw new IOException("Terrain time-map property length mismatch");
                }
            }
            if (samplesOffset < 0) {
                throw new IOException("Missing Terrain time-map intensity raster");
            }
            if (!frames.isEmpty() && time <= frames.getLast().time()) {
                throw new IOException("Terrain time-map times are not strictly ordered");
            }
            frames.add(new Frame(time, samplesOffset));
        }
        if (cursor.position != cursor.end) {
            throw new IOException("Trailing Terrain time-map property bytes");
        }
        return new TerrainTimeMapCodec(payload, frames);
    }

    public int getFrameCount() {
        return frames.size();
    }

    public float getTime(int frame) {
        return frames.get(frame).time();
    }

    public int sample(int frame, int x, int y) {
        Objects.checkIndex(x, WIDTH);
        Objects.checkIndex(y, WIDTH);
        return Byte.toUnsignedInt(payload[frames.get(frame).samplesOffset() + y * WIDTH + x]);
    }

    public byte[] getIntensity(int frame) {
        var offset = frames.get(frame).samplesOffset();
        return Arrays.copyOfRange(payload, offset, offset + SAMPLES);
    }

    public byte[] getOriginalBytes() {
        return payload.clone();
    }

    public byte[] replaceIntensities(List<byte[]> intensities) {
        Objects.requireNonNull(intensities);
        if (intensities.size() != frames.size()) {
            throw new IllegalArgumentException("Terrain time-map count changed");
        }
        var result = payload.clone();
        for (var i = 0; i < frames.size(); i++) {
            var values = Objects.requireNonNull(intensities.get(i));
            if (values.length != SAMPLES) {
                throw new IllegalArgumentException("Terrain time-map raster size changed");
            }
            System.arraycopy(values, 0, result, frames.get(i).samplesOffset(), SAMPLES);
        }
        return result;
    }

    public TerrainTimeMapCodec withWindow(Result update) {
        Objects.requireNonNull(update);
        var window = update.window();
        if (window.offsetX() < 1
                || window.offsetY() < 1
                || (long) window.offsetX() + window.width() > WIDTH - 2
                || (long) window.offsetY() + window.height() > WIDTH - 2) {
            throw new IllegalArgumentException("Sunlight border propagation is not qualified");
        }
        if (update.frames().size() != frames.size()) {
            throw new IllegalArgumentException("Terrain sunlight frame count changed");
        }
        var count = window.width() * window.height();
        var result = payload.clone();
        for (var slot = 0; slot < frames.size(); slot++) {
            var frame = update.frames().get(slot);
            if (Float.floatToRawIntBits(frame.time())
                    != Float.floatToRawIntBits(frames.get(slot).time())) {
                throw new IllegalArgumentException("Terrain sunlight time bits changed");
            }
            if (frame.intensities().size() != count || frame.visibility().size() != (count + 7) / 8) {
                throw new IllegalArgumentException("Terrain sunlight window size changed");
            }
            for (var mask : frame.visibility()) {
                if (mask < 0 || mask > 255) {
                    throw new IllegalArgumentException("Invalid terrain sunlight mask byte");
                }
            }
            var remainder = count & 7;
            if (remainder != 0 && frame.visibility().getLast() >>> remainder != 0) {
                throw new IllegalArgumentException("Terrain sunlight mask has unused bits");
            }
            for (var i = 0; i < count; i++) {
                var value = frame.intensities().get(i);
                if (value < 0 || value > 255) {
                    throw new IllegalArgumentException("Invalid terrain sunlight intensity byte");
                }
                if ((frame.visibility().get(i >>> 3) & (1 << (i & 7))) == 0 && value != 0) {
                    throw new IllegalArgumentException("Blocked terrain sunlight sample was not cleared");
                }
                var x = window.offsetX() + i % window.width();
                var y = window.offsetY() + i / window.width();
                result[frames.get(slot).samplesOffset() + y * WIDTH + x] = value.byteValue();
            }
        }
        return new TerrainTimeMapCodec(result, frames);
    }

    public TerrainTimeMapCodec withSamples(Map<Integer, int[]> vertices) {
        Objects.requireNonNull(vertices);
        var result = payload.clone();
        for (var entry : vertices.entrySet()) {
            var x = entry.getKey() % (WIDTH - 1);
            var y = entry.getKey() / (WIDTH - 1);
            var values = entry.getValue();
            if (entry.getKey() < 0 || y >= WIDTH - 1 || values.length != frames.size()) {
                throw new IllegalArgumentException("Invalid Terrain time-map sample replacement");
            }
            for (var f = 0; f < frames.size(); f++) {
                if (values[f] < 0 || values[f] > 255) {
                    throw new IllegalArgumentException("Invalid terrain sunlight intensity byte");
                }
                result[frames.get(f).samplesOffset() + y * WIDTH + x] = (byte) values[f];
            }
        }
        return new TerrainTimeMapCodec(result, frames);
    }

    public List<Integer> getSectorMap(int frame, int offsetX, int offsetY) {
        checkSectorOffset(offsetX, offsetY);
        var result = new ArrayList<Integer>(17 * 17);
        for (var y = 0; y <= 16; y++) {
            for (var x = 0; x <= 16; x++) {
                result.add(sample(frame, offsetX + x, offsetY + y));
            }
        }
        return List.copyOf(result);
    }

    public Consistency compareSectorMaps(List<Sector> sectors) throws IOException {
        long compared = 0;
        long mismatches = 0;
        var baked = 0;
        for (var sector : sectors) {
            checkSectorOffset(sector.offsetX(), sector.offsetY());
            if (sector.hasTimeMaps() == 0) {
                if (!sector.timeMaps().isEmpty()) {
                    throw new IOException("Unflagged Terrain sector has time maps");
                }
                continue;
            }
            if (sector.timeMaps().size() != frames.size()) {
                throw new IOException("Terrain sector and owner time-map counts disagree");
            }
            baked++;
            for (var f = 0; f < frames.size(); f++) {
                var values = sector.timeMaps().get(f);
                if (values.size() != 289) {
                    throw new IOException("Unaudited Terrain sector time-map size");
                }
                for (var i = 0; i < values.size(); i++) {
                    var value = values.get(i);
                    if (value < 0 || value > 255) {
                        throw new IOException("Invalid Terrain sector intensity");
                    }
                    compared++;
                    if (value != sample(f, sector.offsetX() + i % 17, sector.offsetY() + i / 17)) {
                        mismatches++;
                    }
                }
            }
        }
        return new Consistency(compared, mismatches, baked);
    }

    private static void checkSectorOffset(int x, int y) {
        if (x < 0 || y < 0 || x > 240 || y > 240 || x % 16 != 0 || y % 16 != 0) {
            throw new IllegalArgumentException("Unaudited Terrain sector time-map offset");
        }
    }
}
