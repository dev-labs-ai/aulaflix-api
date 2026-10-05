package com.devlabs.aulaflix.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * What linking reads from an MP4 file: its header, never its media. The top-level boxes are walked by their headers
 * alone, then the {@code moov} box is fetched whole, in one read, and its {@code mvhd} box gives the duration.
 */
final class Mp4Header {

    /** A 32-bit size and a type; a size of 1 means a 64-bit size follows, and 0 that the box runs to the end. */
    private static final int BOX_HEADER_BYTES = 8;
    private static final int LARGE_BOX_HEADER_BYTES = 16;

    /** A file an Admin encoded for a Lesson has a handful of top-level boxes and a moov of a few megabytes. */
    private static final int MAX_TOP_LEVEL_BOXES = 32;
    private static final int MAX_MOOV_BYTES = 64 * 1024 * 1024;

    /** The version, the flags, then the two times that come before the timescale: 32-bit in version 0. */
    private static final int MVHD_V0_BYTES_BEFORE_TIMESCALE = 12;
    private static final int MVHD_V1_BYTES_BEFORE_TIMESCALE = 20;

    private final int durationSeconds;

    private Mp4Header(int durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    /** The file, read range by range: a read past its end answers what there is, as a ranged GET does. */
    interface Source {

        long size();

        byte[] read(long offset, int length);
    }

    /** Refuses, with an {@link IllegalArgumentException}, a file whose header it cannot read. */
    static Mp4Header read(Source file) {
        long offset = 0;
        for (int boxes = 0; boxes < MAX_TOP_LEVEL_BOXES && offset < file.size(); boxes++) {
            long remaining = file.size() - offset;
            byte[] header = file.read(offset, (int) Math.min(LARGE_BOX_HEADER_BYTES, remaining));
            Box box = Box.parse(ByteBuffer.wrap(header), remaining);
            if (box.isOf("moov")) {
                return new Mp4Header(durationOf(movie(file, offset, box), box.headerBytes()));
            }
            offset += box.size();
        }
        throw unreadable("no moov box among the top-level boxes");
    }

    /** The {@code mvhd} duration, rounded to the nearest second, and up from a half. */
    int durationSeconds() {
        return durationSeconds;
    }

    /** Exactly the moov box: a read that answers other bytes means the file changed under it. */
    private static ByteBuffer movie(Source file, long offset, Box moov) {
        if (moov.size() > MAX_MOOV_BYTES) {
            throw unreadable("the moov box is larger than any Lesson's");
        }
        byte[] movie = file.read(offset, (int) moov.size());
        if (movie.length != moov.size()) {
            throw unreadable("the moov box was not read whole");
        }
        return ByteBuffer.wrap(movie);
    }

    private static int durationOf(ByteBuffer movie, int firstChild) {
        int position = firstChild;
        while (position < movie.limit()) {
            int remaining = movie.limit() - position;
            Box child = Box.parse(movie.slice(position, remaining), remaining);
            if (child.isOf("mvhd")) {
                return durationOfMovieHeader(movie.slice(position + child.headerBytes(),
                        (int) child.size() - child.headerBytes()));
            }
            position += (int) child.size();
        }
        throw unreadable("the moov box has no mvhd box");
    }

    private static int durationOfMovieHeader(ByteBuffer mvhd) {
        boolean sixtyFourBit = mvhd.remaining() > 0 && mvhd.get(0) == 1;
        int beforeTimescale = sixtyFourBit ? MVHD_V1_BYTES_BEFORE_TIMESCALE : MVHD_V0_BYTES_BEFORE_TIMESCALE;
        if (mvhd.remaining() < beforeTimescale + Integer.BYTES + (sixtyFourBit ? Long.BYTES : Integer.BYTES)) {
            throw unreadable("the mvhd box is cut short");
        }
        mvhd.position(beforeTimescale);
        long timescale = Integer.toUnsignedLong(mvhd.getInt());
        long duration = sixtyFourBit ? mvhd.getLong() : Integer.toUnsignedLong(mvhd.getInt());
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

    private static IllegalArgumentException unreadable(String reason) {
        return new IllegalArgumentException("Unreadable MP4 header: " + reason);
    }

    /** A box's type, the length of its header, and its whole size, header included. */
    private record Box(String type, int headerBytes, long size) {

        /** The box whose header starts the bytes, with {@code remaining} bytes left in the file or its parent. */
        static Box parse(ByteBuffer bytes, long remaining) {
            if (bytes.remaining() < BOX_HEADER_BYTES) {
                throw unreadable("a box header is cut short");
            }
            long size = Integer.toUnsignedLong(bytes.getInt());
            byte[] type = new byte[4];
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
            return new Box(new String(type, StandardCharsets.US_ASCII), headerBytes, size);
        }

        boolean isOf(String type) {
            return this.type.equals(type);
        }
    }
}
