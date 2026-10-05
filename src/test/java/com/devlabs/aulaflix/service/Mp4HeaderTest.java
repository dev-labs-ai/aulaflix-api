package com.devlabs.aulaflix.service;

import static com.devlabs.aulaflix.StoredVideos.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

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
import java.util.stream.Stream;

import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.PropertyChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.devlabs.aulaflix.exception.AudioNotAacException;
import com.devlabs.aulaflix.exception.VideoNotFaststartException;
import com.devlabs.aulaflix.exception.VideoNotH264Exception;
import com.devlabs.aulaflix.exception.VideoNotMp4Exception;
import com.devlabs.aulaflix.exception.VideoTooShortException;

/**
 * The boxes are built by hand, so each test shows the layout it reads; the ffmpeg fixtures show a real file. Each
 * read the header makes is recorded, to show that it fetches box headers and the {@code moov} box, never the media.
 */
class Mp4HeaderTest {

    private static final byte[] FTYP = box("ftyp", "isom".getBytes(StandardCharsets.US_ASCII), new byte[12]);

    /** The video track of every movie but those of the tests about tracks: H.264, as linking requires. */
    private static final byte[] H264_TRACK = track("vide", sampleEntry("avc1"));

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

    /** ffmpeg writes, with faststart, a file type box of 32 bytes, then a movie box of 1890 bytes, then the media. */
    @Test
    void fetchesNoMoreOfARealFileThanItsFileTypeAndMovieBoxes() {
        RecordingFile file = new RecordingFile(fixture("three-seconds.mp4"));

        Mp4Header.read(file);

        assertThat(file.reads()).containsExactly(new Read(0, 20), new Read(32, 16), new Read(32, 1890));
    }

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource({"3000, 1000, 3", "2499, 1000, 2", "2500, 1000, 3", "1, 2, 1", "500, 1000, 1", "359999, 90000, 4"})
    void roundsTheDurationToTheNearestSecondAndAHalfUp(long duration, long timescale, int seconds) {
        RecordingFile file = new RecordingFile(concat(FTYP, movie(mvhd0(timescale, duration)), mdat(64)));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(seconds);
    }

    /** The duration is rounded before it is checked, so a video of half a second is long enough. */
    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource({"0, 90000", "499, 1000", "1, 3", "44999, 90000"})
    void refusesAVideoUnderASecondOnceRounded(long duration, long timescale) {
        RecordingFile file = new RecordingFile(concat(FTYP, movie(mvhd0(timescale, duration))));

        assertThatExceptionOfType(VideoTooShortException.class).isThrownBy(() -> Mp4Header.read(file));
    }

    @Test
    void refusesARealFileOfUnderHalfASecond() {
        assertThatExceptionOfType(VideoTooShortException.class)
                .isThrownBy(() -> Mp4Header.read(new RecordingFile(fixture("under-half-a-second.mp4"))));
    }

    /** The duration is refused last: encoding again fixes the rest, while the video must be another. */
    @Test
    void refusesTheFormatsBeforeTheDuration() {
        byte[] moov = box("moov", mvhd0(1000, 400), track("vide", sampleEntry("avc1")), track("soun", mp4a(0x6B)));

        assertNotAac(new RecordingFile(concat(FTYP, moov)));
    }

    /**
     * Any unsigned 64-bit duration over any unsigned 32-bit timescale gives its exact quotient rounded half up, or a
     * refusal: as too short under a second, as no MP4 once that is more seconds than an {@code int} holds. Half the
     * durations are whole seconds plus a part of one, so that most fit, that part often a half or next to one; the
     * other half are any 64 bits, so that most do not.
     */
    @Test
    void roundsAnyDurationOverAnyTimescaleLikeExactDivisionDoes() {
        PropertyChecker.forAll(MOVIE_TIMES, time -> {
            BigDecimal exact = new BigDecimal(new BigInteger(Long.toUnsignedString(time.duration())))
                    .divide(BigDecimal.valueOf(time.timescale()), 0, RoundingMode.HALF_UP);
            RecordingFile file = new RecordingFile(concat(FTYP, movie(mvhd1(time.timescale(), time.duration()))));
            if (exact.signum() == 0) {
                assertThatExceptionOfType(VideoTooShortException.class).isThrownBy(() -> Mp4Header.read(file));
                return true;
            }
            if (exact.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                assertNotMp4(file);
                return true;
            }
            return Mp4Header.read(file).durationSeconds() == exact.intValueExact();
        });
    }

    @Test
    void readsTheSixtyFourBitDurationOfAVersionOneMovieHeader() {
        long fourHoursAtNinetyKilohertz = 4L * 3600 * 90_000;
        RecordingFile file = new RecordingFile(concat(FTYP, movie(mvhd1(90_000, fourHoursAtNinetyKilohertz))));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(4 * 3600);
    }

    @Test
    void readsATimescaleAboveTheLargestSignedInteger() {
        long timescale = 0xFFFF_FFFFL;
        RecordingFile file = new RecordingFile(concat(FTYP, movie(mvhd1(timescale, 7 * timescale))));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(7);
    }

    @Test
    void findsTheMovieHeaderAfterOtherBoxesOfTheMovie() {
        byte[] moov = box("moov", box("iods", new byte[16]), mvhd0(1000, 5000), H264_TRACK);
        RecordingFile file = new RecordingFile(concat(FTYP, moov));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(5);
    }

    @Test
    void fetchesTheBoxHeadersAndThenTheWholeMovieBoxInOneRead() {
        byte[] free = box("free", new byte[8]);
        byte[] moov = movie(mvhd0(1000, 3000));
        RecordingFile file = new RecordingFile(concat(FTYP, free, moov, mdat(1 << 20)));

        Mp4Header.read(file);

        int moovOffset = FTYP.length + free.length;
        assertThat(file.reads()).containsExactly(
                new Read(0, 20),
                new Read(FTYP.length, 16),
                new Read(moovOffset, 16),
                new Read(moovOffset, moov.length));
    }

    @Test
    void stepsOverALargeBoxByItsHeaderWhenTheMovieBoxComesAfterIt() {
        byte[] free = box("free", new byte[1 << 20]);
        byte[] moov = movie(mvhd0(600, 1800));
        RecordingFile file = new RecordingFile(concat(FTYP, free, moov));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(3);
        assertThat(file.reads()).allSatisfy(read -> assertThat(read.length()).isLessThanOrEqualTo(moov.length));
    }

    /** The movie box is fetched only once no media came before it: neither is fetched here. */
    @Test
    void refusesAFileWhoseMediaComesBeforeItsMovieBoxByTheMediasHeader() {
        RecordingFile file = new RecordingFile(concat(FTYP, mdat(1 << 20), movie(mvhd0(1000, 3000))));

        assertThatExceptionOfType(VideoNotFaststartException.class).isThrownBy(() -> Mp4Header.read(file));
        assertThat(file.reads()).containsExactly(new Read(0, 20), new Read(FTYP.length, 16));
    }

    /** Without faststart, ffmpeg writes a file type box of 32 bytes, an empty free box, then the media. */
    @Test
    void refusesARealFileEncodedWithoutFaststartByItsFirstBoxHeaders() {
        RecordingFile file = new RecordingFile(fixture("not-faststart.mp4"));

        assertThatExceptionOfType(VideoNotFaststartException.class).isThrownBy(() -> Mp4Header.read(file));
        assertThat(file.reads()).containsExactly(new Read(0, 20), new Read(32, 16), new Read(40, 16));
    }

    /** A fragmented movie indexes none of the media: each fragment, after the movie box, indexes its own. */
    @Test
    void refusesAFragmentedMovie() {
        byte[] moov = box("moov", mvhd0(1000, 0), H264_TRACK, box("mvex", box("trex", new byte[24])));

        assertThatExceptionOfType(VideoNotFaststartException.class)
                .isThrownBy(() -> Mp4Header.read(new RecordingFile(concat(FTYP, moov, box("moof"), mdat(64)))));
    }

    /** ffmpeg's fragments, with {@code -movflags frag_keyframe+empty_moov}, leave the movie a duration of 0. */
    @Test
    void refusesARealFragmentedFileAsNotFaststartRatherThanTooShort() {
        assertThatExceptionOfType(VideoNotFaststartException.class)
                .isThrownBy(() -> Mp4Header.read(new RecordingFile(fixture("fragmented.mp4"))));
    }

    /** Media with no movie box at all is still media before it, which encoding with faststart puts right. */
    @Test
    void refusesMediaWithoutAnyMovieBoxAsNotFaststart() {
        byte[] openEndedMdat = ByteBuffer.allocate(8 + 64).putInt(0).put(ascii("mdat")).array();

        assertThatExceptionOfType(VideoNotFaststartException.class)
                .isThrownBy(() -> Mp4Header.read(new RecordingFile(concat(FTYP, openEndedMdat))));
    }

    @Test
    void stepsOverABoxWhoseSizeIsInItsSixtyFourBitField() {
        byte[] payload = new byte[300];
        byte[] largeFree = ByteBuffer.allocate(16 + payload.length)
                .putInt(1).put(ascii("free")).putLong(16 + payload.length).put(payload)
                .array();
        RecordingFile file = new RecordingFile(concat(FTYP, largeFree, movie(mvhd0(1000, 4000))));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(4);
    }

    /** A size of 0 is the last box's, which runs to the end of the file. */
    @Test
    void readsAMovieBoxThatRunsToTheEndOfTheFile() {
        byte[] movie = concat(mvhd0(1000, 6000), H264_TRACK);
        byte[] openEndedMoov = ByteBuffer.allocate(8 + movie.length).putInt(0).put(ascii("moov")).put(movie).array();
        RecordingFile file = new RecordingFile(concat(FTYP, box("free", new byte[64]), openEndedMoov));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(6);
    }

    /** An old QuickTime movie, or any file whose first bytes only look like a box. */
    @Test
    void refusesAFileThatDoesNotStartWithAFileTypeBox() {
        RecordingFile file = new RecordingFile(concat(movie(mvhd0(1000, 3000)), FTYP));

        assertNotMp4(file);
        assertThat(file.reads()).containsExactly(new Read(0, 20));
    }

    /** A QuickTime movie has a file type box too, but lays out its tracks its own way. */
    @Test
    void refusesAQuickTimeMovie() {
        byte[] quickTime = box("ftyp", ascii("qt  "), new byte[4], ascii("qt  "));

        assertNotMp4(new RecordingFile(concat(quickTime, movie(mvhd0(1000, 3000)))));
    }

    @Test
    void readsTheBrandAfterAFileTypeBoxHeaderWithASixtyFourBitSize() {
        byte[] content = concat(ascii("isom"), new byte[4]);
        byte[] largeFtyp = ByteBuffer.allocate(16 + content.length)
                .putInt(1).put(ascii("ftyp")).putLong(16 + content.length).put(content)
                .array();
        byte[] largeQuickTime = Arrays.copyOf(largeFtyp, largeFtyp.length);
        System.arraycopy(ascii("qt  "), 0, largeQuickTime, 16, 4);
        byte[] moov = movie(mvhd0(1000, 3000));

        assertThat(Mp4Header.read(new RecordingFile(concat(largeFtyp, moov))).durationSeconds()).isEqualTo(3);
        assertNotMp4(new RecordingFile(concat(largeQuickTime, moov)));
    }

    @Test
    void readsAFileTypeBoxThatHoldsOnlyItsBrandAndRefusesOneTooShortForIt() {
        byte[] moov = movie(mvhd0(1000, 3000));

        assertThat(Mp4Header.read(new RecordingFile(concat(box("ftyp", ascii("isom")), moov))).durationSeconds())
                .isEqualTo(3);
        assertNotMp4(new RecordingFile(concat(box("ftyp", ascii("iso")), moov)));
    }

    @Test
    void refusesAnEmptyFileWithoutReadingIt() {
        RecordingFile empty = new RecordingFile(new byte[0]);

        assertNotMp4(empty);
        assertThat(empty.reads()).isEmpty();
    }

    @Test
    void refusesAFileWithoutAMovieBox() {
        RecordingFile file = new RecordingFile(concat(FTYP, box("free", new byte[64])));

        assertNotMp4(file);
    }

    @ParameterizedTest
    @ValueSource(strings = {"avc1", "avc3"})
    void readsAVideoTrackInEitherFormatOfH264(String format) {
        assertThat(Mp4Header.read(fileOf(track("vide", sampleEntry(format)))).durationSeconds()).isEqualTo(3);
    }

    /** HEVC under both its names, AV1, VP9, MPEG-4 Part 2, encrypted video, and a format named in the wrong case. */
    @ParameterizedTest
    @ValueSource(strings = {"hvc1", "hev1", "av01", "vp09", "mp4v", "encv", "AVC1"})
    void refusesAVideoTrackInAnyOtherFormat(String format) {
        assertNotH264(fileOf(track("vide", sampleEntry(format))));
    }

    @Test
    void refusesAMovieWithoutAVideoTrack() {
        assertNotH264(fileOf(track("soun", mp4a(0x40))));
    }

    @Test
    void refusesAVideoTrackWithoutAnySampleEntry() {
        assertNotH264(fileOf(track("vide")));
    }

    @Test
    void refusesAVideoTrackWhoseSamplesAreInH264AndInAnotherFormat() {
        assertNotH264(fileOf(track("vide", sampleEntry("avc1"), sampleEntry("hvc1"))));
    }

    @Test
    void refusesASecondVideoTrackThatIsNotH264() {
        assertNotH264(fileOf(H264_TRACK, track("vide", sampleEntry("hvc1"))));
    }

    /** A timecode track, say, need not even have the boxes that would describe its samples. */
    @Test
    void readsTracksThatAreNeitherVideoNorAudioNoFurtherThanTheirHandler() {
        byte[] text = track("text", sampleEntry("tx3g"));
        byte[] timecode = box("trak", box("mdia", hdlr("tmcd")));

        assertThat(Mp4Header.read(fileOf(text, H264_TRACK, timecode)).durationSeconds()).isEqualTo(3);
    }

    @Test
    void refusesARealHevcFile() {
        assertNotH264(new RecordingFile(fixture("hevc.mp4")));
    }

    /** MPEG-4 Audio, as ffmpeg's AAC is, and the three profiles of MPEG-2 AAC. */
    @ParameterizedTest
    @ValueSource(ints = {0x40, 0x66, 0x67, 0x68})
    void readsAnAudioTrackOfAac(int objectType) {
        assertThat(Mp4Header.read(fileOf(H264_TRACK, track("soun", mp4a(objectType)))).durationSeconds())
                .isEqualTo(3);
    }

    /** MP3, as ffmpeg muxes it into an MP4, MPEG-2 audio, and the object types next to AAC's. */
    @ParameterizedTest
    @ValueSource(ints = {0x6B, 0x69, 0x3F, 0x41, 0x65, 0x00, 0xFF})
    void refusesAnMpeg4AudioEntryOfAnotherObjectType(int objectType) {
        assertNotAac(fileOf(H264_TRACK, track("soun", mp4a(objectType))));
    }

    /** Opus, AC-3, E-AC-3, FLAC, ALAC, encrypted audio, and AAC's own format named in the wrong case. */
    @ParameterizedTest
    @ValueSource(strings = {"Opus", "ac-3", "ec-3", "fLaC", "alac", "enca", "MP4A"})
    void refusesAnAudioTrackInAnotherFormat(String format) {
        assertNotAac(fileOf(H264_TRACK, track("soun", audioEntry(format, esds(esDescriptor(new byte[1], 0x40))))));
    }

    @Test
    void refusesAnAudioTrackWhoseSamplesAreInAacAndInAnotherFormat() {
        assertNotAac(fileOf(H264_TRACK, track("soun", mp4a(0x40), audioEntry("Opus"))));
    }

    @Test
    void refusesASecondAudioTrackThatIsNotAac() {
        assertNotAac(fileOf(H264_TRACK, track("soun", mp4a(0x40)), track("soun", mp4a(0x6B))));
    }

    @Test
    void readsTheEsdsBoxAfterTheOtherBoxesOfTheEntry() {
        byte[] entry = audioEntry("mp4a", box("btrt", new byte[12]), esds(esDescriptor(new byte[1], 0x40)));

        assertThat(Mp4Header.read(fileOf(H264_TRACK, track("soun", entry))).durationSeconds()).isEqualTo(3);
    }

    @Test
    void readsAnAudioTrackWithoutAnySampleEntry() {
        assertThat(Mp4Header.read(fileOf(H264_TRACK, track("soun"))).durationSeconds()).isEqualTo(3);
    }

    /** The video is refused first: re-encoding it is the larger change. */
    @Test
    void refusesTheVideoBeforeTheAudio() {
        assertNotH264(fileOf(track("vide", sampleEntry("hvc1")), track("soun", mp4a(0x6B))));
    }

    @Test
    void readsTheObjectTypeAfterDescriptorSizesOfOneByte() {
        byte[] decoderConfig = shortDescriptor(4, concat(new byte[] {0x40, 0x15}, new byte[11]));
        byte[] esDescriptor = shortDescriptor(3, concat(new byte[3], decoderConfig));

        assertThat(Mp4Header.read(fileOf(H264_TRACK, track("soun", audioEntry("mp4a", esds(esDescriptor)))))
                .durationSeconds()).isEqualTo(3);
    }

    /**
     * The fields each flag of the ES_Descriptor announces come before the decoder's configuration: the id of the
     * stream it depends on, a URL, and the id of its clock's stream. None of their bytes is the decoder's tag.
     */
    @ParameterizedTest(name = "flags {0}")
    @MethodSource("streamFlagsAndTheirFields")
    void readsTheObjectTypePastTheFieldsTheStreamsFlagsAnnounce(String flags, byte[] flagsAndFields) {
        RecordingFile file = fileOf(H264_TRACK, track("soun", audioEntry("mp4a",
                esds(esDescriptor(flagsAndFields, 0x40)))));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(3);
    }

    static Stream<Arguments> streamFlagsAndTheirFields() {
        byte[] url = "http://x".getBytes(StandardCharsets.US_ASCII);
        return Stream.of(
                Arguments.of("none", new byte[] {0x1F}),
                Arguments.of("depends on another stream", new byte[] {(byte) 0x80, 0x00, 0x01}),
                Arguments.of("a URL", concat(new byte[] {0x40, (byte) url.length}, url)),
                Arguments.of("a clock's stream", new byte[] {0x20, 0x00, 0x01}),
                Arguments.of("all three", concat(new byte[] {(byte) 0xE0, 0x00, 0x01, (byte) url.length}, url,
                        new byte[] {0x00, 0x01})));
    }

    /** An MPEG-4 audio entry must name its object type in an esds box: one that cannot is broken. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("descriptorsThatNameNoObjectType")
    void refusesAnMpeg4AudioEntryWhoseDescriptorsNameNoObjectType(String description, byte[] entry) {
        assertNotMp4(fileOf(H264_TRACK, track("soun", entry)));
    }

    /** The whole movie is read before any format is checked, so a broken file is told as such first. */
    @Test
    void refusesABrokenAudioTrackAsNotAnMp4BeforeCheckingTheVideo() {
        assertNotMp4(fileOf(track("vide", sampleEntry("hvc1")), track("soun", audioEntry("mp4a"))));
    }

    static Stream<Arguments> descriptorsThatNameNoObjectType() {
        byte[] aac = esDescriptor(new byte[1], 0x40);
        byte[] decoderConfigAsAnotherTag = Arrays.copyOf(aac, aac.length);
        decoderConfigAsAnotherTag[8] = 5;
        return Stream.of(
                Arguments.of("no esds box", audioEntry("mp4a", box("btrt", new byte[12]))),
                Arguments.of("no descriptor", audioEntry("mp4a", esds(new byte[0]))),
                Arguments.of("another descriptor than the stream's", audioEntry("mp4a",
                        esds(shortDescriptor(4, new byte[] {0x40})))),
                Arguments.of("another descriptor than the decoder's", audioEntry("mp4a",
                        esds(decoderConfigAsAnotherTag))),
                Arguments.of("a size cut short", audioEntry("mp4a", esds(new byte[] {3, (byte) 0x80}))),
                Arguments.of("the decoder's descriptor cut before its object type", audioEntry("mp4a",
                        esds(Arrays.copyOf(aac, 13)))),
                Arguments.of("a URL longer than the descriptor", audioEntry("mp4a",
                        esds(shortDescriptor(3, new byte[] {0, 2, 0x40, 99, 4, 1, 0x40})))));
    }

    @Test
    void refusesARealFileOfMp3InAnMpeg4AudioEntry() {
        assertNotAac(new RecordingFile(fixture("mp3-audio.mp4")));
    }

    @Test
    void refusesARealFileOfOpus() {
        assertNotAac(new RecordingFile(fixture("opus-audio.mp4")));
    }

    @Test
    void refusesAnAudioTrackWhoseFormatsCannotBeRead() {
        assertNotMp4(fileOf(H264_TRACK, box("trak", box("mdia", hdlr("soun"), box("minf")))));
    }

    @Test
    void refusesAnMpeg4AudioEntryTooShortForItsFields() {
        assertNotMp4(fileOf(H264_TRACK, track("soun", box("mp4a", new byte[27]))));
    }

    @Test
    void readsAHandlerBoxThatEndsRightAfterItsType() {
        byte[] shortHandler = box("hdlr", new byte[8], ascii("vide"));
        byte[] video = box("trak", box("mdia", shortHandler, box("minf", box("stbl", stsd(sampleEntry("avc1"))))));

        assertThat(Mp4Header.read(fileOf(video)).durationSeconds()).isEqualTo(3);
    }

    /** An empty box is its header alone, even as the last box of its parent. */
    @Test
    void readsAnEmptyBoxThatEndsTheMovie() {
        RecordingFile file = new RecordingFile(concat(FTYP, box("moov", mvhd0(1000, 3000), H264_TRACK, box("udta"))));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(3);
    }

    /** Each track, broken on the way to the formats of its samples. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenTracks")
    void refusesATrackWhoseFormatsCannotBeRead(String description, byte[] track) {
        assertNotMp4(fileOf(H264_TRACK, track));
    }

    static Stream<Arguments> brokenTracks() {
        byte[] stsd = stsd(sampleEntry("avc1"));
        return Stream.of(
                Arguments.of("no media box", box("trak", box("tkhd", new byte[84]))),
                Arguments.of("no handler box", box("trak", box("mdia", box("minf", box("stbl", stsd))))),
                Arguments.of("a handler box too short for its type", box("trak", box("mdia",
                        box("hdlr", Arrays.copyOf(ascii("\0\0\0\0\0\0\0\0vide"), 11)),
                        box("minf", box("stbl", stsd))))),
                Arguments.of("no media information box", box("trak", box("mdia", hdlr("vide")))),
                Arguments.of("no sample table box", box("trak", box("mdia", hdlr("vide"), box("minf")))),
                Arguments.of("no sample description box",
                        box("trak", box("mdia", hdlr("vide"), box("minf", box("stbl"))))),
                Arguments.of("a sample description box too short for its count", box("trak", box("mdia",
                        hdlr("vide"), box("minf", box("stbl", box("stsd", new byte[7])))))),
                Arguments.of("a sample entry smaller than its header", box("trak", box("mdia", hdlr("vide"),
                        box("minf", box("stbl", box("stsd", new byte[8], ByteBuffer.allocate(8).putInt(4)
                                .put(ascii("avc1")).array())))))));
    }

    @Test
    void refusesAMovieBoxWithoutAMovieHeader() {
        RecordingFile file = new RecordingFile(concat(FTYP, box("moov", H264_TRACK)));

        assertNotMp4(file);
    }

    @Test
    void refusesALastBoxThatRunsToTheEndOfTheFileWithoutAMovieBox() {
        byte[] openEndedFree = ByteBuffer.allocate(8 + 64).putInt(0).put(ascii("free")).array();
        RecordingFile file = new RecordingFile(concat(FTYP, openEndedFree));

        assertNotMp4(file);
    }

    @Test
    void refusesABoxSmallerThanItsOwnHeader() {
        byte[] broken = ByteBuffer.allocate(8).putInt(4).put(ascii("free")).array();
        RecordingFile file = new RecordingFile(concat(FTYP, broken, movie(mvhd0(1000, 3000))));

        assertNotMp4(file);
    }

    @Test
    void refusesAMovieHeaderWithoutATimescale() {
        RecordingFile file = new RecordingFile(concat(FTYP, movie(mvhd0(0, 3000))));

        assertNotMp4(file);
    }

    @Test
    void refusesAFileTooShortForABoxHeader() {
        assertNotMp4(new RecordingFile(new byte[7]));
    }

    @Test
    void findsTheMovieBoxAmongTheFirstThirtyTwoTopLevelBoxesAndNoFurther() {
        byte[] moov = movie(mvhd0(1000, 3000));
        byte[] thirtyOneBoxes = concat(FTYP, concat(Collections.nCopies(30, box("free")).toArray(byte[][]::new)));

        assertThat(Mp4Header.read(new RecordingFile(concat(thirtyOneBoxes, moov))).durationSeconds()).isEqualTo(3);
        assertNotMp4(new RecordingFile(concat(thirtyOneBoxes, box("free"), moov)));
    }

    /** An upload cut short: the movie box says it is longer than what is left of the file. */
    @Test
    void refusesAMovieBoxThatRunsPastTheEndOfTheFile() {
        byte[] moov = movie(mvhd0(1000, 3000));
        RecordingFile file = new RecordingFile(concat(FTYP, Arrays.copyOf(moov, moov.length - 1)));

        assertNotMp4(file);
    }

    /** Not the file's fault, but the storage's: the bytes it answers are not the ones its headers announced. */
    @Test
    void failsOnAMovieBoxTheFileDoesNotReturnWhole() {
        byte[] content = concat(FTYP, movie(mvhd0(1000, 3000)), box("free", new byte[8]));
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

        assertThatIllegalStateException().isThrownBy(() -> Mp4Header.read(longerReads));
    }

    @Test
    void readsAMovieBoxOfUpTo64MebibytesAndRefusesALargerOneWithoutFetchingIt() {
        int limit = 64 * 1024 * 1024;
        byte[] mvhd = mvhd0(1000, 3000);
        byte[] moovHeader = ByteBuffer.allocate(8).putInt(limit).put(ascii("moov")).array();
        SparseFile atTheLimit = new SparseFile(concat(FTYP, moovHeader, mvhd, H264_TRACK), FTYP.length + limit);
        byte[] tooLargeHeader = ByteBuffer.allocate(8).putInt(limit + 1).put(ascii("moov")).array();
        SparseFile pastTheLimit = new SparseFile(concat(FTYP, tooLargeHeader, mvhd, H264_TRACK),
                FTYP.length + limit + 1);

        assertThat(Mp4Header.read(atTheLimit).durationSeconds()).isEqualTo(3);
        assertNotMp4(pastTheLimit);
        assertThat(pastTheLimit.largestRead()).isLessThanOrEqualTo(20);
    }

    @ParameterizedTest(name = "version {0} with {1} bytes")
    @CsvSource({"0, 0", "0, 19", "1, 31"})
    void refusesAMovieHeaderCutShort(int version, int contentBytes) {
        RecordingFile file = new RecordingFile(concat(FTYP, movie(mvhd(version, contentBytes, 1000, 3000))));

        assertNotMp4(file);
    }

    @ParameterizedTest(name = "version {0} with {1} bytes")
    @CsvSource({"0, 20", "1, 32"})
    void readsAMovieHeaderThatEndsRightAfterItsDuration(int version, int contentBytes) {
        RecordingFile file = new RecordingFile(concat(FTYP, movie(mvhd(version, contentBytes, 1000, 3000))));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(3);
    }

    @Test
    void readsADurationOfAsManySecondsAsAnIntegerHolds() {
        RecordingFile file = new RecordingFile(concat(FTYP, movie(mvhd1(1, Integer.MAX_VALUE))));

        assertThat(Mp4Header.read(file).durationSeconds()).isEqualTo(Integer.MAX_VALUE);
    }

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource({"2147483648, 1", "4294967295, 2", "-1, 1"})
    void refusesADurationOfMoreSecondsThanAnIntegerHoldsOnceRounded(long duration, long timescale) {
        RecordingFile file = new RecordingFile(concat(FTYP, movie(mvhd1(timescale, duration))));

        assertNotMp4(file);
    }

    private static void assertNotAac(Mp4Header.Source file) {
        assertThatExceptionOfType(AudioNotAacException.class).isThrownBy(() -> Mp4Header.read(file));
    }

    private static void assertNotH264(Mp4Header.Source file) {
        assertThatExceptionOfType(VideoNotH264Exception.class).isThrownBy(() -> Mp4Header.read(file));
    }

    private static void assertNotMp4(Mp4Header.Source file) {
        assertThatExceptionOfType(VideoNotMp4Exception.class).isThrownBy(() -> Mp4Header.read(file));
    }

    /** A box: its 32-bit size, its type, then its content. */
    private static byte[] box(String type, byte[]... content) {
        byte[] body = concat(content);
        return ByteBuffer.allocate(8 + body.length).putInt(8 + body.length).put(ascii(type)).put(body).array();
    }

    /** A movie box: the movie header, then an H.264 video track. */
    private static byte[] movie(byte[] mvhd) {
        return box("moov", mvhd, H264_TRACK);
    }

    /** A file of three seconds whose movie holds these tracks. */
    private static RecordingFile fileOf(byte[]... tracks) {
        return new RecordingFile(concat(FTYP, box("moov", mvhd0(1000, 3000), concat(tracks))));
    }

    /**
     * A track: its handler type names what it carries, and its sample description box lists the formats its samples
     * are in, with the boxes a track has on the way there.
     */
    private static byte[] track(String handler, byte[]... sampleEntries) {
        return box("trak", box("tkhd", new byte[84]), box("mdia", box("mdhd", new byte[24]), hdlr(handler),
                box("minf", box("dinf", new byte[28]), box("stbl", stsd(sampleEntries), box("stts", new byte[8])))));
    }

    /** A handler box: the version and flags, a field always 0, the handler type, reserved bytes, then a name. */
    private static byte[] hdlr(String handler) {
        return box("hdlr", new byte[8], ascii(handler), new byte[12], ascii("Handler\0"));
    }

    /** A sample description box: the version and flags, the number of entries, then the entries. */
    private static byte[] stsd(byte[]... sampleEntries) {
        return box("stsd", ByteBuffer.allocate(8).putInt(0).putInt(sampleEntries.length).array(),
                concat(sampleEntries));
    }

    /** A visual sample entry of the format, of which linking reads no more than its type. */
    private static byte[] sampleEntry(String format) {
        return box(format, new byte[78]);
    }

    /** An audio sample entry: the 28 bytes of the fields it opens with, then its boxes. */
    private static byte[] audioEntry(String format, byte[]... boxes) {
        return box(format, new byte[28], concat(boxes));
    }

    /** An MPEG-4 audio sample entry of the object type, as ffmpeg writes one, with a bitrate box after its esds. */
    private static byte[] mp4a(int objectType) {
        return audioEntry("mp4a", esds(esDescriptor(new byte[1], objectType)), box("btrt", new byte[12]));
    }

    /** An elementary stream descriptor box: the version and flags, then the descriptors. */
    private static byte[] esds(byte[] descriptors) {
        return box("esds", new byte[4], descriptors);
    }

    /**
     * An ES_Descriptor (tag 3), as ffmpeg writes it: the stream's id, its flags and the fields they announce, then the
     * DecoderConfigDescriptor (tag 4), which opens with the object type, and the SLConfigDescriptor (tag 6).
     */
    private static byte[] esDescriptor(byte[] flagsAndFields, int objectType) {
        byte[] decoderConfig = descriptor(4, concat(new byte[] {(byte) objectType, 0x15}, new byte[11]));
        return descriptor(3, concat(new byte[] {0, 2}, flagsAndFields, decoderConfig, descriptor(6, new byte[] {2})));
    }

    /** A descriptor whose size takes four bytes, as ffmpeg writes it, seven bits each, the high bit on but the last. */
    private static byte[] descriptor(int tag, byte[] content) {
        int size = content.length;
        return concat(new byte[] {(byte) tag, (byte) (0x80 | size >> 21), (byte) (0x80 | size >> 14 & 0x7F),
                (byte) (0x80 | size >> 7 & 0x7F), (byte) (size & 0x7F)}, content);
    }

    /** A descriptor of under 128 bytes, whose size takes one byte. */
    private static byte[] shortDescriptor(int tag, byte[] content) {
        return concat(new byte[] {(byte) tag, (byte) content.length}, content);
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

    /**
     * A file held in memory, which answers a read past its end with what it has, as a ranged GET does. A read of no
     * byte at all would name no range, so it fails the test.
     */
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
            assertThat(length).as("bytes read at %d", offset).isPositive();
            reads.add(new Read(offset, length));
            return Arrays.copyOfRange(content, (int) offset, (int) Math.min(content.length, offset + length));
        }

        List<Read> reads() {
            return reads;
        }
    }
}
