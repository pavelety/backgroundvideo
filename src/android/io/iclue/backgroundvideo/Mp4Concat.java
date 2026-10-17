package io.iclue.backgroundvideo;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Joins the MP4 parts of one recording without remuxing it sample by sample: the media bytes are copied in big blocks
 * and the sample tables are joined, each part placed at its own start. MediaMuxer hands every sample to its writer
 * thread (~0.2 ms each): minutes for a video of hours. Only the plain layout MediaRecorder writes is taken (one sample
 * description per track, no composition offsets, edit lists with a start delay only), anything else is refused with an
 * IOException and VideoMerger remuxes it then.
 */
final class Mp4Concat {
    private static final String TAG = "BACKGROUND_VID_MERGER";
    private static final int SILENCE_CHUNK_SAMPLES = 1024;

    private Mp4Concat() {
    }

    /**
     * @param files    the readable parts in order
     * @param startsUs the start of each part on the timeline of the first one
     * @param silence  an AAC frame of silence for the gaps in the sound, null: none
     * @param out      where the joined video is written
     * @return the parts joined
     */
    static int join(List<File> files, long[] startsUs, ByteBuffer silence, File out) throws IOException {
        List<Mp4> parts = new ArrayList<Mp4>();
        List<Long> starts = new ArrayList<Long>();
        for (int i = 0; i < files.size(); i++) {
            try {
                parts.add(Mp4.read(files.get(i)));
                starts.add(startsUs[i]);
            } catch (Unreadable e) {
                Log.w(TAG, "The part can't be read, skipped: " + files.get(i) + ", " + e.getMessage());
            }
        }
        if (parts.isEmpty()) throw new IOException("no readable part");
        Mp4 first = parts.get(0);
        for (Mp4 part : parts) first.checkSameAs(part);
        Layout layout = new Layout(parts, starts, silence);
        layout.write(out);
        verify(out, layout);
        return parts.size();
    }

    //the platform demuxer has to read the joined file up to its last part, else MediaMuxer does it
    private static void verify(File out, Layout layout) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(out.getPath());
            if (extractor.getTrackCount() != layout.tracks.length) throw new IOException("the joined file has " + extractor.getTrackCount() + " tracks");
            long durationUs = 0;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                if (format.containsKey(MediaFormat.KEY_DURATION)) durationUs = Math.max(durationUs, format.getLong(MediaFormat.KEY_DURATION));
                extractor.selectTrack(i);
            }
            if (durationUs <= layout.lastPartUs) throw new IOException("the joined file lasts " + durationUs + " us only");
            extractor.seekTo(durationUs - 1000000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            if (extractor.getSampleTime() < 0 || extractor.readSampleData(ByteBuffer.allocateDirect(1024 * 1024), 0) <= 0) {
                throw new IOException("the end of the joined file can't be read");
            }
        } catch (RuntimeException e) {
            throw new IOException("the joined file can't be read: " + e, e);
        } finally {
            extractor.release();
        }
    }

    private static final class Unreadable extends IOException {
        Unreadable(String message) {
            super(message);
        }
    }

    private static final class Box {
        final String type;
        final int start;
        final int body;
        final int end;

        Box(String type, int start, int body, int end) {
            this.type = type;
            this.start = start;
            this.body = body;
            this.end = end;
        }

        byte[] bytes(byte[] data) {
            return Arrays.copyOfRange(data, start, end);
        }
    }

    private static List<Box> boxes(byte[] data, int from, int to) throws IOException {
        List<Box> list = new ArrayList<Box>();
        int at = from;
        while (at + 8 <= to) {
            long size = u32(data, at);
            int header = 8;
            if (size == 1) {
                size = u64(data, at + 8);
                header = 16;
            } else if (size == 0) {
                size = to - at;
            }
            if (size < header || at + size > to) throw new IOException("broken box at " + at);
            list.add(new Box(new String(data, at + 4, 4, "ISO-8859-1"), at, at + header, (int) (at + size)));
            at += (int) size;
        }
        return list;
    }

    private static Box only(List<Box> boxes, String type, boolean required) throws IOException {
        Box found = null;
        for (Box box : boxes) {
            if (box.type.equals(type)) {
                if (found != null) throw new IOException("two " + type);
                found = box;
            }
        }
        if (found == null && required) throw new IOException("no " + type);
        return found;
    }

    private static void onlyOf(List<Box> boxes, String... types) throws IOException {
        List<String> allowed = Arrays.asList(types);
        for (Box box : boxes) if (!allowed.contains(box.type)) throw new IOException("unexpected " + box.type);
    }

    private static long u32(byte[] b, int at) {
        return ((b[at] & 0xFFL) << 24) | ((b[at + 1] & 0xFFL) << 16) | ((b[at + 2] & 0xFFL) << 8) | (b[at + 3] & 0xFFL);
    }

    private static long u64(byte[] b, int at) {
        return (u32(b, at) << 32) | u32(b, at + 4);
    }

    private static final class Track {
        String handler;
        byte[] tkhd, mdhd, hdlr, mediaHeader, dinf, stsd;
        long timescale;
        long delayMovie;//the empty edit at the start, in the movie time scale
        long[] runCounts, runDeltas;//stts
        int[] sizes;
        long[] chunkOffsets;
        int[] chunkSamples;
        int[] sync;//stss sample numbers from 1, null: every sample is a sync one
        long duration;//in the time scale of the track

        int sampleCount() {
            return sizes.length;
        }

        long[] chunkSizes() {
            long[] chunkSizes = new long[chunkOffsets.length];
            int sample = 0;
            for (int c = 0; c < chunkOffsets.length; c++) {
                for (int i = 0; i < chunkSamples[c]; i++) chunkSizes[c] += sizes[sample++];
            }
            return chunkSizes;
        }
    }

    private static final class Mp4 {
        File file;
        byte[] ftyp, mvhd;
        long movieTimescale;
        List<byte[]> moovExtras = new ArrayList<byte[]>();//meta, udta: taken from the first part as they are
        Track video, audio;

        //a part cut by a crash has no index (moov): Unreadable, skipped; another layout: IOException, remuxed
        static Mp4 read(File file) throws IOException {
            Mp4 mp4 = new Mp4();
            mp4.file = file;
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                long length = raf.length(), at = 0;
                byte[] header = new byte[16];
                long moovAt = -1, moovSize = 0;
                while (at + 8 <= length) {
                    raf.seek(at);
                    raf.readFully(header, 0, 8);
                    long size = u32(header, 0);
                    String type = new String(header, 4, 4, "ISO-8859-1");
                    if (size == 1) {
                        raf.readFully(header, 8, 8);
                        size = u64(header, 8);
                    } else if (size == 0) {
                        size = length - at;
                    }
                    if (size < 8 || at + size > length) throw new Unreadable("cut " + type + " at " + at);
                    if (type.equals("ftyp")) {
                        mp4.ftyp = new byte[(int) size];
                        raf.seek(at);
                        raf.readFully(mp4.ftyp);
                    } else if (type.equals("moov")) {
                        if (moovAt >= 0) throw new IOException("two moov");
                        moovAt = at;
                        moovSize = size;
                    } else if (!type.equals("mdat") && !type.equals("free") && !type.equals("skip") && !type.equals("wide")) {
                        throw new IOException("unexpected " + type);
                    }
                    at += size;
                }
                if (moovAt < 0) throw new Unreadable("no moov");
                if (mp4.ftyp == null) throw new IOException("no ftyp");
                if (moovSize > Integer.MAX_VALUE) throw new IOException("moov too big");
                byte[] moov = new byte[(int) moovSize];
                raf.seek(moovAt);
                raf.readFully(moov);
                mp4.parseMoov(moov, length);
            } finally {
                raf.close();
            }
            return mp4;
        }

        private void parseMoov(byte[] data, long fileLength) throws IOException {
            List<Box> children = boxes(data, boxes(data, 0, data.length).get(0).body, data.length);
            onlyOf(children, "mvhd", "trak", "meta", "udta");
            Box mvhdBox = only(children, "mvhd", true);
            mvhd = mvhdBox.bytes(data);
            movieTimescale = u32(data, mvhdBox.body + (data[mvhdBox.body] == 1 ? 20 : 12));
            for (Box box : children) {
                if (box.type.equals("meta") || box.type.equals("udta")) moovExtras.add(box.bytes(data));
                if (!box.type.equals("trak")) continue;
                Track track = parseTrak(data, box);
                for (long offset : track.chunkOffsets) if (offset >= fileLength) throw new IOException("a chunk out of the file");
                if (track.handler.equals("vide") && video == null) video = track;
                else if (track.handler.equals("soun") && audio == null) audio = track;
                else throw new IOException("another track " + track.handler);
            }
            if (video == null && audio == null) throw new IOException("no tracks");
        }

        private Track parseTrak(byte[] data, Box trak) throws IOException {
            Track track = new Track();
            List<Box> trakBoxes = boxes(data, trak.body, trak.end);
            onlyOf(trakBoxes, "tkhd", "edts", "mdia");
            track.tkhd = only(trakBoxes, "tkhd", true).bytes(data);
            Box edts = only(trakBoxes, "edts", false);
            if (edts != null) track.delayMovie = parseDelay(data, edts);
            Box mdia = only(trakBoxes, "mdia", true);
            List<Box> mdiaBoxes = boxes(data, mdia.body, mdia.end);
            onlyOf(mdiaBoxes, "mdhd", "hdlr", "minf");
            Box mdhd = only(mdiaBoxes, "mdhd", true);
            track.mdhd = mdhd.bytes(data);
            track.timescale = u32(data, mdhd.body + (data[mdhd.body] == 1 ? 20 : 12));
            Box hdlr = only(mdiaBoxes, "hdlr", true);
            track.hdlr = hdlr.bytes(data);
            track.handler = new String(data, hdlr.body + 8, 4, "ISO-8859-1");
            Box minf = only(mdiaBoxes, "minf", true);
            List<Box> minfBoxes = boxes(data, minf.body, minf.end);
            onlyOf(minfBoxes, "vmhd", "smhd", "dinf", "stbl");
            Box mediaHeader = only(minfBoxes, track.handler.equals("vide") ? "vmhd" : "smhd", true);
            track.mediaHeader = mediaHeader.bytes(data);
            track.dinf = only(minfBoxes, "dinf", true).bytes(data);
            Box stbl = only(minfBoxes, "stbl", true);
            List<Box> stblBoxes = boxes(data, stbl.body, stbl.end);
            onlyOf(stblBoxes, "stsd", "stts", "stss", "stsz", "stsc", "stco", "co64");
            Box stsd = only(stblBoxes, "stsd", true);
            if (u32(data, stsd.body + 4) != 1) throw new IOException("several sample descriptions");
            track.stsd = stsd.bytes(data);
            parseStts(data, only(stblBoxes, "stts", true), track);
            parseStsz(data, only(stblBoxes, "stsz", true), track);
            Box stco = only(stblBoxes, "stco", false), co64 = only(stblBoxes, "co64", false);
            if ((stco == null) == (co64 == null)) throw new IOException("no or two chunk offset tables");
            parseChunkOffsets(data, stco != null ? stco : co64, co64 != null, track);
            parseStsc(data, only(stblBoxes, "stsc", true), track);
            Box stss = only(stblBoxes, "stss", false);
            if (stss != null) {
                int n = (int) u32(data, stss.body + 4);
                track.sync = new int[n];
                for (int i = 0; i < n; i++) track.sync[i] = (int) u32(data, stss.body + 8 + 4 * i);
            }
            return track;
        }

        //only empty edits (a start delay) and then the whole media from its start
        private static long parseDelay(byte[] data, Box edts) throws IOException {
            List<Box> edtsBoxes = boxes(data, edts.body, edts.end);
            onlyOf(edtsBoxes, "elst");
            Box elst = only(edtsBoxes, "elst", true);
            boolean v1 = data[elst.body] == 1;
            int n = (int) u32(data, elst.body + 4), entry = v1 ? 20 : 12, at = elst.body + 8;
            long delay = 0;
            boolean media = false;
            for (int i = 0; i < n; i++, at += entry) {
                long duration = v1 ? u64(data, at) : u32(data, at);
                long mediaTime = v1 ? u64(data, at + 8) : (int) u32(data, at + 4);
                long rate = u32(data, at + (v1 ? 16 : 8));
                if (media) throw new IOException("several edits");
                if (mediaTime == -1) delay += duration;
                else if (mediaTime == 0 && rate == 0x10000) media = true;
                else throw new IOException("an edit with a media time " + mediaTime);
            }
            return delay;
        }

        private static void parseStts(byte[] data, Box stts, Track track) {
            int n = (int) u32(data, stts.body + 4);
            track.runCounts = new long[n];
            track.runDeltas = new long[n];
            for (int i = 0; i < n; i++) {
                track.runCounts[i] = u32(data, stts.body + 8 + 8 * i);
                track.runDeltas[i] = u32(data, stts.body + 12 + 8 * i);
                track.duration += track.runCounts[i] * track.runDeltas[i];
            }
        }

        private static void parseStsz(byte[] data, Box stsz, Track track) throws IOException {
            long fixed = u32(data, stsz.body + 4);
            int n = (int) u32(data, stsz.body + 8);
            track.sizes = new int[n];
            for (int i = 0; i < n; i++) track.sizes[i] = (int) (fixed != 0 ? fixed : u32(data, stsz.body + 12 + 4 * i));
            long samples = 0;
            for (long count : track.runCounts) samples += count;
            if (samples != n) throw new IOException("stts has " + samples + " samples, stsz " + n);
        }

        private static void parseChunkOffsets(byte[] data, Box box, boolean wide, Track track) {
            int n = (int) u32(data, box.body + 4);
            track.chunkOffsets = new long[n];
            for (int i = 0; i < n; i++) track.chunkOffsets[i] = wide ? u64(data, box.body + 8 + 8 * i) : u32(data, box.body + 8 + 4 * i);
        }

        private static void parseStsc(byte[] data, Box stsc, Track track) throws IOException {
            int n = (int) u32(data, stsc.body + 4), chunks = track.chunkOffsets.length;
            track.chunkSamples = new int[chunks];
            long total = 0;
            for (int i = 0; i < n; i++) {
                int at = stsc.body + 8 + 12 * i;
                int firstChunk = (int) u32(data, at), perChunk = (int) u32(data, at + 4);
                if (u32(data, at + 8) != 1) throw new IOException("another sample description");
                int nextChunk = i + 1 < n ? (int) u32(data, at + 12) : chunks + 1;
                if (firstChunk < 1 || nextChunk <= firstChunk || nextChunk > chunks + 1) throw new IOException("broken stsc");
                for (int c = firstChunk; c < nextChunk; c++) {
                    track.chunkSamples[c - 1] = perChunk;
                    total += perChunk;
                }
            }
            if (total != track.sizes.length) throw new IOException("stsc has " + total + " samples, stsz " + track.sizes.length);
        }

        void checkSameAs(Mp4 part) throws IOException {
            sameTrack(video, part.video);
            sameTrack(audio, part.audio);
        }

        private static void sameTrack(Track a, Track b) throws IOException {
            if (a == null && b == null) return;
            if (a == null || b == null) throw new IOException("a part with other tracks");
            if (a.timescale != b.timescale || !Arrays.equals(a.stsd, b.stsd)) throw new IOException("a part in another format");
            if ((a.sync == null) != (b.sync == null)) throw new IOException("a part with other sync samples");
        }
    }

    //where everything goes in the joined file: the tables of each track and the blocks of bytes to copy
    private static final class Layout {
        final List<Mp4> parts;
        final ByteBuffer silence;
        final Track[] tracks;
        final long[][] mediaStarts;//[part][track], in the time scale of the track
        long lastPartUs;

        Layout(List<Mp4> parts, List<Long> startsUs, ByteBuffer silence) {
            this.parts = parts;
            Mp4 first = parts.get(0);
            tracks = first.video != null && first.audio != null ? new Track[]{first.video, first.audio}
                    : new Track[]{first.video != null ? first.video : first.audio};
            this.silence = first.audio != null ? silence : null;
            mediaStarts = new long[parts.size()][tracks.length];
            long offsetUs = 0, endUs = 0;
            for (int p = 0; p < parts.size(); p++) {
                Mp4 part = parts.get(p);
                offsetUs = p == 0 ? 0 : Math.max(startsUs.get(p) - startsUs.get(0), endUs);
                for (int t = 0; t < tracks.length; t++) {
                    Track track = trackOf(part, t), firstTrack = trackOf(first, t);
                    long delayUs = track.delayMovie * 1000000 / part.movieTimescale - firstTrack.delayMovie * 1000000 / first.movieTimescale;
                    mediaStarts[p][t] = p == 0 ? 0 : Math.max(0, (offsetUs + delayUs) * track.timescale / 1000000);
                    long trackEndUs = offsetUs + track.delayMovie * 1000000 / part.movieTimescale + track.duration * 1000000 / track.timescale;
                    endUs = Math.max(endUs, trackEndUs + 100000);
                }
            }
            lastPartUs = offsetUs;
        }

        Track trackOf(Mp4 part, int t) {
            return tracks[t].handler.equals("vide") ? part.video : part.audio;
        }

        void write(File out) throws IOException {
            int n = tracks.length;
            Table[] tables = new Table[n];
            for (int t = 0; t < n; t++) tables[t] = new Table();
            RandomAccessFile raf = new RandomAccessFile(out, "rw");
            try {
                raf.setLength(0);
                FileChannel channel = raf.getChannel();
                Mp4 first = parts.get(0);
                channel.write(ByteBuffer.wrap(first.ftyp));
                long mdatAt = channel.position();
                ByteBuffer mdatHeader = ByteBuffer.allocate(16);
                mdatHeader.putInt(1).put("mdat".getBytes("ISO-8859-1")).putLong(0).flip();
                channel.write(mdatHeader);
                for (int p = 0; p < parts.size(); p++) {
                    Mp4 part = parts.get(p);
                    for (int t = 0; t < n; t++) {
                        Track track = trackOf(part, t);
                        Table table = tables[t];
                        long gap = mediaStarts[p][t] - table.duration;
                        if (p > 0 && gap > 0) {
                            if (track == part.audio && silence != null) writeSilence(channel, table, gap, track);
                            else table.extendLast(gap);
                        }
                    }
                    copyMedia(channel, part, tables);
                }
                long mdatEnd = channel.position();
                ByteBuffer size = ByteBuffer.allocate(8);
                size.putLong(mdatEnd - mdatAt).flip();
                channel.write(size, mdatAt + 8);
                channel.position(mdatEnd);
                channel.write(ByteBuffer.wrap(moov(tables)));
                channel.force(false);
            } finally {
                raf.close();
            }
        }

        //silent frames in the gap, the remainder goes to the last one; frames of 1024 samples, a chunk written at once
        private void writeSilence(FileChannel channel, Table table, long gap, Track audio) throws IOException {
            int rate = sampleRate(audio);
            long frameDelta = audio.timescale * 1024 / (rate > 0 ? rate : audio.timescale);
            long frames = frameDelta > 0 ? gap / frameDelta : 0;
            if (frames == 0) {
                table.extendLast(gap);
                return;
            }
            int frameSize = silence.remaining();
            ByteBuffer chunk = ByteBuffer.allocate(SILENCE_CHUNK_SAMPLES * frameSize);
            for (long written = 0; written < frames; ) {
                int inChunk = (int) Math.min(SILENCE_CHUNK_SAMPLES, frames - written);
                table.addChunk(channel.position(), inChunk);
                chunk.clear();
                for (int i = 0; i < inChunk; i++) {
                    chunk.put(silence.duplicate());
                    table.addSample(frameSize, frameDelta, false);
                }
                chunk.flip();
                while (chunk.hasRemaining()) channel.write(chunk);
                written += inChunk;
            }
            table.extendLast(gap - frames * frameDelta);
        }

        //the chunks of all tracks in the order of the file, contiguous ones as one block
        private void copyMedia(FileChannel channel, Mp4 part, Table[] tables) throws IOException {
            int n = tracks.length;
            List<long[]> chunks = new ArrayList<long[]>();//offset, size, track, chunk
            long[][] newOffsets = new long[n][];
            for (int t = 0; t < n; t++) {
                Track track = trackOf(part, t);
                long[] sizes = track.chunkSizes();
                newOffsets[t] = new long[sizes.length];
                for (int c = 0; c < sizes.length; c++) chunks.add(new long[]{track.chunkOffsets[c], sizes[c], t, c});
            }
            java.util.Collections.sort(chunks, new java.util.Comparator<long[]>() {
                @Override
                public int compare(long[] a, long[] b) {
                    return a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0;
                }
            });
            FileInputStream in = new FileInputStream(part.file);
            try {
                FileChannel source = in.getChannel();
                int i = 0;
                while (i < chunks.size()) {
                    long blockStart = chunks.get(i)[0], blockEnd = blockStart;
                    long target = channel.position();
                    int j = i;
                    while (j < chunks.size() && chunks.get(j)[0] == blockEnd) {
                        long[] chunk = chunks.get(j);
                        newOffsets[(int) chunk[2]][(int) chunk[3]] = target + (chunk[0] - blockStart);
                        blockEnd += chunk[1];
                        j++;
                    }
                    if (j == i) throw new IOException("overlapping chunks at " + blockStart);
                    for (long copied = 0; copied < blockEnd - blockStart; ) {
                        long count = source.transferTo(blockStart + copied, blockEnd - blockStart - copied, channel);
                        if (count <= 0) throw new IOException("the part ended before its chunk at " + (blockStart + copied));
                        copied += count;
                    }
                    i = j;
                }
            } finally {
                in.close();
            }
            for (int t = 0; t < n; t++) {
                Track track = trackOf(part, t);
                Table table = tables[t];
                int firstSample = table.sizes.size();
                for (int c = 0; c < track.chunkOffsets.length; c++) table.addChunk(newOffsets[t][c], track.chunkSamples[c]);
                int sample = 0;
                for (int r = 0; r < track.runCounts.length; r++) {
                    for (long k = 0; k < track.runCounts[r]; k++) table.addSample(track.sizes[sample++], track.runDeltas[r], track.sync == null);
                }
                if (track.sync != null) for (int s : track.sync) table.sync.add(firstSample + s);
            }
        }

        private byte[] moov(Table[] tables) throws IOException {
            Mp4 first = parts.get(0);
            ByteArrayOutputStream traks = new ByteArrayOutputStream();
            long movieDuration = 0;
            for (int t = 0; t < tracks.length; t++) {
                Track track = tracks[t];
                Table table = tables[t];
                long mediaMovie = table.duration * first.movieTimescale / track.timescale;
                long trackMovie = track.delayMovie + mediaMovie;
                movieDuration = Math.max(movieDuration, trackMovie);
                ByteArrayOutputStream stbl = new ByteArrayOutputStream();
                stbl.write(track.stsd);
                stbl.write(table.stts());
                if (track.sync != null) stbl.write(table.stss());
                stbl.write(table.stsz());
                stbl.write(table.stsc());
                stbl.write(table.chunkOffsets());
                ByteArrayOutputStream minf = new ByteArrayOutputStream();
                minf.write(track.mediaHeader);
                minf.write(track.dinf);
                minf.write(box("stbl", stbl.toByteArray()));
                ByteArrayOutputStream mdia = new ByteArrayOutputStream();
                mdia.write(withDuration(track.mdhd, table.duration, 12));
                mdia.write(track.hdlr);
                mdia.write(box("minf", minf.toByteArray()));
                ByteArrayOutputStream trak = new ByteArrayOutputStream();
                trak.write(withDuration(track.tkhd, trackMovie, 16));
                if (track.delayMovie > 0) trak.write(edts(track.delayMovie, mediaMovie));
                trak.write(box("mdia", mdia.toByteArray()));
                traks.write(box("trak", trak.toByteArray()));
            }
            ByteArrayOutputStream moov = new ByteArrayOutputStream();
            moov.write(withDuration(first.mvhd, movieDuration, 12));
            for (byte[] extra : first.moovExtras) moov.write(extra);
            moov.write(traks.toByteArray());
            return box("moov", moov.toByteArray());
        }
    }

    private static int sampleRate(Track audio) {
        //AudioSampleEntry in stsd: box header 8 + full box 4 + entry count 4, then the entry: header 8, 16 bytes, rate 16.16
        return (int) (u32(audio.stsd, 16 + 8 + 16 + 8) >>> 16);
    }

    //the joined tables of one track
    private static final class Table {
        final IntList sizes = new IntList();
        final IntList sync = new IntList();
        final List<long[]> runs = new ArrayList<long[]>();//count, delta
        final List<Long> chunkOffsets = new ArrayList<Long>();
        final IntList chunkSamples = new IntList();
        long duration;

        void addSample(int size, long delta, boolean isSync) {
            sizes.add(size);
            if (isSync) sync.add(sizes.size());
            long[] last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            if (last != null && last[1] == delta) last[0]++;
            else runs.add(new long[]{1, delta});
            duration += delta;
        }

        //the last sample lasts over a gap: the picture holds
        void extendLast(long by) {
            if (by <= 0 || runs.isEmpty()) return;
            long[] last = runs.get(runs.size() - 1);
            if (last[0] > 1) {
                last[0]--;
                runs.add(new long[]{1, last[1] + by});
            } else {
                last[1] += by;
            }
            duration += by;
        }

        void addChunk(long offset, int samples) {
            chunkOffsets.add(offset);
            chunkSamples.add(samples);
        }

        byte[] stts() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0);
            out.writeInt(runs.size());
            for (long[] run : runs) {
                if (run[1] > 0xFFFFFFFFL) throw new IOException("a gap too long for stts");
                out.writeInt((int) run[0]);
                out.writeInt((int) run[1]);
            }
            return box("stts", bytes.toByteArray());
        }

        byte[] stss() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0);
            out.writeInt(sync.size());
            for (int i = 0; i < sync.size(); i++) out.writeInt(sync.get(i));
            return box("stss", bytes.toByteArray());
        }

        byte[] stsz() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(12 + 4 * sizes.size());
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(sizes.size());
            for (int i = 0; i < sizes.size(); i++) out.writeInt(sizes.get(i));
            return box("stsz", bytes.toByteArray());
        }

        byte[] stsc() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            List<int[]> entries = new ArrayList<int[]>();
            for (int c = 0; c < chunkSamples.size(); c++) {
                if (entries.isEmpty() || entries.get(entries.size() - 1)[1] != chunkSamples.get(c)) entries.add(new int[]{c + 1, chunkSamples.get(c)});
            }
            out.writeInt(0);
            out.writeInt(entries.size());
            for (int[] entry : entries) {
                out.writeInt(entry[0]);
                out.writeInt(entry[1]);
                out.writeInt(1);
            }
            return box("stsc", bytes.toByteArray());
        }

        byte[] chunkOffsets() throws IOException {
            boolean wide = false;
            for (long offset : chunkOffsets) wide |= offset > 0xFFFFFFFFL;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0);
            out.writeInt(chunkOffsets.size());
            for (long offset : chunkOffsets) {
                if (wide) out.writeLong(offset);
                else out.writeInt((int) offset);
            }
            return box(wide ? "co64" : "stco", bytes.toByteArray());
        }
    }

    private static final class IntList {
        private int[] values = new int[1024];
        private int size;

        void add(int value) {
            if (size == values.length) values = Arrays.copyOf(values, size * 2);
            values[size++] = value;
        }

        int get(int i) {
            return values[i];
        }

        int size() {
            return size;
        }
    }

    private static byte[] edts(long delayMovie, long mediaMovie) throws IOException {
        boolean v1 = delayMovie > 0xFFFFFFFFL || mediaMovie > 0xFFFFFFFFL;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(v1 ? 0x01000000 : 0);
        out.writeInt(2);
        if (v1) {
            out.writeLong(delayMovie);
            out.writeLong(-1);
        } else {
            out.writeInt((int) delayMovie);
            out.writeInt(-1);
        }
        out.writeInt(0x10000);
        if (v1) {
            out.writeLong(mediaMovie);
            out.writeLong(0);
        } else {
            out.writeInt((int) mediaMovie);
            out.writeInt(0);
        }
        out.writeInt(0x10000);
        return box("edts", box("elst", bytes.toByteArray()));
    }

    /**
     * mvhd, tkhd and mdhd with a new duration; version 0 is rewritten as 1 when it does not fit in 32 bits.
     * @param durationAt where the duration is in version 0 after the full box header: 12 for mvhd and mdhd, 16 for tkhd
     */
    private static byte[] withDuration(byte[] box, long duration, int durationAt) throws IOException {
        int body = 8;
        if (u32(box, 0) != box.length) throw new IOException("a header box of another size");
        if (box[body] == 1) {//the times before the duration take 64 bits
            byte[] copy = box.clone();
            ByteBuffer.wrap(copy).putLong(body + 4 + durationAt + 8, duration);
            return copy;
        }
        if (duration <= 0xFFFFFFFFL) {
            byte[] copy = box.clone();
            ByteBuffer.wrap(copy).putInt(body + 4 + durationAt, (int) duration);
            return copy;
        }
        //version 1: the creation and modification times and the duration take 64 bits
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        ByteBuffer in = ByteBuffer.wrap(box, body, box.length - body);
        out.writeInt(0x01000000 | (in.getInt() & 0xFFFFFF));
        out.writeLong(in.getInt() & 0xFFFFFFFFL);
        out.writeLong(in.getInt() & 0xFFFFFFFFL);
        out.writeInt(in.getInt());//the time scale of mvhd and mdhd, the track id of tkhd
        if (durationAt == 16) out.writeInt(in.getInt());//tkhd: reserved
        in.getInt();
        out.writeLong(duration);
        byte[] rest = new byte[in.remaining()];
        in.get(rest);
        out.write(rest);
        return box(new String(box, 4, 4, "ISO-8859-1"), bytes.toByteArray());
    }

    private static byte[] box(String type, byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + 8);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(payload.length + 8);
        out.write(type.getBytes("ISO-8859-1"));
        out.write(payload);
        return bytes.toByteArray();
    }
}
