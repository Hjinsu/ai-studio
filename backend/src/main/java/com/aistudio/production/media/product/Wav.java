package com.aistudio.production.media.product;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Minimal RIFF/WAVE reader and writer for 16-bit PCM. The sample count is always taken from the decoded
 * {@code data} chunk, never from the file size (headers vary, e.g. 46-byte SAPI headers).
 */
record Wav(int sampleRate, int channels, short[] samples) {
    static final int MAX_SECONDS = 600;

    long frames() {
        return samples.length / channels;
    }

    static Wav parse(byte[] bytes) {
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 44 || in.getInt(0) != 0x46464952 || in.getInt(8) != 0x45564157) {
            throw MediaFailure.blocked("MEDIA_AUDIO_INVALID", "Not a RIFF/WAVE file");
        }
        int position = 12;
        Integer rate = null;
        Integer channels = null;
        short[] samples = null;
        while (position + 8 <= bytes.length) {
            int id = in.getInt(position);
            long size = Integer.toUnsignedLong(in.getInt(position + 4));
            int body = position + 8;
            if (size > bytes.length - body) {
                throw MediaFailure.blocked("MEDIA_AUDIO_INVALID", "Truncated WAV chunk");
            }
            if (id == 0x20746d66) { // "fmt "
                if (size < 16) {
                    throw MediaFailure.blocked("MEDIA_AUDIO_INVALID", "Short fmt chunk");
                }
                int format = Short.toUnsignedInt(in.getShort(body));
                channels = Short.toUnsignedInt(in.getShort(body + 2));
                rate = in.getInt(body + 4);
                int bits = Short.toUnsignedInt(in.getShort(body + 14));
                boolean extensiblePcm = format == 0xFFFE && size >= 40 && in.getShort(body + 24) == 1;
                if ((format != 1 && !extensiblePcm) || bits != 16 || channels < 1 || channels > 2
                        || rate < 8000 || rate > 48000) {
                    throw MediaFailure.blocked("MEDIA_AUDIO_INVALID", "Unsupported WAV encoding");
                }
            } else if (id == 0x61746164) { // "data"
                if (rate == null || channels == null) {
                    throw MediaFailure.blocked("MEDIA_AUDIO_INVALID", "WAV data before fmt");
                }
                if (size % (2L * channels) != 0 || size / (2L * channels) > (long) rate * MAX_SECONDS) {
                    throw MediaFailure.blocked("MEDIA_AUDIO_INVALID", "Invalid WAV data length");
                }
                samples = new short[(int) (size / 2)];
                ByteBuffer data = ByteBuffer.wrap(bytes, body, (int) size).order(ByteOrder.LITTLE_ENDIAN);
                data.asShortBuffer().get(samples);
                break;
            }
            position = body + (int) size + (int) (size & 1);
        }
        if (samples == null || samples.length == 0) {
            throw MediaFailure.blocked("MEDIA_AUDIO_INVALID", "WAV has no audio data");
        }
        return new Wav(rate, channels, samples);
    }

    /** Mono view (channel average) of the samples. */
    short[] mono() {
        if (channels == 1) {
            return samples;
        }
        short[] out = new short[samples.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (short) ((samples[2 * i] + samples[2 * i + 1]) / 2);
        }
        return out;
    }

    byte[] encode() {
        int dataBytes = samples.length * 2;
        ByteBuffer out = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46464952).putInt(36 + dataBytes).putInt(0x45564157);
        out.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) channels).putInt(sampleRate)
                .putInt(sampleRate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        out.putInt(0x61746164).putInt(dataBytes);
        out.asShortBuffer().put(samples);
        return out.array();
    }
}
