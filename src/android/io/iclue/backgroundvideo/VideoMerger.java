package io.iclue.backgroundvideo;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Another app took the camera during a recording, so it went on in new files (parts) once the camera was free again.
 * An app keeps one video per recording and may play it along a timeline (start + playback time), so the parts are
 * joined into the first file on the real timeline: each part is placed at its own start, the picture holds in a gap
 * and the sound gets silent frames there (players count audio frames, a timestamp gap shifts the sound).
 * The bytes are copied as they are by Mp4Concat (~1 s for hours); a layout it does not know is remuxed by MediaMuxer.
 */
final class VideoMerger {
    private static final String TAG = "BACKGROUND_VID_MERGER";
    private static final int AAC_FRAME_SAMPLES = 1024;
    private static final int SILENT_INPUT_FRAMES = 8;
    private static final long PART_MARGIN_US = 100000;
    private static final int MIN_BUFFER_SIZE = 1024 * 1024;

    private final MediaMuxer muxer;
    private final MediaFormat videoFormat;
    private final MediaFormat audioFormat;
    private final int videoTrack;
    private final int audioTrack;
    private final ByteBuffer buffer;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private ByteBuffer silence;
    private long audioFrameUs;
    private long lastVideoUs = -1;
    private long lastAudioUs = -1;
    private long readNs;
    private long writeNs;

    /**
     * @param files    the parts in order, the first one is the recording: it is replaced with the joined video
     * @param startsMs when each part started, by the device clock
     * @param workDir  internal storage: the muxer writes every sample on its own, that is slow on the shared one (FUSE)
     * @return the first file: the joined video, or only the first part when the parts could not be joined
     */
    static String merge(List<File> files, List<Long> startsMs, File workDir) {
        long began = System.currentTimeMillis();
        File target = files.get(0);
        try {
            int joined = copyJoin(files, startsMs, target);
            Log.i(TAG, "Joined " + joined + " of " + files.size() + " parts into " + target + ", " + target.length()
                    + " bytes, in " + (System.currentTimeMillis() - began) + " ms");
        } catch (Exception e) {
            Log.w(TAG, "The parts are remuxed, they can't be joined as they are: " + e);
            remux(files, startsMs, workDir, began);
        } finally {
            for (int i = 1; i < files.size(); i++) {
                File file = files.get(i);
                if (!file.equals(target) && file.exists() && !file.delete()) Log.w(TAG, "Could not delete " + file);
            }
        }
        return target.getPath();
    }

    //the bytes of the parts as they are (Mp4Concat): next to the target with big writes, then over it at once
    private static int copyJoin(List<File> files, List<Long> startsMs, File target) throws IOException {
        long[] startsUs = new long[files.size()];
        for (int i = 0; i < startsUs.length; i++) startsUs[i] = (startsMs.get(i) - startsMs.get(0)) * 1000;
        File joined = new File(target.getPath() + ".joining");
        try {
            int count = Mp4Concat.join(files, startsUs, silenceFor(files), joined);
            if (!joined.renameTo(target)) throw new IOException("could not replace " + target);
            return count;
        } finally {
            if (joined.exists() && !joined.delete()) Log.w(TAG, "Could not delete " + joined);
        }
    }

    private static ByteBuffer silenceFor(List<File> files) {
        for (File file : files) {
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(file.getPath());
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) return silentFrame(format);
                }
                return null;
            } catch (Exception e) {
                Log.d(TAG, "No format in " + file + ": " + e);
            } finally {
                extractor.release();
            }
        }
        return null;
    }

    //sample by sample through MediaMuxer: slow (~0.2 ms a sample), for the files Mp4Concat refuses
    private static void remux(List<File> files, List<Long> startsMs, File workDir, long began) {
        File target = files.get(0);
        File joined = new File(workDir, target.getName() + ".joining");
        List<Part> parts = new ArrayList<Part>();
        try {
            MediaFormat videoFormat = null, audioFormat = null;
            for (int i = 0; i < files.size(); i++) {
                Part part = Part.open(files.get(i), startsMs.get(i) * 1000);
                if (part == null) continue;
                parts.add(part);
                if (videoFormat == null) videoFormat = part.videoFormat;
                if (audioFormat == null) audioFormat = part.audioFormat;
            }
            if (parts.isEmpty()) throw new IOException("no readable part");
            VideoMerger merger = new VideoMerger(joined, videoFormat, audioFormat);
            merger.write(parts, startsMs.get(0) * 1000);
            long moving = System.currentTimeMillis();
            moveInto(joined, target);
            Log.i(TAG, "Remuxed " + parts.size() + " of " + files.size() + " parts into " + target + ", "
                    + target.length() + " bytes, in " + (System.currentTimeMillis() - began) + " ms (read "
                    + merger.readNs / 1000000 + ", write " + merger.writeNs / 1000000 + ", move "
                    + (System.currentTimeMillis() - moving) + " ms)");
        } catch (Exception e) {
            Log.e(TAG, "Could not join the parts, only the first one is kept", e);
            if (joined.exists() && !joined.delete()) Log.w(TAG, "Could not delete " + joined);
        } finally {
            for (Part part : parts) part.release();
        }
    }

    //into the folder of the target with big writes, then over it at once
    private static void moveInto(File joined, File target) throws IOException {
        if (joined.renameTo(target)) return;//the same file system
        File copy = new File(target.getPath() + ".joining");
        try {
            FileInputStream in = new FileInputStream(joined);
            try {
                FileOutputStream out = new FileOutputStream(copy);
                try {
                    byte[] chunk = new byte[MIN_BUFFER_SIZE];
                    for (int n; (n = in.read(chunk)) > 0; ) out.write(chunk, 0, n);
                    out.getFD().sync();
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            if (!copy.renameTo(target)) throw new IOException("could not replace " + target);
        } finally {
            if (copy.exists() && !copy.delete()) Log.w(TAG, "Could not delete " + copy);
            if (!joined.delete()) Log.w(TAG, "Could not delete " + joined);
        }
    }

    private VideoMerger(File out, MediaFormat videoFormat, MediaFormat audioFormat) throws IOException {
        this.videoFormat = videoFormat;
        this.audioFormat = audioFormat;
        muxer = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            videoTrack = videoFormat != null ? muxer.addTrack(videoFormat) : -1;
            audioTrack = audioFormat != null ? muxer.addTrack(audioFormat) : -1;
            if (videoFormat != null && videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION));
            }
        } catch (RuntimeException e) {
            muxer.release();
            throw e;
        }
        buffer = ByteBuffer.allocateDirect(Math.max(MIN_BUFFER_SIZE, Math.max(maxInputSize(videoFormat), maxInputSize(audioFormat))));
        if (audioFormat != null) {
            audioFrameUs = AAC_FRAME_SAMPLES * 1000000L / audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            silence = silentFrame(audioFormat);
        }
    }

    private void write(List<Part> parts, long originUs) {
        try {
            muxer.start();
            long endUs = 0;
            for (Part part : parts) {
                part.dropMismatching(videoFormat, audioFormat);
                long offsetUs = Math.max(part.startUs - originUs, endUs);
                fillSilence(offsetUs + part.firstAudioUs());
                copy(part, offsetUs);
                endUs = Math.max(lastVideoUs, lastAudioUs) + PART_MARGIN_US;
            }
            muxer.stop();
        } finally {
            muxer.release();
        }
    }

    //the samples of both tracks in time order: the joined file stays interleaved for playing over the network
    private void copy(Part part, long offsetUs) {
        while (true) {
            long videoUs = part.video != null ? part.video.getSampleTime() : -1;
            long audioUs = part.audio != null ? part.audio.getSampleTime() : -1;
            if (videoUs < 0 && audioUs < 0) return;
            boolean isVideo = videoUs >= 0 && (audioUs < 0 || videoUs <= audioUs);
            MediaExtractor extractor = isVideo ? part.video : part.audio;
            long reading = System.nanoTime();
            int size = extractor.readSampleData(buffer, 0);
            boolean sync = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
            readNs += System.nanoTime() - reading;
            long ptsUs = offsetUs + (isVideo ? videoUs : audioUs);
            if (size > 0 && ptsUs > (isVideo ? lastVideoUs : lastAudioUs)) {
                info.set(0, size, ptsUs, sync ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                long writing = System.nanoTime();
                muxer.writeSampleData(isVideo ? videoTrack : audioTrack, buffer, info);
                writeNs += System.nanoTime() - writing;
                if (isVideo) lastVideoUs = ptsUs;
                else lastAudioUs = ptsUs;
            }
            reading = System.nanoTime();
            boolean more = size >= 0 && extractor.advance();
            readNs += System.nanoTime() - reading;
            if (!more) part.finish(isVideo);
        }
    }

    private void fillSilence(long untilUs) {
        if (silence == null || lastAudioUs < 0) return;
        long writing = System.nanoTime();
        for (long t = lastAudioUs + audioFrameUs; t + audioFrameUs <= untilUs; t += audioFrameUs) {
            info.set(0, silence.limit(), t, MediaCodec.BUFFER_FLAG_KEY_FRAME);
            muxer.writeSampleData(audioTrack, silence, info);
            lastAudioUs = t;
        }
        writeNs += System.nanoTime() - writing;
    }

    private static int maxInputSize(MediaFormat format) {
        return format != null && format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
    }

    //a frame of silence by the AAC encoder of the device for the format of the recording; null: the gaps stay silent-less
    private static ByteBuffer silentFrame(MediaFormat audioFormat) {
        if (!MediaFormat.MIMETYPE_AUDIO_AAC.equals(audioFormat.getString(MediaFormat.KEY_MIME))) return null;
        int sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        List<Integer> bitRates = new ArrayList<Integer>(Arrays.asList(16000, 32000, 64000));
        if (audioFormat.containsKey(MediaFormat.KEY_BIT_RATE)) bitRates.add(0, audioFormat.getInteger(MediaFormat.KEY_BIT_RATE));
        for (int bitRate : bitRates) {
            MediaCodec codec = null;
            try {
                MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                codec.start();
                byte[] frame = encodeSilence(codec, sampleRate, channels);
                ByteBuffer silence = ByteBuffer.allocateDirect(frame.length);
                silence.put(frame);
                silence.flip();
                return silence;
            } catch (Exception e) {
                Log.w(TAG, "No silence by the encoder at " + bitRate + " bit/s: " + e);
            } finally {
                if (codec != null) {
                    try {
                        codec.stop();
                    } catch (Exception ignored) {
                    }
                    codec.release();
                }
            }
        }
        return null;
    }

    private static byte[] encodeSilence(MediaCodec codec, int sampleRate, int channels) {
        List<byte[]> frames = new ArrayList<byte[]>();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] pcm = new byte[AAC_FRAME_SAMPLES * channels * 2];
        int queued = 0;
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (queued <= SILENT_INPUT_FRAMES) {
                int in = codec.dequeueInputBuffer(10000);
                if (in >= 0) {
                    ByteBuffer input = codec.getInputBuffer(in);
                    input.clear();
                    boolean last = queued == SILENT_INPUT_FRAMES;
                    int size = last ? 0 : Math.min(pcm.length, input.remaining());
                    input.put(pcm, 0, size);
                    codec.queueInputBuffer(in, 0, size, queued * AAC_FRAME_SAMPLES * 1000000L / sampleRate,
                            last ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                    queued++;
                }
            }
            int out = codec.dequeueOutputBuffer(info, 10000);
            if (out >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                    ByteBuffer output = codec.getOutputBuffer(out);
                    byte[] frame = new byte[info.size];
                    output.position(info.offset);
                    output.get(frame);
                    frames.add(frame);
                }
                codec.releaseOutputBuffer(out, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
        if (frames.isEmpty()) throw new IllegalStateException("the encoder gave no frames");
        return frames.get(frames.size() / 2);//past the start of the encoder
    }

    private static final class Part {
        final File file;
        final long startUs;
        MediaExtractor video;
        MediaExtractor audio;
        MediaFormat videoFormat;
        MediaFormat audioFormat;

        private Part(File file, long startUs) {
            this.file = file;
            this.startUs = startUs;
        }

        //null: a part cut by a crash (no index at the end) can't be read
        static Part open(File file, long startUs) {
            Part part = new Part(file, startUs);
            try {
                part.video = part.select("video/");
                part.audio = part.select("audio/");
                if (part.video == null && part.audio == null) throw new IOException("no tracks");
                return part;
            } catch (Exception e) {
                Log.w(TAG, "The part can't be read, skipped: " + file + ", " + e);
                part.release();
                return null;
            }
        }

        private MediaExtractor select(String mimePrefix) throws IOException {
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(file.getPath());
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith(mimePrefix)) {
                        extractor.selectTrack(i);
                        if (mimePrefix.startsWith("video")) videoFormat = format;
                        else audioFormat = format;
                        return extractor;
                    }
                }
            } catch (IOException | RuntimeException e) {
                extractor.release();
                throw e;
            }
            extractor.release();
            return null;
        }

        //the encoder of the device gives the same format to every part; another one can't go into the same track
        void dropMismatching(MediaFormat video, MediaFormat audio) {
            if (this.video != null && !sameFormat(videoFormat, video, MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT)) {
                Log.w(TAG, "Another video format in " + file + ", its picture is skipped: " + videoFormat);
                finish(true);
            }
            if (this.audio != null && !sameFormat(audioFormat, audio, MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT)) {
                Log.w(TAG, "Another audio format in " + file + ", its sound is skipped: " + audioFormat);
                finish(false);
            }
        }

        long firstAudioUs() {
            return audio != null ? Math.max(0, audio.getSampleTime()) : 0;
        }

        void finish(boolean isVideo) {
            if (isVideo && video != null) {
                video.release();
                video = null;
            } else if (!isVideo && audio != null) {
                audio.release();
                audio = null;
            }
        }

        void release() {
            finish(true);
            finish(false);
        }

        private static boolean sameFormat(MediaFormat a, MediaFormat b, String... keys) {
            if (a == null || b == null || !a.getString(MediaFormat.KEY_MIME).equals(b.getString(MediaFormat.KEY_MIME))) return false;
            for (String key : keys) {
                if (!a.containsKey(key) || !b.containsKey(key) || a.getInteger(key) != b.getInteger(key)) return false;
            }
            for (String csd : new String[]{"csd-0", "csd-1"}) {
                ByteBuffer x = a.containsKey(csd) ? a.getByteBuffer(csd) : null, y = b.containsKey(csd) ? b.getByteBuffer(csd) : null;
                if (x == null ? y != null : !x.equals(y)) return false;
            }
            return true;
        }
    }
}
