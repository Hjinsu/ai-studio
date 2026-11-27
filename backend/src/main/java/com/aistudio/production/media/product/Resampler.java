package com.aistudio.production.media.product;

/**
 * Deterministic polyphase Kaiser-windowed sinc resampler for 16-bit mono PCM. Output sample {@code n} is taken at
 * input time {@code n * in / out}, so a source sample offset {@code s} maps to {@link #map(long, int, int)}. The
 * output length is {@code round(frames * out / in)}. Used to normalise narration to the 48 kHz sample clock.
 */
final class Resampler {
    private static final int HALF_TAPS = 24;
    private static final double BETA = 8.6;

    private Resampler() {}

    static long map(long sourceSample, int inRate, int outRate) {
        return Math.floorDiv(sourceSample * outRate * 2 + inRate, 2L * inRate);
    }

    static short[] resample(short[] input, int inRate, int outRate) {
        if (inRate == outRate) {
            return input.clone();
        }
        int gcd = gcd(inRate, outRate);
        int up = outRate / gcd;
        int down = inRate / gcd;
        if (up > 4096) {
            throw MediaFailure.blocked("MEDIA_AUDIO_INVALID", "Unsupported narration sample rate");
        }
        double cutoff = 0.95 * Math.min(1.0, (double) outRate / inRate);
        int taps = 2 * HALF_TAPS;
        double[][] table = new double[up][taps];
        double norm = bessel0(BETA);
        for (int phase = 0; phase < up; phase++) {
            double frac = (double) phase / up;
            double sum = 0;
            for (int k = 0; k < taps; k++) {
                double x = (k - HALF_TAPS + 1) - frac;
                double window = Math.abs(x) >= HALF_TAPS ? 0
                        : bessel0(BETA * Math.sqrt(1 - (x / HALF_TAPS) * (x / HALF_TAPS))) / norm;
                double sinc = x == 0 ? 1 : Math.sin(Math.PI * cutoff * x) / (Math.PI * cutoff * x);
                table[phase][k] = cutoff * sinc * window;
                sum += table[phase][k];
            }
            for (int k = 0; k < taps; k++) {
                table[phase][k] /= sum;
            }
        }
        long outLength = map(input.length, inRate, outRate);
        short[] output = new short[(int) outLength];
        for (int n = 0; n < output.length; n++) {
            long position = (long) n * down;
            int base = (int) (position / up);
            int phase = (int) (position % up);
            double[] h = table[phase];
            double acc = 0;
            for (int k = 0; k < taps; k++) {
                int index = base - HALF_TAPS + 1 + k;
                if (index >= 0 && index < input.length) {
                    acc += h[k] * input[index];
                }
            }
            long value = Math.round(acc);
            output[n] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
        }
        return output;
    }

    private static double bessel0(double x) {
        double sum = 1;
        double term = 1;
        for (int k = 1; k < 50; k++) {
            term *= (x / (2 * k)) * (x / (2 * k));
            sum += term;
            if (term < 1e-12 * sum) {
                break;
            }
        }
        return sum;
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }
}
