package io.mosip.vivid.device.sbi;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultipartStreamReaderTest {
    private static final byte[] JPEG_A = "ÿØAAAAÿÙ".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] JPEG_B = "ÿØBBBBBBBBÿÙ".getBytes(StandardCharsets.ISO_8859_1);

    private static MultipartStreamReader reader(String contentType, String wire) {
        return new MultipartStreamReader(new ByteArrayInputStream(wire.getBytes(StandardCharsets.ISO_8859_1)), contentType);
    }
    private static String s(byte[] b) { return new String(b, StandardCharsets.ISO_8859_1); }

    @Test
    void boundaryParsing() {
        assertThat(MultipartStreamReader.boundaryOf("multipart/x-mixed-replace; boundary=frame")).isEqualTo("frame");
        assertThat(MultipartStreamReader.boundaryOf("multipart/x-mixed-replace;boundary=\"a b\"")).isEqualTo("a b");
        assertThat(MultipartStreamReader.boundaryOf("multipart/x-mixed-replace; boundary=--BoundaryString")).isEqualTo("--BoundaryString");
        assertThatThrownBy(() -> MultipartStreamReader.boundaryOf("image/jpeg")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void standardCrlfWithContentLength() throws IOException {
        String wire = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + JPEG_A.length + "\r\n\r\n" + s(JPEG_A) + "\r\n"
                    + "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + JPEG_B.length + "\r\n\r\n" + s(JPEG_B) + "\r\n--frame--\r\n";
        try (MultipartStreamReader r = reader("multipart/x-mixed-replace; boundary=frame", wire)) {
            assertThat(r.next().body()).isEqualTo(JPEG_A);
            MultipartStreamReader.Part p = r.next();
            assertThat(p.body()).isEqualTo(JPEG_B);
            assertThat(p.contentType()).isEqualTo("image/jpeg");
            assertThat(r.next()).isNull();
            assertThat(r.next()).isNull();
            assertThat(r.partsRead()).isEqualTo(2);
            assertThat(r.bytesSkipped()).isZero();
        }
    }

    @Test
    void mockMdsFramingWithDashesInBoundaryAndNoSpaces() throws IOException {
        // exactly what io.mosip.mock.sbi SBIServiceResponse writes
        String wire = "--BoundaryString\r\nAccess-Control-Allow-Origin:*\r\nContent-Type:image/jpeg\r\nContent-Length:" + JPEG_A.length + "\r\n\r\n" + s(JPEG_A) + "\r\n\r\n"
                    + "--BoundaryString\r\nAccess-Control-Allow-Origin:*\r\nContent-Type:image/jpeg\r\nContent-Length:" + JPEG_B.length + "\r\n\r\n" + s(JPEG_B) + "\r\n\r\n";
        try (MultipartStreamReader r = reader("multipart/x-mixed-replace; boundary=--BoundaryString", wire)) {
            MultipartStreamReader.Part p = r.next();
            assertThat(p.body()).isEqualTo(JPEG_A);
            assertThat(p.contentType()).isEqualTo("image/jpeg");
            assertThat(r.next().body()).isEqualTo(JPEG_B);
            assertThat(r.next()).isNull();   // clean EOF without closing delimiter
        }
    }

    @Test
    void bareLfLineEndingsAndMissingContentLength() throws IOException {
        String wire = "--b\nContent-Type: image/jpeg\n\n" + s(JPEG_A) + "\n--b\nContent-Type: image/jpeg\n\n" + s(JPEG_B) + "\n--b--\n";
        try (MultipartStreamReader r = reader("multipart/x-mixed-replace; boundary=b", wire)) {
            assertThat(r.next().body()).isEqualTo(JPEG_A);
            assertThat(r.next().body()).isEqualTo(JPEG_B);
            assertThat(r.next()).isNull();
        }
    }

    @Test
    void wrongContentLengthResynchronisesOnDelimiter() throws IOException {
        String wire = "--b\r\nContent-Length: 3\r\n\r\n" + s(JPEG_A) + "\r\n--b\r\nContent-Length: 999999999999\r\n\r\n" + s(JPEG_B) + "\r\n--b--";
        try (MultipartStreamReader r = reader("multipart/x-mixed-replace; boundary=b", wire)) {
            assertThat(r.next().body()).hasSize(3);          // declared too small: truncated, but the parser resyncs
            assertThat(r.next().body()).isEqualTo(JPEG_B);   // declared absurdly large: treated as unframed
            assertThat(r.next()).isNull();
            assertThat(r.bytesSkipped()).isGreaterThanOrEqualTo(JPEG_A.length - 3);   // leftover bytes plus the CRLF before the delimiter
        }
    }

    @Test
    void zeroLengthPartsAndGarbageBetweenParts() throws IOException {
        String wire = "preamble junk\r\n\r\n--b\r\nContent-Length: 0\r\n\r\n\r\n--b\r\nContent-Length: " + JPEG_A.length + "\r\n\r\n" + s(JPEG_A)
                    + "\r\n@@garbage@@\r\n--b\r\nContent-Type: image/jpeg\r\nContent-Length: " + JPEG_B.length + "\r\n\r\n" + s(JPEG_B) + "\r\n--b--\r\n";
        try (MultipartStreamReader r = reader("multipart/x-mixed-replace; boundary=b", wire)) {
            MultipartStreamReader.Part empty = r.next();
            assertThat(empty.isEmpty()).isTrue();
            assertThat(r.next().body()).isEqualTo(JPEG_A);
            assertThat(r.next().body()).isEqualTo(JPEG_B);
            assertThat(r.next()).isNull();
            assertThat(r.bytesSkipped()).isGreaterThan(0);
        }
    }

    @Test
    void truncatedPartRaisesEof() {
        String wire = "--b\r\nContent-Length: 100\r\n\r\n" + s(JPEG_A);
        MultipartStreamReader r = reader("multipart/x-mixed-replace; boundary=b", wire);
        assertThatThrownBy(r::next).isInstanceOf(EOFException.class);
        MultipartStreamReader r2 = reader("multipart/x-mixed-replace; boundary=b", "--b\r\nContent-Type: image/jpeg\r\n");
        assertThatThrownBy(r2::next).isInstanceOf(EOFException.class);
    }

    @Test
    void largePartsCrossBufferBoundaries() throws IOException {
        byte[] big = new byte[300 * 1024];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 31);
        String wire = "--b\r\nContent-Type: image/jpeg\r\n\r\n" + s(big) + "\r\n--b\r\nContent-Length: " + big.length + "\r\n\r\n" + s(big) + "\r\n--b--\r\n";
        MultipartStreamReader r = new MultipartStreamReader(new ByteArrayInputStream(wire.getBytes(StandardCharsets.ISO_8859_1)), "b", 1024);
        assertThat(r.next().body()).isEqualTo(big);
        assertThat(r.next().body()).isEqualTo(big);
        assertThat(r.next()).isNull();
    }
}
