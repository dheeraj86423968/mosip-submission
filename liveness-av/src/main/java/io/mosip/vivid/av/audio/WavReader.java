package io.mosip.vivid.av.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal RIFF/WAVE reader for 16-bit PCM (any channel count, any rate). Channels are averaged and the result is
 * linearly resampled to 16 kHz. For tests and file-driven evaluation only; the live path never touches files.
 */
public final class WavReader {
    public static final int TARGET_RATE = 16_000;

    private WavReader() {}

    public static AudioClip read(Path file) throws IOException {
        return parse(Files.readAllBytes(file));
    }

    public static AudioClip parse(byte[] bytes) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 12 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) throw new IOException("not a RIFF/WAVE file");
        int pos = 12, channels = 0, rate = 0, bits = 0, format = 0;
        while (pos + 8 <= bytes.length) {
            int id = b.getInt(pos);
            int size = b.getInt(pos + 4);
            int body = pos + 8;
            if (id == 0x20746d66) { // "fmt "
                format = b.getShort(body) & 0xffff;
                channels = b.getShort(body + 2) & 0xffff;
                rate = b.getInt(body + 4);
                bits = b.getShort(body + 14) & 0xffff;
            } else if (id == 0x61746164) { // "data"
                if (format != 1 && format != 0xFFFE) throw new IOException("only PCM WAV is supported (format " + format + ")");
                if (bits != 16 || channels <= 0 || rate <= 0) throw new IOException("only 16-bit PCM is supported");
                int len = Math.min(size < 0 ? Integer.MAX_VALUE : size, bytes.length - body);
                int frames = len / (2 * channels);
                short[] mono = new short[frames];
                for (int i = 0; i < frames; i++) {
                    int acc = 0;
                    for (int c = 0; c < channels; c++) acc += b.getShort(body + 2 * (i * channels + c));
                    mono[i] = (short) (acc / channels);
                }
                return new AudioClip(resample(mono, rate, TARGET_RATE), TARGET_RATE, 0);
            }
            pos = body + size + (size & 1);
        }
        throw new IOException("no data chunk");
    }

    /** Linear-interpolation resampler; adequate for speech envelopes and for the recogniser at 16 kHz. */
    public static short[] resample(short[] in, int from, int to) {
        if (from == to) return in.clone();
        int n = (int) ((long) in.length * to / from);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double src = (double) i * from / to;
            int j = (int) src;
            double f = src - j;
            double a = in[Math.min(j, in.length - 1)], c = in[Math.min(j + 1, in.length - 1)];
            out[i] = (short) Math.round(a + (c - a) * f);
        }
        return out;
    }
}
