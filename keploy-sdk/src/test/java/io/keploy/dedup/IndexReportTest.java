package io.keploy.dedup;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IndexReportTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    // The build id's golden vectors (shared with Keploy's codecoverage.JavaBuildID): the sha256 over
    // "keploy-java-table 1\njacoco 0.8.15\n0000000000000001\n00000000000000ff\nffffffffffffffff".
    private static final long[] GOLDEN_IDS = {1, 0xff, 0xffffffffffffffffL};
    private static final String GOLDEN_BUILD_ID = "jv1-ac2499a6a1d337817534c44eb82f6ab3";

    @Test
    public void theBuildIdIsKeploysForTheSameIdsInAnyOrderAndRepeated() {
        assertEquals(GOLDEN_BUILD_ID, IndexReport.buildId(GOLDEN_IDS, 1, "0.8.15"));
        // A signed sort puts 0xffffffffffffffff (-1) first, a different id.
        assertEquals(GOLDEN_BUILD_ID,
                IndexReport.buildId(new long[] {0xffffffffffffffffL, 1, 0xff, 1}, 1, "0.8.15"));
        assertEquals(GOLDEN_BUILD_ID,
                IndexReport.buildId(new long[] {0xff, 0xffffffffffffffffL, 0xff, 1}, 1, "0.8.15"));
    }

    @Test
    public void anotherTableFormatOrAnalyzerNamesAnotherBuildOfTheSameClasses() {
        // An SDK whose table can differ for the same class bytes must not name the build an older SDK saved a
        // table under: Keploy would refuse every later save of it (409 build_table_mismatch).
        assertEquals("jv1-32f6f460d59e38d89bac87928b9421ae", IndexReport.buildId(GOLDEN_IDS, 2, "0.8.15"));
        assertEquals("jv1-776b07757cb4a25493b652ff31a8ca86", IndexReport.buildId(GOLDEN_IDS, 1, "0.8.14"));
        // Over the ids alone (the build id before the format and the analyzer were in it): none of the above.
        assertEquals("jv1-73ab094cbaa75db7d27e1cc885ca2260",
                "jv1-" + IndexReport.classIdsSum(GOLDEN_IDS).substring(0, 32));
    }

    @Test
    public void theSdksBuildIdIsOfThisTablesFormatAndItsAnalyzersJacoco() {
        assertEquals(1, BuildTable.TABLE_FORMAT);
        assertEquals(jacocoCore(), IndexReport.Analyzer.JACOCO);
        assertEquals(IndexReport.buildId(GOLDEN_IDS, 1, jacocoCore()), IndexReport.buildId(GOLDEN_IDS));
    }

    /**
     * An END reads class ids (hexId, classId) before its ACK; only the build id needs V, so those helpers must
     * not run JaCoCo's class init and its version lookup. Run in a loader of its own over the SDK's classes,
     * JaCoCo's core and Gson, so the classes it loads are its own to count.
     */
    @Test
    public void theClassIdHelpersNeverLoadTheAnalyzersJacoco() throws Exception {
        URL[] urls = {
            IndexReport.class.getProtectionDomain().getCodeSource().getLocation(),
            org.jacoco.core.JaCoCo.class.getProtectionDomain().getCodeSource().getLocation(),
            com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation(),
        };
        byte[] classBytes = Files.readAllBytes(new File(urls[0].toURI()).toPath()
                .resolve(IndexReport.class.getName().replace('.', '/') + ".class"));
        try (Counting loader = new Counting(urls)) {
            Class<?> report = Class.forName(IndexReport.class.getName(), true, loader);
            assertTrue(report != IndexReport.class);
            assertEquals("0000000000000001", call(report, "hexId", long.class, 1L));
            assertEquals(IndexReport.classId(classBytes), call(report, "classId", byte[].class, classBytes));
            assertFalse("V was read by a class-id helper", loader.loaded(IndexReport.Analyzer.class.getName()));
            assertFalse("JaCoCo's version was loaded by a class-id helper",
                    loader.loaded(org.jacoco.core.JaCoCo.class.getName()));
            assertEquals(IndexReport.buildId(new long[] {1}), call(report, "buildId", long[].class, new long[] {1}));
            assertTrue(loader.loaded(IndexReport.Analyzer.class.getName()));
            assertTrue(loader.loaded(org.jacoco.core.JaCoCo.class.getName()));
        }
    }

    private static final class Counting extends URLClassLoader {
        Counting(URL[] urls) {
            super(urls, null);
        }

        boolean loaded(String name) {
            return findLoadedClass(name) != null;
        }
    }

    private static Object call(Class<?> c, String name, Class<?> parameter, Object argument) throws Exception {
        java.lang.reflect.Method m = c.getDeclaredMethod(name, parameter);
        m.setAccessible(true);
        return m.invoke(null, argument);
    }

    @Test
    public void aJacocoReleaseIsItsVersionLessTheBuildQualifier() {
        assertEquals("0.8.15", IndexReport.release("0.8.15.202606040825"));
        assertEquals("0.8.15", IndexReport.release("0.8.15"));
        assertEquals("1.0.0", IndexReport.release("1.0.0.SNAPSHOT.x"));
        assertEquals("0.8", IndexReport.release("0.8"));
        assertEquals("", IndexReport.release(""));
    }

    /**
     * jacocoCore is the release of the org.jacoco.core jar the analyzer runs, as its Maven build named it (its
     * pom.properties, read from the jar JaCoCo's class was loaded from, so another jar's pom.properties on the
     * class path cannot answer), not as JaCoCo.VERSION says: what IndexReport.Analyzer.JACOCO must be.
     */
    static String jacocoCore() {
        try {
            File jar = new File(org.jacoco.core.JaCoCo.class.getProtectionDomain().getCodeSource().getLocation()
                    .toURI());
            assertTrue(jar + " is org.jacoco.core's jar", jar.isFile());
            try (java.util.jar.JarFile file = new java.util.jar.JarFile(jar)) {
                ZipEntry entry = file.getEntry("META-INF/maven/org.jacoco/org.jacoco.core/pom.properties");
                assertNotNull("org.jacoco.core's pom.properties in " + jar, entry);
                Properties p = new Properties();
                try (InputStream in = file.getInputStream(entry)) {
                    p.load(in);
                }
                String version = p.getProperty("version");
                assertTrue(version, version.matches("\\d+\\.\\d+\\.\\d+"));
                return version;
            }
        } catch (IOException | java.net.URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    // The whole sha256 over the same three ids: what a build table unit's
    // hash is for a unit of those class ids.
    private static final String IDS_SUM = "73ab094cbaa75db7d27e1cc885ca22604fb30a4555ebac8aae6e264612a027d0";

    @Test
    public void theUnitHashIsTheSumOverTheUnitsIdsAlone() {
        assertEquals(IDS_SUM, IndexReport.classIdsSum(new long[] {0xffffffffffffffffL, 1, 0xff, 1}));
    }

    @Test
    public void noClassHasNoBuildId() {
        assertEquals("", IndexReport.buildId(new long[0]));
    }

    @Test
    public void aClassIdIsWrittenAs16LowercaseHexDigits() {
        assertEquals("0000000000000000", IndexReport.hexId(0));
        assertEquals("0000000000000099", IndexReport.hexId(0x99L));
        assertEquals("0123456789abcdef", IndexReport.hexId(0x0123456789abcdefL));
        assertEquals("8000000000000001", IndexReport.hexId(0x8000000000000001L));
        assertEquals("ffffffffffffffff", IndexReport.hexId(-1L));
    }

    @Test
    public void theClassIdIsJacocos() {
        byte[] bytes = {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 0, 0, 0, 52, 1, 2, 3};
        assertEquals(org.jacoco.core.internal.data.CRC64.classId(bytes), IndexReport.classId(bytes));
    }

    @Test
    public void aRootIsTheLocationWithoutTheClassPath() {
        assertEquals("/app/classes", IndexReport.rootOf("/app/classes/org/acme/Foo.class", "org/acme/Foo"));
        assertEquals("/app/libs/x.jar", IndexReport.rootOf("/app/libs/x.jar!org/acme/Foo.class", "org/acme/Foo"));
        assertEquals("/app/app.jar!BOOT-INF/classes",
                IndexReport.rootOf("/app/app.jar!BOOT-INF/classes/org/acme/Foo.class", "org/acme/Foo"));
        assertEquals("/app/app.jar!BOOT-INF/lib/x.jar",
                IndexReport.rootOf("/app/app.jar!BOOT-INF/lib/x.jar!org/acme/Foo.class", "org/acme/Foo"));
        // Not the class's own path: a name that only ends the same way.
        assertNull(IndexReport.rootOf("/app/classes/org/xacme/Foo.class", "acme/Foo"));
        assertNull(IndexReport.rootOf("/app/classes/org/acme/Bar.class", "org/acme/Foo"));
        assertNull(IndexReport.rootOf("org/acme/Foo.class", "org/acme/Foo"));
        // A class dir that is the file system's root.
        assertEquals("/", IndexReport.rootOf("/org/acme/Foo.class", "org/acme/Foo"));
    }

    @Test
    public void theReportIsOneSmallJsonObjectWithNoClassList() {
        IndexReport report = new IndexReport(GOLDEN_BUILD_ID, IndexReport.SOURCE_DERIVED, "java -jar /app/app.jar",
                Arrays.asList("/app/libs/b.jar", "/app/classes", "/app/libs/b.jar"),
                Collections.singletonList("org.acme"), 3, "org.acme.*", IndexReport.JACOCO_EMBEDDED, "2.2.0", "0.8.15");
        JsonObject json = JsonParser.parseString(report.toJson()).getAsJsonObject();
        assertEquals("index", json.get("kind").getAsString());
        assertEquals(1, json.get("v").getAsInt());
        assertEquals(GOLDEN_BUILD_ID, json.get("build").getAsString());
        assertEquals("derived", json.get("source").getAsString());
        assertEquals("java -jar /app/app.jar", json.get("launch").getAsString());
        assertEquals("[\"/app/classes\",\"/app/libs/b.jar\"]", json.get("roots").toString());
        assertEquals(2, json.get("rootCount").getAsInt());
        assertEquals("[\"org.acme\"]", json.get("packages").toString());
        assertEquals(3, json.get("classes").getAsInt());
        assertEquals("org.acme.*", json.get("includes").getAsString());
        assertEquals("embedded", json.get("jacoco").getAsString());
        assertEquals("2.2.0", json.get("sdkVersion").getAsString());
        assertEquals("0.8.15", json.get("jacocoVersion").getAsString());
        assertEquals(13, json.size());
        // The versions come last, after the fields an older SDK sent.
        assertTrue(report.toJson(), report.toJson().endsWith(
                "\"jacoco\":\"embedded\",\"sdkVersion\":\"2.2.0\",\"jacocoVersion\":\"0.8.15\"}"));
        // Its kind starts the object, so a reader can tell it from a test's
        // coverage record by its first bytes.
        assertEquals(0, report.toJson().indexOf("{\"kind\":\"index\","));
        // A record of no test: old Keploy reads no test id from it.
        assertFalse(json.has("id"));
    }

    @Test
    public void manyRootsAreCutAndCounted() {
        List<String> roots = new ArrayList<>();
        for (int i = 0; i < IndexReport.MAX_ROOTS + 6; i++) {
            roots.add(String.format("/cp/lib-%03d.jar", i));
        }
        IndexReport report = new IndexReport("", IndexReport.SOURCE_CLASSPATH, "why", roots,
                Collections.<String>emptyList(), 0, "", IndexReport.JACOCO_UNCHECKED, "", "");
        assertEquals(IndexReport.MAX_ROOTS, report.roots.size());
        assertEquals(IndexReport.MAX_ROOTS + 6, report.rootCount);
        assertEquals("/cp/lib-000.jar", report.roots.get(0));
    }

    @Test
    public void theSdkVersionIsWhatItsJarsManifestNames() throws Exception {
        // As keploy-sdk.jar's build writes it: its title and the project's
        // version.
        assertEquals("2.2.0-row", versionOfMarkerIn(jarWithMarker(IndexReport.SDK_TITLE, "2.2.0-row")));
        // keploy-sdk.jar's manifest that names no version, and a class dir.
        assertEquals("", versionOfMarkerIn(jarWithMarker(IndexReport.SDK_TITLE, null)));
        File dir = tmp.newFolder();
        File copy = new File(dir, MARKER);
        assertTrue(copy.getParentFile().mkdirs());
        try (InputStream input = Marker.class.getClassLoader().getResourceAsStream(MARKER)) {
            Files.copy(input, copy.toPath());
        }
        assertEquals("", versionOfMarkerIn(dir));
        // The SDK's own classes, here read from the build's class dir.
        assertEquals("", IndexReport.versionOf(IndexReport.class));
    }

    @Test
    public void anotherJarsVersionIsNeverTakenForTheSdks() throws Exception {
        // An app's uber jar that shades the SDK's classes, or holds a copy
        // of them, names the app's version: the SDK's is not known.
        assertEquals("", versionOfMarkerIn(jarWithMarker("My App", "7.7.7-app")));
        assertEquals("", versionOfMarkerIn(jarWithMarker(null, "7.7.7-app")));
    }

    // Marker is a class versionOf is asked about, loaded from a jar or dir
    // of the row's own, by a loader of its own.
    static final class Marker {
    }

    private static final String MARKER = Marker.class.getName().replace('.', '/') + ".class";

    private File jarWithMarker(String title, String version) throws Exception {
        File jar = tmp.newFile("marker-" + System.nanoTime() + ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (title != null) {
            manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_TITLE, title);
        }
        if (version != null) {
            manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        }
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar), manifest);
             InputStream input = Marker.class.getClassLoader().getResourceAsStream(MARKER)) {
            out.putNextEntry(new ZipEntry(MARKER));
            byte[] buffer = new byte[4096];
            for (int read; (read = input.read(buffer)) != -1; ) {
                out.write(buffer, 0, read);
            }
            out.closeEntry();
        }
        return jar;
    }

    private static String versionOfMarkerIn(File from) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {from.toURI().toURL()}, null)) {
            Class<?> marker = Class.forName(Marker.class.getName(), false, loader);
            assertTrue("loaded from " + from, marker != Marker.class);
            return IndexReport.versionOf(marker);
        }
    }
}
