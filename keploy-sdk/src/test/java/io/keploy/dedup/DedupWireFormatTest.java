package io.keploy.dedup;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Locks the collector-channel wire contract the k8s-proxy dedup collector parses:
 * the COV payload's JSON shape (real Gson) and the CLASSES / MANIFEST bytecode
 * frame formats. These exercise the exact production serialize path via the
 * package-private KeployDedupAgent.toDedupJson / encodeBytecodeFrame helpers.
 *
 * The frame decoding here mirrors k8s-proxy's handleClassesFrame /
 * handleManifestFrame: split the payload (after the "CLASSES "/"MANIFEST "
 * prefix) on whitespace, then standard-base64-decode each field.
 */
public class DedupWireFormatTest {

    // --- COV payload (real Gson serialization) ---

    @Test
    public void covPayloadCarriesIdAndProbesUnderHistoricalKey() {
        Map<String, List<Integer>> probes = new LinkedHashMap<>();
        probes.put("com/foo/Bar", Arrays.asList(0, 1, 5));
        probes.put("smoke/Work", Collections.singletonList(2));

        String json = KeployDedupAgent.toDedupJson("test-set-0/smoke", probes);

        assertTrue("id present", json.contains("\"id\":\"test-set-0/smoke\""));
        // Historical wire key retained for consumer compatibility; now carries
        // {vmClassName -> [probeIdx]} (NOT source lines by file).
        assertTrue("historical key present", json.contains("\"executedLinesByFile\""));
        assertTrue("vm class-name key (not a source file)", json.contains("\"com/foo/Bar\""));
        assertTrue("second class key", json.contains("\"smoke/Work\""));
        assertTrue("probe indices for Bar", json.contains("[0,1,5]"));
    }

    @Test
    public void covPayloadWithNoCoverageSerializesEmptyMap() {
        String json = KeployDedupAgent.toDedupJson(
                "test-set-0/empty", Collections.<String, List<Integer>>emptyMap());
        assertTrue("empty coverage still emits the key",
                json.contains("\"executedLinesByFile\":{}"));
    }

    // --- CLASSES frame (full: sent once per connection) ---

    @Test
    public void classesFrameEncodesTagManifestZipAsThreeBase64Fields() {
        String tag = "docker.io/library/simple-java-dedup:parity";
        String manifest = "{\"com/foo/Bar\":{\"id\":\"ab\",\"probeCount\":6}}";
        byte[] zip = new byte[]{0x50, 0x4b, 0x03, 0x04, 0x11, 0x22}; // "PK.." zip magic + bytes

        String frame = KeployDedupAgent.encodeBytecodeFrame(tag, manifest, zip);

        assertTrue("CLASSES prefix", frame.startsWith("CLASSES "));
        String[] parts = frame.substring("CLASSES ".length()).split("\\s+");
        assertEquals("three fields: tag, manifest, zip", 3, parts.length);
        assertEquals(tag, decode(parts[0]));
        assertEquals(manifest, decode(parts[1]));
        assertArrayEquals(zip, Base64.getDecoder().decode(parts[2]));
    }

    // --- MANIFEST frame (lightweight update: no zip) ---

    @Test
    public void manifestFrameEncodesTagManifestAsTwoBase64Fields() {
        String tag = "docker.io/library/simple-java-dedup:parity";
        String manifest = "{\"com/foo/Bar\":{\"id\":\"ab\",\"probeCount\":6},"
                + "\"smoke/Work\":{\"id\":\"cd\",\"probeCount\":2}}";

        String frame = KeployDedupAgent.encodeBytecodeFrame(tag, manifest, null);

        assertTrue("MANIFEST prefix (no zip)", frame.startsWith("MANIFEST "));
        assertTrue("carries no CLASSES/zip", !frame.startsWith("CLASSES "));
        String[] parts = frame.substring("MANIFEST ".length()).split("\\s+");
        assertEquals("two fields: tag, manifest", 2, parts.length);
        assertEquals(tag, decode(parts[0]));
        assertEquals(manifest, decode(parts[1]));
    }

    @Test
    public void framesAreSingleLine() {
        String frameC = KeployDedupAgent.encodeBytecodeFrame("t", "{}", new byte[]{1, 2, 3});
        String frameM = KeployDedupAgent.encodeBytecodeFrame("t", "{}", null);
        // The collector reads one frame per line; a base64 field must never embed
        // a newline that would split the frame across reads.
        assertTrue("CLASSES frame is one line", frameC.indexOf('\n') < 0);
        assertTrue("MANIFEST frame is one line", frameM.indexOf('\n') < 0);
    }

    private static String decode(String b64) {
        return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
    }
}
