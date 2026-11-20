package io.keploy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * keploy-sdk runs on Java 8, and is released from JDK 17: the root pom's
 * java8-api profile makes javac on JDK 9 and later check the code against
 * Java 8's API (release 8), as it does for this test class. A call to
 * ByteBuffer.position(int) then refers to the method Java 8 has, Buffer's,
 * as when JDK 8 compiles it. With source and target 1.8 alone, JDK 9 and
 * later compile it to ByteBuffer's own position(int), which JDK 9 added and
 * Java 8 lacks (NoSuchMethodError on Java 8). It reads this test class as a
 * stand-in for the main classes, which take release 8 from the same
 * property: if tests ever get their own maven.compiler.testRelease, point
 * this check at a main class instead.
 */
public class Java8ApiTest {
    /** Holds only the call, so nothing else puts a position descriptor in its class file. */
    static final class Call {
        static void position(ByteBuffer buffer) {
            buffer.position(0);
        }
    }

    @Test
    public void aCallResolvesAgainstJava8sApi() throws IOException {
        String classFile = new String(read(Call.class), StandardCharsets.ISO_8859_1);
        assertTrue("the call to ByteBuffer.position(int) does not refer to Buffer's: (I)Ljava/nio/Buffer; is not in its class file",
                classFile.contains("(I)Ljava/nio/Buffer;"));
        assertFalse("the call to ByteBuffer.position(int) refers to the one JDK 9 added, which Java 8 lacks: "
                        + "javac did not compile against Java 8's API (the root pom's java8-api profile)",
                classFile.contains("(I)Ljava/nio/ByteBuffer;"));
    }

    static byte[] read(Class<?> type) throws IOException {
        String name = type.getName();
        try (InputStream in = type.getResourceAsStream(name.substring(name.lastIndexOf('.') + 1) + ".class")) {
            assertNotNull("no class file for " + name, in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            for (int n; (n = in.read(chunk)) != -1; ) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        }
    }
}
