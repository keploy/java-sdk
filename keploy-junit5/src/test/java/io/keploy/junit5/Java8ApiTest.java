package io.keploy.junit5;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/**
 * keploy-junit5 runs on Java 8, and is released from JDK 17: its pom's
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
class Java8ApiTest {
    /** Holds only the call, so nothing else puts a position descriptor in its class file. */
    static final class Call {
        static void position(ByteBuffer buffer) {
            buffer.position(0);
        }
    }

    @Test
    void aCallResolvesAgainstJava8sApi() throws IOException {
        String classFile = new String(read(Call.class), StandardCharsets.ISO_8859_1);
        assertTrue(classFile.contains("(I)Ljava/nio/Buffer;"),
                "the call to ByteBuffer.position(int) does not refer to Buffer's: (I)Ljava/nio/Buffer; is not in its class file");
        assertFalse(classFile.contains("(I)Ljava/nio/ByteBuffer;"),
                "the call to ByteBuffer.position(int) refers to the one JDK 9 added, which Java 8 lacks: "
                        + "javac did not compile against Java 8's API (keploy-junit5's java8-api profile)");
    }

    /**
     * The java8-api profile works through maven-compiler-plugin's release
     * parameter, which the plugin has since 3.6. A build that names no version
     * gets the running Maven's default, 3.1 on Maven 3.8 and older, which
     * ignores maven.compiler.release without a warning: a jar built there with
     * -DskipTests, where the row above does not run, would carry calls Java 8
     * lacks. keploy-junit5's pom names the version itself.
     */
    @Test
    void theCompilerPluginVersionHasRelease() throws Exception {
        File module = new File(System.getProperty("basedir", System.getProperty("user.dir"))).getAbsoluteFile();
        String version = compilerPluginVersion(pom(new File(module, "pom.xml"), "keploy-junit5"));
        assertFalse(version.isEmpty(), "keploy-junit5's pom names no maven-compiler-plugin version: "
                + "Maven 3.8 and older then use 3.1, which ignores release 8");
        assertTrue(hasRelease(version), "maven-compiler-plugin " + version + " has no release parameter "
                + "(3.6 and later have it): it ignores the java8-api profile's release 8");
    }

    /**
     * The pom at file, which must be artifactId's: run from another directory
     * without Maven's basedir (an IDE's working directory, say), the row would
     * read another module's pom and could pass on its pin.
     */
    static Document pom(File file, String artifactId) throws Exception {
        String where = ": run this row from keploy-junit5's directory, or through Maven, which sets basedir to it";
        assertTrue(file.isFile(), "no pom at " + file + where);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = factory.newDocumentBuilder().parse(file);
        assertEquals(artifactId, XPathFactory.newInstance().newXPath().evaluate("/project/artifactId", doc).trim(),
                file + " is not " + artifactId + "'s pom" + where);
        return doc;
    }

    /** The version a pom gives maven-compiler-plugin in its build, or "" when it names none. */
    static String compilerPluginVersion(Document doc) throws Exception {
        String plugin = "plugins/plugin[artifactId='maven-compiler-plugin']/version";
        String version = XPathFactory.newInstance().newXPath().evaluate("/project/build/" + plugin, doc).trim();
        if (version.isEmpty()) {
            version = XPathFactory.newInstance().newXPath().evaluate("/project/build/pluginManagement/" + plugin, doc).trim();
        }
        return version;
    }

    /** True for 3.6 and later: a plain version such as 3.13.0. Anything else, a property included, is false. */
    static boolean hasRelease(String version) {
        String[] parts = version.split("\\.");
        if (parts.length < 2 || !parts[0].matches("[0-9]+") || !parts[1].matches("[0-9]+")) {
            return false;
        }
        int major = Integer.parseInt(parts[0]);
        return major > 3 || (major == 3 && Integer.parseInt(parts[1]) >= 6);
    }

    static byte[] read(Class<?> type) throws IOException {
        String name = type.getName();
        try (InputStream in = type.getResourceAsStream(name.substring(name.lastIndexOf('.') + 1) + ".class")) {
            assertNotNull(in, "no class file for " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            for (int n; (n = in.read(chunk)) != -1; ) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        }
    }
}
