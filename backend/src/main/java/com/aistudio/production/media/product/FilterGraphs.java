package com.aistudio.production.media.product;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fixed filter-graph templates (D04). Every value placed in a graph is a range-checked number, an enum-selected
 * template or a fixed ASCII alias inside the attempt scratch directory; no script text, path or AI output is ever
 * interpolated. Graphs are written to a file and passed with {@code -/filter_complex} to avoid command-line limits.
 */
final class FilterGraphs {
    static final String SUBTITLE_FILE = "sub.ass";
    static final String FONT_DIR = "fonts";
    static final String MIX_FILE = "mix.wav";
    static final String NORMALIZED_AUDIO = "norm.wav";
    static final String VIDEO_GRAPH = "video.txt";
    static final String AUDIO_GRAPH = "audio.txt";
    static final String OUTPUT = "out.mp4";
    static final String LOUDNORM = "I=-16:TP=-1.5:LRA=11";

    private FilterGraphs() {}

    static String image(int index) {
        return "img%02d.png".formatted(index + 1);
    }

    static String narration(int index) {
        return "nar%02d.wav".formatted(index + 1);
    }

    static String sound(String fixtureId) {
        if (!fixtureId.matches("[a-z0-9-]{1,32}")) {
            throw MediaFailure.blocked("MEDIA_FIXTURE_UNAVAILABLE", "Invalid audio fixture id");
        }
        return "snd-" + fixtureId + ".wav";
    }

    static String video(RenderPlan plan) {
        StringBuilder g = new StringBuilder();
        for (var shot : plan.shots()) {
            int frames = plan.clipFrames(shot);
            g.append("[").append(shot.index()).append(":v]scale=2160:3840:flags=lanczos,setsar=1,zoompan=")
                    .append(camera(shot, frames)).append(":d=").append(frames)
                    .append(":s=1080x1920:fps=30,scale=out_color_matrix=bt709:out_range=tv,format=yuv420p,")
                    .append("setsar=1,settb=1/30,setpts=PTS-STARTPTS[v").append(shot.index()).append("];\n");
        }
        String current = "v0";
        for (int i = 1; i < plan.shots().size(); i++) {
            var previous = plan.shots().get(i - 1);
            var shot = plan.shots().get(i);
            String next = "c" + i;
            if (previous.transitionOut() == RenderPlan.Transition.FADE) {
                g.append("[").append(current).append("][v").append(i).append("]xfade=transition=fade:duration=")
                        .append(seconds(RenderPlan.FADE_FRAMES)).append(":offset=").append(seconds(shot.startFrame()))
                        .append(",settb=1/30[").append(next).append("];\n");
            } else {
                g.append("[").append(current).append("][v").append(i)
                        .append("]concat=n=2:v=1:a=0,settb=1/30[").append(next).append("];\n");
            }
            current = next;
        }
        g.append("[").append(current).append("]");
        if (plan.fadeOut()) {
            g.append("fade=t=out:st=").append(seconds(plan.timeline().totalFrames() - RenderPlan.FADE_FRAMES))
                    .append(":d=").append(seconds(RenderPlan.FADE_FRAMES)).append(",");
        }
        g.append("ass=filename=").append(SUBTITLE_FILE).append(":fontsdir=").append(FONT_DIR)
                .append(",scale=out_color_matrix=bt709:out_range=tv,format=yuv420p,")
                .append("setparams=range=tv:color_primaries=bt709:color_trc=bt709:colorspace=bt709[vout]\n");
        return g.toString();
    }

    static String camera(RenderPlan.Shot shot, int frames) {
        int n = Math.max(1, frames - 1);
        String t = "(on/" + n + ")";
        String ease = "(" + t + "*" + t + "*(3-2*" + t + "))";
        String cx = "iw/2-(iw/zoom/2)";
        String cy = "ih/2-(ih/zoom/2)";
        String travelX = "(iw-iw/zoom)";
        String travelY = "(ih-ih/zoom)";
        return switch (shot.camera()) {
            case STATIC -> "z='1':x='0':y='0'";
            case ZOOM_IN -> "z='1+0.12*" + ease + "':x='" + cx + "':y='" + cy + "'";
            case ZOOM_OUT -> "z='1.12-0.12*" + ease + "':x='" + cx + "':y='" + cy + "'";
            case PAN_LEFT -> "z='1.12':x='" + travelX + "*(1-" + ease + ")':y='" + cy + "'";
            case PAN_RIGHT -> "z='1.12':x='" + travelX + "*" + ease + "':y='" + cy + "'";
            case PAN_UP -> "z='1.12':x='" + cx + "':y='" + travelY + "*(1-" + ease + ")'";
            case PAN_DOWN -> "z='1.12':x='" + cx + "':y='" + travelY + "*" + ease + "'";
            case PUNCH_IN -> {
                int p = Math.max(1, shot.punchFrame());
                yield "z='if(lt(on," + p + "),1+0.03*on/" + p + ",min(1.18,1.03+0.0375*(on-" + p + ")))'"
                        + ":x='" + cx + "':y='" + cy + "'";
            }
        };
    }

    /**
     * Narration-first stereo mix: scene narration is concatenated and padded to the exact frame-aligned length,
     * optional music is looped, faded and ducked under the narration, and sound effects are placed at sample offsets.
     * Input order: narration files, then music (if any), then one input per sound placement.
     */
    static String audio(RenderPlan plan, int narrations) {
        long total = plan.timeline().outputSamples();
        StringBuilder g = new StringBuilder();
        for (int i = 0; i < narrations; i++) {
            g.append("[").append(i).append(":a]");
        }
        boolean music = plan.musicId() != null;
        g.append("concat=n=").append(narrations).append(":v=0:a=1,apad=whole_len=").append(total)
                .append(",atrim=end_sample=").append(total).append(",pan=stereo|c0=c0|c1=c0");
        g.append(music ? ",asplit=2[narr][key];\n" : "[narr];\n");
        List<String> mix = new ArrayList<>();
        mix.add("narr");
        int input = narrations;
        if (music) {
            double seconds = (double) total / Alignment.RATE;
            g.append("[").append(input++).append(":a]aformat=sample_rates=48000:channel_layouts=stereo,atrim=end_sample=")
                    .append(total).append(",volume=").append(decibels(plan.musicGainDb()))
                    .append(",afade=t=in:d=1.0,afade=t=out:st=").append(number(Math.max(0, seconds - 1.5)))
                    .append(":d=1.5[bgm0];\n[bgm0][key]sidechaincompress=threshold=0.03:ratio=6:attack=15:release=350")
                    .append(":makeup=1[bgm];\n");
            mix.add("bgm");
        }
        int k = 0;
        for (var sound : plan.sounds()) {
            String label = "s" + k++;
            g.append("[").append(input++).append(":a]aformat=sample_rates=48000:channel_layouts=stereo,volume=")
                    .append(decibels(sound.gainDb())).append(",adelay=delays=").append(sound.startSample())
                    .append("S:all=1,apad=whole_len=").append(total).append(",atrim=end_sample=").append(total)
                    .append("[").append(label).append("];\n");
            mix.add(label);
        }
        if (mix.size() == 1) {
            g.append("[narr]anull[aout]\n");
        } else {
            for (String label : mix) {
                g.append("[").append(label).append("]");
            }
            g.append("amix=inputs=").append(mix.size()).append(":normalize=0:duration=first,atrim=end_sample=")
                    .append(total).append("[aout]\n");
        }
        return g.toString();
    }

    record Measured(String inputI, String inputTp, String inputLra, String inputThresh, String targetOffset) {
        Measured {
            for (String value : List.of(inputI, inputTp, inputLra, inputThresh, targetOffset)) {
                if (value == null || !value.matches("-?[0-9]{1,3}(\\.[0-9]{1,3})?")) {
                    throw MediaFailure.blocked("MEDIA_LOUDNESS_INVALID", "Loudness measurement is out of range");
                }
            }
            if (Double.parseDouble(inputI) < -70) {
                throw MediaFailure.blocked("MEDIA_LOUDNESS_INVALID", "Mix is silent");
            }
        }
    }

    static String loudnormSecondPass(Measured m, long totalSamples) {
        return "loudnorm=" + LOUDNORM + ":measured_I=" + m.inputI() + ":measured_TP=" + m.inputTp()
                + ":measured_LRA=" + m.inputLra() + ":measured_thresh=" + m.inputThresh() + ":offset=" + m.targetOffset()
                + ":linear=true:print_format=json,aresample=48000,apad=whole_len=" + totalSamples
                + ",atrim=end_sample=" + totalSamples;
    }

    static String seconds(int frames) {
        return Double.toString(frames / 30.0);
    }

    private static String decibels(double db) {
        if (db < -40 || db > 0) {
            throw MediaFailure.blocked("MEDIA_FIXTURE_UNAVAILABLE", "Gain is out of range");
        }
        return number(db) + "dB";
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
