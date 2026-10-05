package com.devlabs.aulaflix.service;

import static com.devlabs.aulaflix.StoredVideos.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.PropertyChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The boxes are built by hand, so each test shows the layout it reads; the ffmpeg fixtures show a real file. Each
 * read the header makes is recorded, to show that it fetches box headers and the {@code moov} box, never the media.
 */
class Mp4HeaderTest {

    private static final byte[] FTYP = box("ftyp", "isom".getBytes(StandardCharsets.US_ASCII), new byte[12]);

    private static final Generator<Long> TIMESCALES = Generator.integers()
            .suchThat(timescale -> timescale != 0)
            .map(Integer::toUnsignedLong);

    private static final Generator<MovieTime> MOVIE_TIMES = Generator.anyOf(
            Generator.from(data -> {
                long timescale = data.generate(TIMESCALES);
                long seconds = data.generate(Generator.integers(0, Integer.MAX_VALUE));
                long part = data.generate(Generator.anyOf(
                        Generator.integers(0, (int) Math.min(timescale - 1, Integer.MAX_VALUE)).map(Integer::longValue),
                        Generator.sampledFrom(0L, timescale / 2, (timescale + 1) / 2, timescale - 1)));
                return new MovieTime(seconds * timescale + part, timescale);
            }),
            Generator.from(data -> new MovieTime(
                    (long) data.generate(Generator.integers()) << 32
                            | Integer.toUnsignedLong(data.generate(Generator.integers())),
                    data.generate(TIMESCALES))));

    @Test
    void readsTheDurationOfARealFaststartFile() {
        assertThat(Mp4Header.read(new RecordingFile(fixture("three-seconds.mp4"))).durationSeconds()).isEqualTo(3);
        assertThat(Mp4Header.read(new RecordingFile(fixture("five-seconds.mp4"))).durationSeconds()).isEqualTo(5);
    }

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource({"3000, 1000, 3", "2499, 1000, 2", "2500, 1000, 3", "1, 2, 1", "0, 90000, 0", "359999, 90000, 4"})
    void roundsTheDurationToTheNearestSecondAndAHalfUp(long duration, long timescale, int seconds) {
        RecordingFile file = new RecordingFile(concat(FTYP, box("moov", mvhd0(timescale, duration)), mdat(64)));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(seconds);
    }

    /**
     * Any unsigned 64-bit duration over any unsigned 32-bit timescale gives its exact quotient rounded half up, or a
     * refusal once that is more seconds than an {@code int} holds. Half the durations are whole seconds plus a part of
     * one, so that most fit, that part often a half or next to one; the other half are any 64 bits, so that most do
     * not.
     */
    @Test
    void roundsAnyDurationOverAnyTimescaleLikeExactDivisionDoes() {
        PropertyChecker.forAll(MOVIE_TIMES, time -> {
            BigDecimal exact = new BigDecimal(new BigInteger(Long.toUnsignedString(time.duration())))
                    .divide(BigDecimal.valueOf(time.timescale()), 0, RoundingMode.HALF_UP);
            RecordingFile file = new RecordingFile(concat(FTYP, box("moov", mvhd1(time.timescale(), time.duration()))));
            if (exact.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                assertThatIllegalArgumentException().isThrownBy(() -> Mp4Header.read(file));
                return true;
            }
            return Mp4Header.read(file).durationSeconds() == exact.intValueExact();
        });
    }

    @Test
    void readsTheSixtyFourBitDurationOfAVersionOneMovieHeader() {
        long fourHoursAtNinetyKilohertz = 4L * 3600 * 90_000;
        RecordingFile file = new RecordingFile(concat(FTYP, box("moov", mvhd1(90_000, fourHoursAtNinetyKilohertz))));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(4 * 3600);
    }

    @Test
    void readsATimescaleAboveTheLargestSignedInteger() {
        long timescale = 0xFFFF_FFFFL;
        RecordingFile file = new RecordingFile(concat(FTYP, box("moov", mvhd1(timescale, 7 * timescale))));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(7);
    }

    @Test
    void findsTheMovieHeaderAfterOtherBoxesOfTheMovie() {
        byte[] moov = box("moov", box("iods", new byte[16]), mvhd0(1000, 5000), box("trak", new byte[40]));
        RecordingFile file = new RecordingFile(concat(FTYP, moov));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(5);
    }

    @Test
    void fetchesTheBoxHeadersAndThenTheWholeMovieBoxInOneRead() {
        byte[] free = box("free", new byte[8]);
        byte[] moov = box("moov", mvhd0(1000, 3000));
        RecordingFile file = new RecordingFile(concat(FTYP, free, moov, mdat(1 << 20)));

        Mp4Header.read(file);

        int moovOffset = FTYP.length + free.length;
        assertThat(file.reads()).containsExactly(
                new Read(0, 16),
                new Read(FTYP.length, 16),
                new Read(moovOffset, 16),
                new Read(moovOffset, moov.length));
    }

    @Test
    void stepsOverTheMediaByItsHeaderWhenTheMovieBoxComesAfterIt() {
        byte[] mdat = mdat(1 << 20);
        RecordingFile file = new RecordingFile(concat(FTYP, mdat, box("moov", mvhd0(600, 1800))));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(3);
        assertThat(file.reads()).allSatisfy(read -> assertThat(read.length()).isLessThanOrEqualTo(128));
    }

    @Test
    void stepsOverABoxWhoseSizeIsInItsSixtyFourBitField() {
        byte[] payload = new byte[300];
        byte[] largeMdat = ByteBuffer.allocate(16 + payload.length)
                .putInt(1).put(ascii("mdat")).putLong(16 + payload.length).put(payload)
                .array();
        RecordingFile file = new RecordingFile(concat(FTYP, largeMdat, box("moov", mvhd0(1000, 4000))));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(4);
    }

    /** A size of 0 is the last box's, which runs to the end of the file. */
    @Test
    void readsAMovieBoxThatRunsToTheEndOfTheFile() {
        byte[] mvhd = mvhd0(1000, 6000);
        byte[] openEndedMoov = ByteBuffer.allocate(8 + mvhd.length).putInt(0).put(ascii("moov")).put(mvhd).array();
        RecordingFile file = new RecordingFile(concat(FTYP, mdat(64), openEndedMoov));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(6);
    }

    @Test
    void refusesAFileWithoutAMovieBox() {
        RecordingFile file = new RecordingFile(concat(FTYP, mdat(64)));

        assertThatIllegalArgumentException().isThrownBy(() -> Mp4Header.read(file));
    }

    @Test
    void refusesAMovieBoxWithoutAMovieHeader() {
        RecordingFile file = new RecordingFile(concat(FTYP, box("moov", box("trak", new byte[40]))));

        assertThatIllegalArgumentException().isThrownBy(() -> Mp4Header.read(file));
    }

    @Test
    void refusesALastBoxThatRunsToTheEndOfTheFileWithoutAMovieBox() {
        byte[] openEndedMdat = ByteBuffer.allocate(8 + 64).putInt(0).put(ascii("mdat")).array();
        RecordingFile file = new RecordingFile(concat(FTYP, openEndedMdat));

        assertThatIllegalArgumentException().isThrownBy(() -> Mp4Header.read(file));
    }

    @Test
    void refusesABoxSmallerThanItsOwnHeader() {
        byte[] broken = ByteBuffer.allocate(8).putInt(4).put(ascii("free")).array();
        RecordingFile file = new RecordingFile(concat(FTYP, broken, box("moov", mvhd0(1000, 3000))));

        assertThatIllegalArgumentException().isThrownBy(() -> Mp4Header.read(file));
    }

    @Test
    void refusesAMovieHeaderWithoutATimescale() {
        RecordingFile file = new RecordingFile(concat(FTYP, box("moov", mvhd0(0, 3000))));

        assertThatIllegalArgumentException().isThrownBy(() -> Mp4Header.read(file));
    }

    @Test
    void refusesAFileTooShortForABoxHeader() {
        assertThatIllegalArgumentException().isThrownBy(() -> Mp4Header.read(new RecordingFile(new byte[7])));
    }

    @Test
    void findsTheMovieBoxAmongTheFirstThirtyTwoTopLevelBoxesAndNoFurther() {
        byte[] moov = box("moov", mvhd0(1000, 3000));
        byte[] thirtyOneBoxes = concat(FTYP, concat(Collections.nCopies(30, box("free")).toArray(byte[][]::new)));

        assertThat(Mp4Header.read(new RecordingFile(concat(thirtyOneBoxes, moov))).durationSeconds()).isEqualTo(3);
        assertThatIllegalArgumentException().isThrownBy(
                () -> Mp4Header.read(new RecordingFile(concat(thirtyOneBoxes, box("free"), moov))));
    }

    /** An upload cut short: the movie box says it is longer than what is left of the file. */
    @Test
    void refusesAMovieBoxThatRunsPastTheEndOfTheFile() {
        byte[] moov = box("moov", mvhd0(1000, 3000));
        RecordingFile file = new RecordingFile(concat(FTYP, Arrays.copyOf(moov, moov.length - 1)));

        assertThatIllegalArgumentException().isThrownBy(() -> Mp4Header.read(file));
    }

    @Test
    void refusesAMovieBoxTheFileDoesNotReturnWhole() {
        byte[] content = concat(FTYP, box("moov", mvhd0(1000, 3000)), box("free", new byte[8]));
        Mp4Header.Source longerReads = new Mp4Header.Source() {
            @Override
            public long size() {
                return content.length;
            }

            @Override
            public byte[] read(long offset, int length) {
                return Arrays.copyOfRange(content, (int) offset, (int) offset + Math.min(length + 2,
                        content.length - (int) offset));
            }
        };

        assertThatIllegalArgumentException().isThrownBy(() -> Mp4Header.read(longerReads));
    }

    @Test
    void readsAMovieBoxOfUpTo64MebibytesAndRefusesALargerOneWithoutFetchingIt() {
        int limit = 64 * 1024 * 1024;
        byte[] mvhd = mvhd0(1000, 3000);
        byte[] moovHeader = ByteBuffer.allocate(8).putInt(limit).put(ascii("moov")).array();
        SparseFile atTheLimit = new SparseFile(concat(FTYP, moovHeader, mvhd), FTYP.length + limit);
        byte[] tooLargeHeader = ByteBuffer.allocate(8).putInt(limit + 1).put(ascii("moov")).array();
        SparseFile pastTheLimit = new SparseFile(concat(FTYP, tooLargeHeader, mvhd), FTYP.length + limit + 1);

        assertThat(Mp4Header.read(atTheLimit).durationSeconds()).isEqualTo(3);
        assertThatIllegalArgumentException().isThrownBy(() -> Mp4Header.read(pastTheLimit));
        assertThat(pastTheLimit.largestRead()).isLessThanOrEqualTo(16);
    }

    @ParameterizedTest(name = "version {0} with {1} bytes")
    @CsvSource({"0, 0", "0, 19", "1, 31"})
    void refusesAMovieHeaderCutShort(int version, int contentBytes) {
        RecordingFile file = new RecordingFile(concat(FTYP, box("moov", mvhd(version, contentBytes, 1000, 3000))));

        assertThatIllegalArgumentException().isThrownBy(() -> Mp4Header.read(file));
    }

    @ParameterizedTest(name = "version {0} with {1} bytes")
    @CsvSource({"0, 20", "1, 32"})
    void readsAMovieHeaderThatEndsRightAfterItsDuration(int version, int contentBytes) {
        RecordingFile file = new RecordingFile(concat(FTYP, box("moov", mvhd(version, contentBytes, 1000, 3000))));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(3);
    }

    @Test
    void readsADurationOfAsManySecondsAsAnIntegerHolds() {
        RecordingFile file = new RecordingFile(concat(FTYP, box("moov", mvhd1(1, Integer.MAX_VALUE))));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(Integer.MAX_VALUE);
    }

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource({"2147483648, 1", "4294967295, 2", "-1, 1"})
    void refusesADurationOfMoreSecondsThanAnIntegerHoldsOnceRounded(long duration, long timescale) {
        RecordingFile file = new RecordingFile(concat(FTYP, box("moov", mvhd1(timescale, duration))));

        assertThatIllegalArgumentException().isThrownBy(() -> Mp4Header.read(file));
    }

    /** A box: its 32-bit size, its type, then its content. */
    private static byte[] box(String type, byte[]... content) {
        byte[] body = concat(content);
        return ByteBuffer.allocate(8 + body.length).putInt(8 + body.length).put(ascii(type)).put(body).array();
    }

    /** A version 0 movie header: 32-bit times and duration, then the fields linking never reads. */
    private static byte[] mvhd0(long timescale, long duration) {
        return box("mvhd", ByteBuffer.allocate(4 + 16 + 80)
                .putInt(0).putInt(0).putInt(0).putInt((int) timescale).putInt((int) duration)
                .array());
    }

    /** A version 1 movie header: 64-bit times and duration around a 32-bit timescale. */
    private static byte[] mvhd1(long timescale, long duration) {
        return box("mvhd", ByteBuffer.allocate(4 + 28 + 80)
                .putInt(1 << 24).putLong(0).putLong(0).putInt((int) timescale).putLong(duration)
                .array());
    }

    /** A movie header of the version, with only its first bytes, which hold the timescale and duration. */
    private static byte[] mvhd(int version, int contentBytes, long timescale, long duration) {
        byte[] whole = version == 0 ? mvhd0(timescale, duration) : mvhd1(timescale, duration);
        return box("mvhd", Arrays.copyOfRange(whole, 8, 8 + contentBytes));
    }

    private static byte[] mdat(int mediaBytes) {
        return box("mdat", new byte[mediaBytes]);
    }

    private static byte[] ascii(String type) {
        return type.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Arrays.stream(parts).forEach(out::writeBytes);
        return out.toByteArray();
    }

    private record Read(long offset, int length) {
    }

    private record MovieTime(long duration, long timescale) {
    }

    /** A file of the declared size whose first bytes are given and the rest zeros, made only as far as it is read. */
    private static final class SparseFile implements Mp4Header.Source {

        private final byte[] start;
        private final long size;
        private int largestRead;

        SparseFile(byte[] start, long size) {
            this.start = start;
            this.size = size;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public byte[] read(long offset, int length) {
            largestRead = Math.max(largestRead, length);
            byte[] bytes = new byte[(int) Math.min(length, size - offset)];
            if (offset < start.length) {
                System.arraycopy(start, (int) offset, bytes, 0, (int) Math.min(bytes.length, start.length - offset));
            }
            return bytes;
        }

        int largestRead() {
            return largestRead;
        }
    }

    /** A file held in memory, which answers a read past its end with what it has, as a ranged GET does. */
    private static final class RecordingFile implements Mp4Header.Source {

        private final byte[] content;
        private final List<Read> reads = new ArrayList<>();

        RecordingFile(byte[] content) {
            this.content = content;
        }

        @Override
        public long size() {
            return content.length;
        }

        @Override
        public byte[] read(long offset, int length) {
            reads.add(new Read(offset, length));
            return Arrays.copyOfRange(content, (int) offset, (int) Math.min(content.length, offset + length));
        }

        List<Read> reads() {
            return reads;
        }
    }
}
