package io.keploy.dedup;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class IndexReportTest {

    // Keploy's codecoverage.JavaBuildID of the ids 1, 0xff and
    // 0xffffffffffffffff (its TestBuildIDs): sha256 over
    // "0000000000000001\n00000000000000ff\nffffffffffffffff".
    private static final String GO_BUILD_ID = "jv1-73ab094cbaa75db7d27e1cc885ca2260";

    @Test
    public void theBuildIdIsKeploysForTheSameIdsInAnyOrderAndRepeated() {
        assertEquals(GO_BUILD_ID, IndexReport.buildId(new long[] {1, 0xff, 0xffffffffffffffffL}));
        // A signed sort puts 0xffffffffffffffff (-1) first, a different id.
        assertEquals(GO_BUILD_ID, IndexReport.buildId(new long[] {0xffffffffffffffffL, 1, 0xff, 1}));
        assertEquals(GO_BUILD_ID, IndexReport.buildId(new long[] {0xff, 0xffffffffffffffffL, 0xff, 1}));
    }

    @Test
    public void noClassHasNoBuildId() {
        assertEquals("", IndexReport.buildId(new long[0]));
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
        IndexReport report = new IndexReport(GO_BUILD_ID, IndexReport.SOURCE_DERIVED, "java -jar /app/app.jar",
                Arrays.asList("/app/libs/b.jar", "/app/classes", "/app/libs/b.jar"),
                Collections.singletonList("org.acme"), 3, "org.acme.*", IndexReport.JACOCO_EMBEDDED);
        JsonObject json = JsonParser.parseString(report.toJson()).getAsJsonObject();
        assertEquals("index", json.get("kind").getAsString());
        assertEquals(1, json.get("v").getAsInt());
        assertEquals(GO_BUILD_ID, json.get("build").getAsString());
        assertEquals("derived", json.get("source").getAsString());
        assertEquals("java -jar /app/app.jar", json.get("launch").getAsString());
        assertEquals("[\"/app/classes\",\"/app/libs/b.jar\"]", json.get("roots").toString());
        assertEquals(2, json.get("rootCount").getAsInt());
        assertEquals("[\"org.acme\"]", json.get("packages").toString());
        assertEquals(3, json.get("classes").getAsInt());
        assertEquals("org.acme.*", json.get("includes").getAsString());
        assertEquals("embedded", json.get("jacoco").getAsString());
        assertEquals(11, json.size());
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
                Collections.<String>emptyList(), 0, "", IndexReport.JACOCO_UNCHECKED);
        assertEquals(IndexReport.MAX_ROOTS, report.roots.size());
        assertEquals(IndexReport.MAX_ROOTS + 6, report.rootCount);
        assertEquals("/cp/lib-000.jar", report.roots.get(0));
    }
}
