package com.devlabs.aulaflix.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.devlabs.aulaflix.exception.AudioNotAacException;
import com.devlabs.aulaflix.exception.VideoNotFaststartException;
import com.devlabs.aulaflix.exception.VideoNotH264Exception;
import com.devlabs.aulaflix.exception.VideoNotMp4Exception;
import com.devlabs.aulaflix.exception.VideoTooShortException;

/**
 * What linking reads from an MP4 file: its header, never its media. The top-level boxes are walked by their headers
 * alone, then the {@code moov} box is fetched whole, in one read: its {@code mvhd} box gives the duration, and its
 * tracks the codecs of their samples. A file that is not a faststart H.264/AAC MP4 lasting a second, once rounded, is
 * refused (ADR 0007) for the first of its problems: no MP4 at all, then no faststart, then its video, its audio, and
 * last its duration, which no new encoding changes.
 */
final class Mp4Header {

    /** A 32-bit size and a type; a size of 1 means a 64-bit size follows, and 0 that the box runs to the end. */
    private static final int BOX_HEADER_BYTES = 8;
    private static final int LARGE_BOX_HEADER_BYTES = 16;

    /** A box's type, and a handler's, is four ASCII characters. */
    private static final int TYPE_BYTES = 4;

    /** A file type box opens with its major brand; QuickTime's lays a movie's tracks out its own way. */
    private static final int BRAND_BYTES = 4;
    private static final String QUICKTIME_BRAND = "qt  ";

    /** A file an Admin encoded for a Lesson has a handful of top-level boxes and a moov of a few megabytes. */
    private static final int MAX_TOP_LEVEL_BOXES = 32;
    private static final int MAX_MOOV_BYTES = 64 * 1024 * 1024;

    /** A handler box: its version and flags, a field always 0, then the handler type, which names a track's kind. */
    private static final int HDLR_BYTES_BEFORE_TYPE = 8;
    private static final String VIDEO_HANDLER = "vide";
    private static final String AUDIO_HANDLER = "soun";

    /** A sample description box: its version and flags, and the number of sample entries that follow. */
    private static final int STSD_BYTES_BEFORE_ENTRIES = 8;

    /**
     * Each sample entry's codec, written as a codecs parameter writes it (RFC 6381): H.264 with its parameter sets in
     * the entry or in band, and MPEG-4 Audio, as ffmpeg's AAC is, or one of the three profiles of MPEG-2 AAC.
     */
    private static final Set<String> H264_CODECS = Set.of("avc1", "avc3");
    private static final Set<String> AAC_CODECS = Set.of("mp4a.40", "mp4a.66", "mp4a.67", "mp4a.68");

    /**
     * MPEG-4 audio's format, which leaves the codec to the object type its esds box names: ffmpeg's MP3 in an MP4 is
     * {@code mp4a} too. Its sample entry holds the 8 bytes every sample entry opens with and 20 of its own, then boxes.
     */
    private static final String MPEG4_AUDIO_FORMAT = "mp4a";
    private static final int AUDIO_ENTRY_BYTES_BEFORE_BOXES = 28;

    /** An esds box: its version and flags, then an ES_Descriptor, which holds a DecoderConfigDescriptor. */
    private static final int ESDS_BYTES_BEFORE_DESCRIPTOR = 4;
    private static final int ES_DESCRIPTOR_TAG = 3;
    private static final int DECODER_CONFIG_DESCRIPTOR_TAG = 4;

    /** An ES_Descriptor's stream id, then its flags: each of three announces a field before the decoder's. */
    private static final int ES_ID_BYTES = 2;
    private static final int STREAM_DEPENDENCE_FLAG = 0x80;
    private static final int URL_FLAG = 0x40;
    private static final int OCR_STREAM_FLAG = 0x20;
    private static final int STREAM_ID_BYTES = 2;

    /** A descriptor's size takes one to four bytes, seven bits each, with the high bit set on all but the last. */
    private static final int SIZE_CONTINUES = 0x80;

    /** The version, the flags, then the two times that come before the timescale: 32-bit in version 0. */
    private static final int MVHD_V0_BYTES_BEFORE_TIMESCALE = 12;
    private static final int MVHD_V1_BYTES_BEFORE_TIMESCALE = 20;

    private final int durationSeconds;

    private Mp4Header(int durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    /**
     * The file, read range by range: a read past its end answers what there is, as a ranged GET does. Each read names
     * at least one byte, since a range of none is no range at all.
     */
    interface Source {

        long size();

        byte[] read(long offset, int length);
    }

    /** Refuses a file whose header it cannot read as not an MP4, and a file it can read for its first problem. */
    static Mp4Header read(Source file) {
        if (file.size() == 0) {
            throw unreadable("the file is empty");
        }
        long offset = fileType(file).size();
        for (int boxes = 1; boxes < MAX_TOP_LEVEL_BOXES && offset < file.size(); boxes++) {
            long remaining = file.size() - offset;
            byte[] header = file.read(offset, (int) Math.min(LARGE_BOX_HEADER_BYTES, remaining));
            BoxHeader box = BoxHeader.parse(ByteBuffer.wrap(header), remaining);
            if (box.isOf("moov")) {
                return acceptedHeaderOf(movie(file, offset, box));
            }
            if (box.isOf("mdat")) {
                throw new VideoNotFaststartException();
            }
            offset += box.size();
        }
        throw unreadable("no moov box among the top-level boxes");
    }

    /** The {@code mvhd} duration, rounded to the nearest second, and up from a half. */
    int durationSeconds() {
        return durationSeconds;
    }

    /**
     * The first box, which in an MP4 is a file type box. It is read with the major brand that opens its content, so
     * that a QuickTime movie, which has one too, is told apart in the same read.
     */
    private static BoxHeader fileType(Source file) {
        byte[] start = file.read(0, (int) Math.min(LARGE_BOX_HEADER_BYTES + BRAND_BYTES, file.size()));
        BoxHeader ftyp = BoxHeader.parse(ByteBuffer.wrap(start), file.size());
        if (!ftyp.isOf("ftyp") || ftyp.size() < ftyp.headerBytes() + BRAND_BYTES) {
            throw unreadable("the file does not start with a file type box");
        }
        if (new String(start, ftyp.headerBytes(), BRAND_BYTES, StandardCharsets.US_ASCII).equals(QUICKTIME_BRAND)) {
            throw unreadable("the file is a QuickTime movie");
        }
        return ftyp;
    }

    /** Exactly the moov box's content: a read that answers other bytes means the file changed under it. */
    private static ByteBuffer movie(Source file, long offset, BoxHeader moov) {
        if (moov.size() > MAX_MOOV_BYTES) {
            throw unreadable("the moov box is larger than any Lesson's");
        }
        byte[] movie = file.read(offset, (int) moov.size());
        if (movie.length != moov.size()) {
            throw new IllegalStateException("The moov box was not read whole: the file changed under it");
        }
        return ByteBuffer.wrap(movie).slice(moov.headerBytes(), movie.length - moov.headerBytes());
    }

    /**
     * The movie is read whole before any check, so that a file broken anywhere is refused as not an MP4. A fragmented
     * movie, which its {@code mvex} box announces, indexes none of its media: each fragment after it indexes its own.
     */
    private static Mp4Header acceptedHeaderOf(ByteBuffer movie) {
        List<Box> boxes = boxesOf(movie);
        int durationSeconds = durationOf(first(boxes, "mvhd"));
        List<Track> tracks = boxes.stream().filter(box -> box.isOf("trak")).map(box -> Track.of(box.content()))
                .toList();
        if (boxes.stream().anyMatch(box -> box.isOf("mvex"))) {
            throw new VideoNotFaststartException();
        }
        List<String> videoCodecs = codecsOf(tracks, VIDEO_HANDLER);
        if (videoCodecs.isEmpty() || !H264_CODECS.containsAll(videoCodecs)) {
            throw new VideoNotH264Exception();
        }
        if (!AAC_CODECS.containsAll(codecsOf(tracks, AUDIO_HANDLER))) {
            throw new AudioNotAacException();
        }
        if (durationSeconds < 1) {
            throw new VideoTooShortException();
        }
        return new Mp4Header(durationSeconds);
    }

    /** The codec of every sample of every track of the kind the handler type names. */
    private static List<String> codecsOf(List<Track> tracks, String handler) {
        return tracks.stream().filter(track -> track.handler().equals(handler))
                .flatMap(track -> track.codecs().stream())
                .toList();
    }

    /**
     * The codec a sample entry names: its format, but for MPEG-4 audio the format and the object type that its esds box
     * names, in hex: {@code mp4a.40} for ffmpeg's AAC, {@code mp4a.6B} for its MP3.
     */
    private static String codecOf(Box entry) {
        if (!entry.isOf(MPEG4_AUDIO_FORMAT)) {
            return entry.type();
        }
        ByteBuffer esds = first(boxesOf(after(entry.content(), AUDIO_ENTRY_BYTES_BEFORE_BOXES)), "esds");
        return "%s.%02X".formatted(MPEG4_AUDIO_FORMAT, objectTypeOf(after(esds, ESDS_BYTES_BEFORE_DESCRIPTOR)));
    }

    /**
     * The object type that opens the DecoderConfigDescriptor, within the ES_Descriptor, past the stream's id, its flags
     * and the fields they announce.
     */
    private static int objectTypeOf(ByteBuffer descriptors) {
        if (nextByte(descriptors) != ES_DESCRIPTOR_TAG) {
            throw unreadable("an esds box does not start with an ES_Descriptor");
        }
        skipSize(descriptors);
        skip(descriptors, ES_ID_BYTES);
        skipFieldsAnnouncedBy(nextByte(descriptors), descriptors);
        if (nextByte(descriptors) != DECODER_CONFIG_DESCRIPTOR_TAG) {
            throw unreadable("an ES_Descriptor has no DecoderConfigDescriptor");
        }
        skipSize(descriptors);
        return nextByte(descriptors);
    }

    /** In order: the id of the stream this one depends on, a URL, and the id of the stream of its clock. */
    private static void skipFieldsAnnouncedBy(int flags, ByteBuffer descriptors) {
        if ((flags & STREAM_DEPENDENCE_FLAG) != 0) {
            skip(descriptors, STREAM_ID_BYTES);
        }
        if ((flags & URL_FLAG) != 0) {
            skip(descriptors, nextByte(descriptors));
        }
        if ((flags & OCR_STREAM_FLAG) != 0) {
            skip(descriptors, STREAM_ID_BYTES);
        }
    }

    private static void skipSize(ByteBuffer descriptors) {
        int sizeByte;
        do {
            sizeByte = nextByte(descriptors);
        } while ((sizeByte & SIZE_CONTINUES) != 0);
    }

    /** Skipping past the end leaves nothing to read, which the next read refuses. */
    private static void skip(ByteBuffer descriptors, int bytes) {
        descriptors.position(Math.min(descriptors.limit(), descriptors.position() + bytes));
    }

    private static int nextByte(ByteBuffer descriptors) {
        if (!descriptors.hasRemaining()) {
            throw unreadable("an esds box is cut short");
        }
        return Byte.toUnsignedInt(descriptors.get());
    }

    private static int durationOf(ByteBuffer mvhd) {
        boolean sixtyFourBit = mvhd.remaining() > 0 && mvhd.get(0) == 1;
        int beforeTimescale = sixtyFourBit ? MVHD_V1_BYTES_BEFORE_TIMESCALE : MVHD_V0_BYTES_BEFORE_TIMESCALE;
        if (mvhd.remaining() < beforeTimescale + Integer.BYTES + (sixtyFourBit ? Long.BYTES : Integer.BYTES)) {
            throw unreadable("the mvhd box is cut short");
        }
        long timescale = Integer.toUnsignedLong(mvhd.getInt(beforeTimescale));
        int durationAt = beforeTimescale + Integer.BYTES;
        long duration = sixtyFourBit ? mvhd.getLong(durationAt) : Integer.toUnsignedLong(mvhd.getInt(durationAt));
        return roundedSeconds(duration, timescale);
    }

    /** The duration is unsigned, so it is divided as such; the remainder is below the 32-bit timescale. */
    private static int roundedSeconds(long duration, long timescale) {
        if (timescale == 0) {
            throw unreadable("the mvhd box has no timescale");
        }
        long seconds = Long.divideUnsigned(duration, timescale);
        long rounded = Long.remainderUnsigned(duration, timescale) * 2 >= timescale ? seconds + 1 : seconds;
        if (rounded < 0 || rounded > Integer.MAX_VALUE) {
            throw unreadable("the mvhd duration is longer than any video");
        }
        return (int) rounded;
    }

    /** The boxes that fill the content one after the other. */
    private static List<Box> boxesOf(ByteBuffer content) {
        List<Box> boxes = new ArrayList<>();
        int position = 0;
        while (position < content.remaining()) {
            int remaining = content.remaining() - position;
            BoxHeader header = BoxHeader.parse(content.slice(position, remaining), remaining);
            boxes.add(new Box(header.type(), content.slice(position + header.headerBytes(),
                    (int) header.size() - header.headerBytes())));
            position += (int) header.size();
        }
        return boxes;
    }

    /** The content of the first box of the type among them. */
    private static ByteBuffer first(List<Box> boxes, String type) {
        return boxes.stream().filter(box -> box.isOf(type)).findFirst()
                .orElseThrow(() -> unreadable("a " + type + " box is missing"))
                .content();
    }

    /** The content of the box at the end of the path of types, each the first of its type within the one before. */
    private static ByteBuffer descendantOf(ByteBuffer content, String... path) {
        ByteBuffer descendant = content;
        for (String type : path) {
            descendant = first(boxesOf(descendant), type);
        }
        return descendant;
    }

    /** The content past its first bytes, which hold fields of its own. */
    private static ByteBuffer after(ByteBuffer content, int bytes) {
        requireBytes(content, bytes);
        return content.slice(bytes, content.remaining() - bytes);
    }

    /** The content's first four bytes, as ASCII: a type. */
    private static String typeAtStartOf(ByteBuffer content) {
        requireBytes(content, TYPE_BYTES);
        byte[] type = new byte[TYPE_BYTES];
        content.get(0, type);
        return new String(type, StandardCharsets.US_ASCII);
    }

    private static void requireBytes(ByteBuffer content, int bytes) {
        if (content.remaining() < bytes) {
            throw unreadable("a box is too short for its fields");
        }
    }

    private static VideoNotMp4Exception unreadable(String reason) {
        return new VideoNotMp4Exception(reason);
    }

    /** A box held in memory: its type, and its content without its header. */
    private record Box(String type, ByteBuffer content) {

        boolean isOf(String type) {
            return this.type.equals(type);
        }
    }

    /**
     * A track's handler type, which names what it carries, and the codecs of the samples of a video or an audio track.
     * Any other track is read no further than its handler.
     */
    private record Track(String handler, List<String> codecs) {

        static Track of(ByteBuffer trak) {
            ByteBuffer media = descendantOf(trak, "mdia");
            String handler = typeAtStartOf(after(descendantOf(media, "hdlr"), HDLR_BYTES_BEFORE_TYPE));
            if (!handler.equals(VIDEO_HANDLER) && !handler.equals(AUDIO_HANDLER)) {
                return new Track(handler, List.of());
            }
            ByteBuffer stsd = descendantOf(media, "minf", "stbl", "stsd");
            return new Track(handler, boxesOf(after(stsd, STSD_BYTES_BEFORE_ENTRIES)).stream()
                    .map(Mp4Header::codecOf)
                    .toList());
        }
    }

    /** A box's type, the length of its header, and its whole size, header included, as its header gives them. */
    private record BoxHeader(String type, int headerBytes, long size) {

        /** The box whose header starts the bytes, with {@code remaining} bytes left in the file or its parent. */
        static BoxHeader parse(ByteBuffer bytes, long remaining) {
            if (bytes.remaining() < BOX_HEADER_BYTES) {
                throw unreadable("a box header is cut short");
            }
            long size = Integer.toUnsignedLong(bytes.getInt());
            byte[] type = new byte[TYPE_BYTES];
            bytes.get(type);
            int headerBytes = BOX_HEADER_BYTES;
            if (size == 1) {
                if (bytes.remaining() < Long.BYTES) {
                    throw unreadable("a 64-bit box size is cut short");
                }
                size = bytes.getLong();
                headerBytes = LARGE_BOX_HEADER_BYTES;
            } else if (size == 0) {
                size = remaining;
            }
            if (size < headerBytes || size > remaining) {
                throw unreadable("a box size does not fit the file");
            }
            return new BoxHeader(new String(type, StandardCharsets.US_ASCII), headerBytes, size);
        }

        boolean isOf(String type) {
            return this.type.equals(type);
        }
    }
}
