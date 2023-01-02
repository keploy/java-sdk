package io.keploy.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Magic bytes can be checked at : https://en.wikipedia.org/wiki/List_of_file_signatures
 * And converted to integer hex params using JS in the browser console, for example :
 * "78 01 73 0D 62 ?? 60".split(" ").map(str => str == "??" ? "MagicBytes.ANY" : "0x"+str).join(", ")
 * prints => "0x78, 0x01, 0x73, 0x0D, 0x62, MagicBytes.ANY, 0x60"
 */
public enum MagicBytes {
    // Executables
    EXE(Header.builder()
            .add("EXE (includes PE32 + DOS)", 0x4D, 0x5A)),
    MACH_O(Header.builder()
            .add("MACH-O 32bit", 0xFE, 0xED, 0xFA, 0xCE)
            .add("MACH-O 64bit", 0xFE, 0xED, 0xFA, 0xCF)),
    SHEBANG(Header.builder()
            .add("SHEBANG (#!) script", 0x23, 0x21)),
    ELF(Header.builder()
            .add("ELF", 0x7F, 0x45, 0x4C, 0x46)),
    COM(Header.builder()
            .add("COM", 0xC9)),
    DALVIK(Header.builder()
            .add("DEX", 0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00)),
    DMG(Header.builder()
            .add("DMG", 0x78, 0x01, 0x73, 0x0D, 0x62, 0x62, 0x60)),
    // Archives
    SQLITE(Header.builder()
            .add("SQLITE3", 0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66, 0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00)),
    TAR_LZW(Header.builder()
            .add("TAR LZW", 0x1F, 0x9D)
            .add("TAR LZ", 0x1F, 0xA0)),
    BZIP2(Header.builder()
            .add("BZ2", 0x42, 0x5A, 0x68)),
    LZIP(Header.builder()
            .add("LZIP", 0x4C, 0x5A, 0x49, 0x50)),
    ZIP(Header.builder()
            .add("ZIP", 0x50, 0x4B, 0x03, 0x04)
            .add("ZIP (empty)", 0x50, 0x4B, 0x05, 0x06)
            .add("ZIP (spanned)", 0x50, 0x4B, 0x07, 0x08)),
    RAR(Header.builder()
            .add("RAR v1.5+", 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
            .add("RAR v5+", 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)),
    ISO(Header.builder()
            .add("ISO9660 CD/DVD Image File", 0x43, 0x44, 0x30, 0x30, 0x31)),
    VMDK(Header.builder()
            .add("VMDK", 0x4B, 0x44, 0x4D)),
    VDI(Header.builder()
            .add("VDI (VirtualBox)", 0x3C, 0x3C, 0x3C, 0x20, 0x4F, 0x72, 0x61, 0x63, 0x6C, 0x65, 0x20, 0x56, 0x4D, 0x20, 0x56, 0x69, 0x72, 0x74, 0x75, 0x61, 0x6C, 0x42, 0x6F, 0x78, 0x20, 0x44, 0x69, 0x73, 0x6B, 0x20, 0x49, 0x6D, 0x61, 0x67, 0x65, 0x20, 0x3E, 0x3E, 0x3E)),
    VHD(Header.builder()
            .add("VHD (Win)", 0x63, 0x6F, 0x6E, 0x6E, 0x65, 0x63, 0x74, 0x69, 0x78)),
    VHDX(Header.builder()
            .add("VHDX (Win8)", 0x76, 0x68, 0x64, 0x78, 0x66, 0x69, 0x6C, 0x65)),
    ISZ(Header.builder()
            .add("ISZ (compressed ISO)", 0x49, 0x73, 0x5A, 0x21)),
    EVT(Header.builder()
            .add("Windows Event Viewer", 0x4C, 0x66, 0x4C, 0x65)),
    XAR(Header.builder()
            .add("eXtensible ARchive", 0x78, 0x61, 0x72, 0x21)),
    TAR(Header.builder()
            .add("TAR (subpackage)", 0x75, 0x73, 0x74, 0x61, 0x72, 0x00, 0x30, 0x30)
            .add("TAR", 0x75, 0x73, 0x74, 0x61, 0x72, 0x20, 0x20, 0x00)),
    SEVEN_ZIP(Header.builder()
            .add("7Z", 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)),
    GZIP(Header.builder()
            .add("GZ", 0x1F, 0x8B)),
    MATROSKA(Header.builder()
            .add("MKV/WebM", 0x1A, 0x45, 0xDF, 0xA3)),
    DICOM(Header.builder()
            .add("DICOM", 0x44, 0x49, 0x43, 0x4D)),
    ZLIB(Header.builder()
            .add("ZLIB (No compression - no preset dictionary)", 0x78, 0x01)
            .add("ZLIB (Best speed - no preset dictionary)", 0x78, 0x5E)
            .add("ZLIB (Default compression - no preset dictionary)", 0x78, 0x9C)
            .add("ZLIB (Best compression - no preset dictionary)", 0x78, 0xDA)
            .add("ZLIB (No compression - with preset dictionary)", 0x78, 0x20)
            .add("ZLIB (Best speed - with preset dictionary)", 0x78, 0x7D)
            .add("ZLIB (Default compression - with preset dictionary)", 0x78, 0xBB)
            .add("ZLIB (Best compression - with preset dictionary)", 0x78, 0xF9)),
    LZFSE(Header.builder()
            .add("LZFSE (Apple)", 0x62, 0x76, 0x78, 0x32)),
    PST(Header.builder()
            .add("Microsoft Outlook", 0x21, 0x42, 0x44, 0x4E)),
    // Text
    REG(Header.builder()
            .add("Windows Registry File/DAT", 0x72, 0x65, 0x67, 0x66)),
    DAT(Header.builder()
            .add("DAT/USMT 3+", 0x50, 0x4D, 0x4F, 0x43, 0x43, 0x4D, 0x4F, 0x43)),
    OFFICE_OLD(Header.builder()
            .add("Compound File Binary Format (MS-Office)", 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)),
    PDF(Header.builder()
            .add("PDF", 0x25, 0x50, 0x44, 0x46, 0x2d)),
    XML(Header.builder()
            .add("XML", 0x3c, 0x3f, 0x78, 0x6d, 0x6c, 0x20)),
    RTT(Header.builder()
            .add("RTT", 0x7B, 0x5C, 0x72, 0x74, 0x66, 0x31)),
    // Images
    PNG(Header.builder()
            .add("PNG", 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
    PBM(Header.builder()
            .add("PBM", 0x50, 0x31, 0x0A)),
    PGM(Header.builder()
            .add("PGM", 0x50, 0x32, 0x0A)),
    PPM(Header.builder()
            .add("PPM", 0x50, 0x33, 0x0A)),
    JPG(Header.builder()
            .add("JPG Raw", 0xFF, 0xD8, 0xFF, 0xDB)
            .add("JPG Raw 2", 0xFF, 0xD8, 0xFF, 0xEE)
            .add("JPG JFIF", 0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01)
            .add("JPG EXIF", 0xFF, 0xD8, 0xFF, 0xE1, MagicBytes.ANY, MagicBytes.ANY, 0x45, 0x78, 0x69, 0x66, 0x00, 0x00)),
    GIF(Header.builder()
            .add("GIF87a", 0x47, 0x49, 0x46, 0x38, 0x37, 0x61)
            .add("GIF89a", 0x47, 0x49, 0x46, 0x38, 0x39, 0x61)),
    TIFF(Header.builder()
            .add("TIFF LE", 0x49, 0x49, 0x2A, 0x00)
            .add("TIFF BE", 0x4D, 0x4D, 0x00, 0x2A)),
    BMP(Header.builder()
            .add("BMP", 0x42, 0x4D)),
    // Audio
    WAV(Header.builder()
            .add("WAV", 0x52, 0x49, 0x46, 0x46, MagicBytes.ANY, MagicBytes.ANY, MagicBytes.ANY, MagicBytes.ANY, 0x57, 0x41, 0x56, 0x45)),
    MP3(Header.builder()
            .add("MP3", 0x49, 0x44, 0x33)),
    FLAC(Header.builder()
            .add("FLAC", 0x66, 0x4C, 0x61, 0x43)),
    MIDI(Header.builder()
            .add("MIDI", 0x4D, 0x54, 0x68, 0x64)),
    // Video
    AVI(Header.builder()
            .add("AVI", 0x52, 0x49, 0x46, 0x46, MagicBytes.ANY, MagicBytes.ANY, MagicBytes.ANY, MagicBytes.ANY, 0x41, 0x56, 0x49, 0x20)),
    MP4(Header.builder()
            .add("MP4", 0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D)),
    FLV(Header.builder()
            .add("FLV", 0x46, 0x4C, 0x56)),
    ;

    private static final int ANY = -1;
    private final Header[] headers;

    private MagicBytes(Header.Builder builder) {
        this.headers = builder.build();
    }

    public Header[] getHeaders() {
        return headers;
    }

    /* Checks if bytes match a specific magic bytes sequence.
     * Tries to match each header sequentially, the code
     * short-circuits on match found.						  */
    public Header is(byte[] bytes) {
        boolean matches;
        for (Header header : headers) {
            matches = true;
            for (int i = 0; i < header.bytes.length; i++) {
                if (header.bytes[i] != ANY && header.bytes[i] != Byte.toUnsignedInt(bytes[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches)
                return header;
        }
        return null;
    }

    // Extracts head bytes from any stream
    public static byte[] extract(InputStream is, int length) throws IOException {
        try {
            byte[] buffer = new byte[length];
            is.read(buffer, 0, length);
            return buffer;
        } finally {
            is.close();
        }
    }

    public static Header matches(byte[] bytes) {
        Header header;
        for (MagicBytes magic : MagicBytes.values()) {
            header = magic.is(bytes);
            if (header != null)
                return header;
        }
        return null;
    }

    //TODO: Add support for more content type.
    public static String getContentType(Header ct) {
        String contentType = ct.getName();
        if (contentType.contains("PNG")) {
            return "png";
        } else if (contentType.contains("JPG")) {
            return "jpg";
        } else if (contentType.contains("PDF")) {
            return "pdf";
        } else if (contentType.contains("XML")) {
            return "xml";
        }
        return "";
    }

    /* Convenience methods */

    public Header is(String name) throws FileNotFoundException, IOException {
        return is(new File(name));
    }

    public Header is(File file) throws FileNotFoundException, IOException {
        return is(new FileInputStream(file));
    }

    public Header is(InputStream is) throws IOException {
        return is(extract(is, 50));
    }

    public static Header matching(String name) throws FileNotFoundException, IOException {
        return matching(new File(name));
    }

    public static Header matching(File file) throws FileNotFoundException, IOException {
        return matching(new FileInputStream(file));
    }

    public static Header matching(InputStream is) throws IOException {
        return matches(extract(is, 50));
    }

    public static final class Header {
        private final String name;
        private final int[] bytes;

        public Header(String name, int[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }

        public String getName() {
            return name;
        }

        public int[] getBytes() {
            return bytes;
        }

        @Override
        public String toString() {
            return String.format("%s: %s", name, Arrays.toString(bytes));
        }

        private static Builder builder() {
            return new Builder();
        }

        private static final class Builder {
            private final List<Header> headers = new ArrayList<>();


            public Builder add(String name, int... bytes) {
                headers.add(new Header(name, bytes));
                return this;
            }

            public Header[] build() {
                return headers.toArray(new Header[0]);
            }
        }
    }
}