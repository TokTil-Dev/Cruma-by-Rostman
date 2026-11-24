package com.rost.cruma.core.audio;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.eclipse.collections.impl.list.mutable.primitive.ShortArrayList;

public final class VorbisDecoder implements Pcm {
    private static final byte[] L2_CAPTURE_PATTERN = {'L', '2', 'S', 'D'};
    private static final byte[] OGG_CAPTURE_PATTERN = {'O', 'g', 'g', 'S'};
    private static final int FEED_BYTES = 8192;
    private static final int DECODE_FRAMES = 1 << 14;
    private static final int LONGEST_PAGE = 27 + 255 + 255 * 255;
    private static final int PAGE_SEGMENTS = 26;
    private static final int PAGE_HEADER = 27;
    private static final int GRANULE = 6;
    private static final int PACKET_RATE = 12;

    private final byte[] file;
    private int filePosition;
    private final SyncState sync = new SyncState();
    private final StreamState stream = new StreamState();
    private final Page page = new Page();
    private final Packet packet = new Packet();
    private final Info info = new Info();
    private final Comment comment = new Comment();
    private final DspState dsp = new DspState();
    private final Block block = new Block(dsp);
    private final float[][][] pcm = new float[1][][];
    private final int[] pcmIndex;
    private boolean ended;

    public VorbisDecoder(Path path) throws IOException {
        this(Files.readAllBytes(path), path.toString());
    }

    VorbisDecoder(byte[] bytes, String source) throws IOException {
        this.file = bytes;
        if (bytes.length >= 4
                && bytes[0] == L2_CAPTURE_PATTERN[0]
                && bytes[1] == L2_CAPTURE_PATTERN[1]
                && bytes[2] == L2_CAPTURE_PATTERN[2]
                && bytes[3] == L2_CAPTURE_PATTERN[3]) {
            System.arraycopy(OGG_CAPTURE_PATTERN, 0, bytes, 0, 4);
        }
        sync.init();
        info.init();
        comment.init();
        readHeaders(source);
        dsp.synthesis_init(info);
        block.init(dsp);
        pcmIndex = new int[info.channels];
    }

    public static SoundClip decode(Path file, String name) throws IOException {
        try (var decoder = new VorbisDecoder(file)) {
            var channels = decoder.getChannels();
            var chunk = new short[DECODE_FRAMES * channels];
            var samples = new ShortArrayList();
            var frames = decoder.read(chunk);
            while (frames > 0) {
                for (var index = 0; index < frames * channels; index++) {
                    samples.add(chunk[index]);
                }
                frames = decoder.read(chunk);
            }
            return new SoundClip(name, channels, decoder.getSampleRate(), samples.toArray());
        }
    }

    public static double seconds(Path path) {
        try (var file = FileChannel.open(path, StandardOpenOption.READ)) {
            var start = readAt(file, 0, PAGE_HEADER + 1);
            var packet = PAGE_HEADER + (start.get(PAGE_SEGMENTS) & 0xFF);
            var rate = readAt(file, packet + PACKET_RATE, Integer.BYTES).getInt(0);
            var tail = readAt(file, Math.max(0, file.size() - LONGEST_PAGE), LONGEST_PAGE);
            for (var at = tail.limit() - PAGE_HEADER; at >= 0; at--) {
                if (isPage(tail, at)) {
                    var frames = tail.getLong(at + GRANULE);
                    return frames < 0 || rate <= 0 ? Double.NaN : (double) frames / rate;
                }
            }
            return Double.NaN;
        } catch (IOException | RuntimeException unreadable) {
            return Double.NaN;
        }
    }

    public boolean hasOldFloor() {
        try {
            var field = Info.class.getDeclaredField("floor_type");
            field.setAccessible(true);
            var types = (int[]) field.get(info);
            if (types == null) {
                return true;
            }
            for (var type : types) {
                if (type == 0) {
                    return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException hidden) {
            return true;
        }
    }

    @Override
    public int getChannels() {
        return info.channels;
    }

    @Override
    public int getSampleRate() {
        return info.rate;
    }

    @Override
    public int read(short[] out) {
        var channels = info.channels;
        var capacity = out.length / channels;
        var written = 0;
        while (written < capacity) {
            var available = dsp.synthesis_pcmout(pcm, pcmIndex);
            if (available <= 0) {
                if (!readNextPacket()) {
                    break;
                }
                continue;
            }
            var frames = Math.min(available, capacity - written);
            for (var channel = 0; channel < channels; channel++) {
                var samples = pcm[0][channel];
                var offset = pcmIndex[channel];
                var cursor = written * channels + channel;
                for (var i = 0; i < frames; i++) {
                    var value = (int) (samples[offset + i] * 32767f);
                    out[cursor] = (short) Math.clamp(value, Short.MIN_VALUE, Short.MAX_VALUE);
                    cursor += channels;
                }
            }
            dsp.synthesis_read(frames);
            written += frames;
        }
        return written;
    }

    @Override
    public long skip(long frames) {
        var left = frames;
        while (left > 0) {
            var available = dsp.synthesis_pcmout(pcm, pcmIndex);
            if (available <= 0) {
                if (!readNextPacket()) {
                    break;
                }
                continue;
            }
            var passed = (int) Math.min(available, left);
            dsp.synthesis_read(passed);
            left -= passed;
        }
        return frames - left;
    }

    @Override
    public void close() {
        block.clear();
        dsp.clear();
        stream.clear();
        info.clear();
        sync.clear();
    }

    private void readHeaders(String source) throws IOException {
        var headers = 0;
        while (headers < 3) {
            var result = sync.pageout(page);
            if (result == 0) {
                if (!feedSyncBuffer()) {
                    throw new IOException(source + " ends inside the Vorbis headers");
                }
                continue;
            }
            if (result < 0) {
                continue;
            }
            if (headers == 0) {
                stream.init(page.serialno());
                stream.reset();
            }
            if (stream.pagein(page) < 0) {
                throw new IOException(source + " has a corrupt Ogg page in its headers");
            }
            while (headers < 3) {
                var got = stream.packetout(packet);
                if (got == 0) {
                    break;
                }
                if (got < 0) {
                    throw new IOException(source + " has a corrupt Vorbis header packet");
                }
                if (info.synthesis_headerin(comment, packet) < 0) {
                    throw new IOException(source + " is not a Vorbis stream");
                }
                headers++;
            }
        }
    }

    private boolean readNextPacket() {
        while (!ended) {
            var got = stream.packetout(packet);
            if (got == 1) {
                if (block.synthesis(packet) == 0) {
                    dsp.synthesis_blockin(block);
                    return true;
                }
                continue;
            }
            if (got < 0) {
                continue;
            }
            var result = sync.pageout(page);
            if (result == 1) {
                stream.pagein(page);
            } else if (result == 0 && !feedSyncBuffer()) {
                ended = true;
            }
        }
        return false;
    }

    private boolean feedSyncBuffer() {
        if (filePosition >= file.length) {
            return false;
        }
        var count = Math.min(FEED_BYTES, file.length - filePosition);
        var index = sync.buffer(count);
        System.arraycopy(file, filePosition, sync.data, index, count);
        sync.wrote(count);
        filePosition += count;
        return true;
    }

    private static ByteBuffer readAt(FileChannel file, long position, int bytes) throws IOException {
        var read = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        file.position(position);
        while (read.hasRemaining() && file.read(read) >= 0) {
        }
        return read.flip();
    }

    private static boolean isPage(ByteBuffer bytes, int at) {
        for (var index = 0; index < OGG_CAPTURE_PATTERN.length; index++) {
            if (bytes.get(at + index) != OGG_CAPTURE_PATTERN[index]) {
                return false;
            }
        }
        return true;
    }
}
