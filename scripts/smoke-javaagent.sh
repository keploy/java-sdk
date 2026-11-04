#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
module_dir="${repo_root}/keploy-sdk"
mkdir -p "${module_dir}/target"
agent_jar="$(find "${module_dir}/target" -maxdepth 1 -type f -name 'keploy-sdk-*.jar' \
  ! -name 'original-*' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort | head -n 1)"

if [[ -z "${agent_jar}" || ! -f "${agent_jar}" ]]; then
  echo "Missing agent jar under ${module_dir}/target" >&2
  echo "Run mvn -B -DskipTests package first." >&2
  exit 1
fi

mvn -q -f "${repo_root}/pom.xml" org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy \
  -Dartifact=org.jacoco:org.jacoco.agent:0.8.12:jar:runtime \
  -DoutputDirectory="${module_dir}/target" \
  -DdestFileName=jacocoagent.jar

# The tcp-report run below compares against the released keploy-sdk 2.1.1
# CoverageReporter (the coverage-runner Job's), and runs once more with an
# older ASM ahead of the SDK on the application classpath.
# (copy names each jar artifactId-version.jar.)
mvn -q -f "${repo_root}/pom.xml" org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy \
  -Dartifact=io.keploy:keploy-sdk:2.1.1:jar \
  -DoutputDirectory="${module_dir}/target/smoke-deps"
mvn -q -f "${repo_root}/pom.xml" org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy \
  -Dartifact=org.ow2.asm:asm:5.0.4:jar \
  -DoutputDirectory="${module_dir}/target/smoke-deps"
runner_reporter_jar="${module_dir}/target/smoke-deps/keploy-sdk-2.1.1.jar"
older_asm_jar="${module_dir}/target/smoke-deps/asm-5.0.4.jar"
for jar in "${runner_reporter_jar}" "${older_asm_jar}"; do
  if [[ ! -f "${jar}" ]]; then
    echo "missing ${jar}" >&2
    exit 1
  fi
done

jacoco_jar="$(find "${module_dir}/target" -maxdepth 1 -type f \( \
  -name 'jacocoagent.jar' -o -name 'org.jacoco.agent-*-runtime.jar' \) | sort | head -n 1)"
if [[ -z "${jacoco_jar}" || ! -f "${jacoco_jar}" ]]; then
  echo "Missing JaCoCo runtime agent jar under ${module_dir}/target" >&2
  exit 1
fi

work_dir="$(mktemp -d)"
cleanup() {
  rm -rf "${work_dir}"
  rm -f /tmp/coverage_control.sock /tmp/coverage_data.sock /tmp/keploy-sdk-smoke-*.exec
}
trap cleanup EXIT

mkdir -p "${work_dir}/src/smoke" "${work_dir}/classes"

cat > "${work_dir}/src/smoke/Work.java" <<'JAVA'
package smoke;

final class Work {
    private Work() {
    }

    static int exercise(int input) {
        int value = input + 1;
        if (value > 1) {
            value = value * 2;
        }
        return value;
    }
}
JAVA

cat > "${work_dir}/src/smoke/SmokeHarness.java" <<'JAVA'
package smoke;

import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class SmokeHarness {
    private static final File CONTROL_SOCKET = new File("/tmp/coverage_control.sock");
    private static final File DATA_SOCKET = new File("/tmp/coverage_data.sock");

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "unknown" : args[0];
        delete(DATA_SOCKET);

        AtomicReference<String> payload = new AtomicReference<String>();
        CountDownLatch received = new CountDownLatch(1);
        Thread receiver = new Thread(new Runnable() {
            @Override
            public void run() {
                try (AFUNIXServerSocket server = AFUNIXServerSocket.newInstance()) {
                    server.bind(AFUNIXSocketAddress.of(DATA_SOCKET), 1);
                    try (AFUNIXSocket socket = server.accept()) {
                        payload.set(readAll(socket.getInputStream()));
                        received.countDown();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "coverage-data-receiver");
        receiver.setDaemon(true);
        receiver.start();

        waitFor(CONTROL_SOCKET);
        waitFor(DATA_SOCKET);

        command("START test-set-0/" + mode);
        if (Work.exercise(1) != 4) {
            throw new IllegalStateException("unexpected application result");
        }
        command("END test-set-0/" + mode);

        if (!received.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timed out waiting for coverage payload in " + mode);
        }

        String json = payload.get();
        // The fingerprint is keyed by VM class name (probe-based coverage),
        // e.g. "smoke/Work" — not a source file like "Work.java".
        if (json == null
                || !json.contains("\"id\":\"test-set-0/" + mode + "\"")
                || !json.contains("smoke/Work")) {
            throw new IllegalStateException("unexpected coverage payload for " + mode + ": " + json);
        }
        System.out.println(mode + ": " + json);
    }

    private static void command(String command) throws Exception {
        try (AFUNIXSocket socket = AFUNIXSocket.newInstance()) {
            socket.connect(AFUNIXSocketAddress.of(CONTROL_SOCKET), 3000);
            OutputStream output = socket.getOutputStream();
            output.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            String ack = readAll(socket.getInputStream());
            if (!ack.contains("ACK")) {
                throw new IllegalStateException("missing ACK for " + command + ": " + ack);
            }
        }
    }

    private static void waitFor(File file) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10000L;
        while (System.currentTimeMillis() < deadline) {
            if (file.exists()) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new IllegalStateException("timed out waiting for " + file);
    }

    private static String readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void delete(File file) {
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("failed to delete " + file);
        }
    }
}
JAVA

# The in-JVM coverage report (TCP transport): a sample app runs two test cases
# under JaCoCo, the harness plays the collector (START/END, COV/CLASSES frames,
# then REPORT), and the in-JVM numbers must equal the offline CoverageReporter's
# (this build's and the released one the coverage-runner Job ships), run exactly
# as the coverage-runner Job runs it, on the same inputs. Two app classes never
# run (Unused, and Tier, an interface with no code), so the totals must count
# classes no probe fired in.
mkdir -p "${work_dir}/src/tcp/smoke" "${work_dir}/tcp-app" "${work_dir}/tcp-harness"
cat > "${work_dir}/src/tcp/smoke/Shop.java" <<'JAVA'
package smoke;

import java.util.ArrayList;
import java.util.List;

public final class Shop {
    private Shop() {
    }

    public static int price(int quantity, String tier) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        int unit;
        switch (tier) {
            case "gold":
                unit = 7;
                break;
            case "silver":
                unit = 9;
                break;
            default:
                unit = 10;
        }
        Basket basket = new Basket();
        for (int i = 0; i < quantity; i++) {
            basket.add(unit);
        }
        int total = basket.total();
        return quantity >= 10 && !"gold".equals(tier) ? total - total / 10 : total;
    }

    public static int parse(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static final class Basket {
        private final List<Integer> items = new ArrayList<>();

        void add(int price) {
            items.add(price);
        }

        int total() {
            return items.stream().mapToInt(p -> p > 0 ? p : 0).sum();
        }
    }
}
JAVA

cat > "${work_dir}/src/tcp/smoke/Unused.java" <<'JAVA'
package smoke;

public final class Unused {
    private Unused() {
    }

    public static int tier(int n) {
        if (n > 10) {
            return 2;
        }
        return n < 0 ? -1 : 1;
    }
}
JAVA

cat > "${work_dir}/src/tcp/smoke/Tier.java" <<'JAVA'
package smoke;

public interface Tier {
    int unit();
}
JAVA

cat > "${work_dir}/src/tcp/smoke/SmokeTcpHarness.java" <<'JAVA'
package smoke;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public final class SmokeTcpHarness {
    private static final Gson GSON = new Gson();
    private static final Map<String, TreeSet<Integer>> UNION = new TreeMap<String, TreeSet<Integer>>();
    private static byte[] zip;
    private static String manifest;

    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args[0]);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(20000);
            System.setProperty("keploy.coverage.endpoint", "127.0.0.1:" + server.getLocalPort());
            System.setProperty("keploy.build.tag", "smoke-tcp");
            // The same start() the -javaagent premain calls, once the port is known.
            if (!io.keploy.dedup.KeployDedupAgent.start()) {
                throw new IllegalStateException("the SDK did not start");
            }
            try (Socket conn = server.accept()) {
                conn.setSoTimeout(20000);
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(),
                        StandardCharsets.UTF_8));
                OutputStream os = conn.getOutputStream();
                expect("CAPS REPORT", in.readLine());

                send(os, "START test-set-0/t1");
                expect("ACK", in.readLine());
                if (Shop.price(3, "gold") != 21) {
                    throw new IllegalStateException("unexpected application result");
                }
                send(os, "END test-set-0/t1");
                readUntilAck(in);

                send(os, "START test-set-0/t2");
                expect("ACK", in.readLine());
                if (Shop.price(10, "bronze") != 90 || Shop.parse("x1") != -1) {
                    throw new IllegalStateException("unexpected application result");
                }
                send(os, "END test-set-0/t2");
                readUntilAck(in);

                if (zip == null || manifest == null || UNION.isEmpty()) {
                    throw new IllegalStateException("no CLASSES frame or no COV probes: union=" + UNION);
                }
                Map<String, List<Integer>> union = new TreeMap<String, List<Integer>>();
                for (Map.Entry<String, TreeSet<Integer>> e : UNION.entrySet()) {
                    union.put(e.getKey(), new ArrayList<Integer>(e.getValue()));
                }
                Files.write(out.resolve("classes.zip"), zip);
                Files.write(out.resolve("manifest.json"), manifest.getBytes(StandardCharsets.UTF_8));
                Files.write(out.resolve("union.json"), GSON.toJson(union).getBytes(StandardCharsets.UTF_8));

                // The union alone (the SDK's live manifest), then with the stored manifest.
                Map<String, Object> request = new LinkedHashMap<String, Object>();
                request.put("union", union);
                report(in, os, request, out.resolve("injvm-live.json"));
                request.put("manifest", GSON.fromJson(manifest, JsonObject.class));
                report(in, os, request, out.resolve("injvm-manifest.json"));
            }
        }
        io.keploy.dedup.KeployDedupAgent.stop();
    }

    private static void report(BufferedReader in, OutputStream os, Map<String, Object> request, Path dest)
            throws Exception {
        send(os, "REPORT test-set-0 " + GSON.toJson(request));
        String reply = in.readLine();
        expect("ACK", in.readLine());
        if (reply == null || !reply.startsWith("REPORT {")) {
            throw new IllegalStateException("unexpected REPORT reply: " + reply);
        }
        JsonObject json = GSON.fromJson(reply.substring("REPORT ".length()), JsonObject.class);
        if (json.has("error") || !"test-set-0".equals(json.get("testSetId").getAsString())) {
            throw new IllegalStateException("REPORT failed: " + reply);
        }
        json.remove("testSetId");
        Files.write(dest, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
        System.out.println("tcp-report: " + dest.getFileName() + " " + json);
    }

    private static void readUntilAck(BufferedReader in) throws Exception {
        Base64.Decoder dec = Base64.getDecoder();
        String line;
        while ((line = in.readLine()) != null && !line.equals("ACK")) {
            if (line.startsWith("COV ")) {
                JsonObject cov = GSON.fromJson(line.substring(4), JsonObject.class);
                Map<String, List<Integer>> probes = GSON.fromJson(cov.get("executedLinesByFile"),
                        new TypeToken<Map<String, List<Integer>>>() { }.getType());
                for (Map.Entry<String, List<Integer>> e : probes.entrySet()) {
                    if (!UNION.containsKey(e.getKey())) {
                        UNION.put(e.getKey(), new TreeSet<Integer>());
                    }
                    UNION.get(e.getKey()).addAll(e.getValue());
                }
            } else if (line.startsWith("CLASSES ")) {
                String[] f = line.substring(8).split(" ");
                manifest = new String(dec.decode(f[1]), StandardCharsets.UTF_8);
                zip = dec.decode(f[2]);
            } else if (line.startsWith("MANIFEST ")) {
                manifest = new String(dec.decode(line.substring(9).split(" ")[1]), StandardCharsets.UTF_8);
            }
        }
        if (line == null) {
            throw new IllegalStateException("the SDK closed the connection before ACK");
        }
    }

    private static void send(OutputStream os, String line) throws Exception {
        os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static void expect(String want, String got) {
        if (!want.equals(got)) {
            throw new IllegalStateException("expected '" + want + "', got '" + got + "'");
        }
    }
}
JAVA

cat > "${work_dir}/src/tcp/smoke/SmokeCompare.java" <<'JAVA'
package smoke;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class SmokeCompare {
    // args[0] is the released reporter's output (the coverage-runner Job's);
    // every other output must equal it.
    public static void main(String[] args) throws Exception {
        Gson gson = new Gson();
        JsonObject offline = read(gson, args[0]);
        for (int i = 1; i < args.length; i++) {
            JsonObject other = read(gson, args[i]);
            if (!offline.equals(other)) {
                throw new IllegalStateException(args[i] + " differs from the coverage-runner's reporter: "
                        + other + " vs " + offline);
            }
        }
        long lineCovered = offline.get("lineCovered").getAsLong();
        long lineTotal = offline.get("lineTotal").getAsLong();
        long branchCovered = offline.get("branchCovered").getAsLong();
        long branchTotal = offline.get("branchTotal").getAsLong();
        // Shop + Shop$Basket ran partly, Unused and Tier not at all: four classes,
        // two hit, and both counters partly covered.
        if (offline.get("classCount").getAsInt() != 4 || offline.get("hitClassCount").getAsInt() != 2
                || lineCovered <= 0 || lineCovered >= lineTotal
                || branchCovered <= 0 || branchCovered >= branchTotal) {
            throw new IllegalStateException("implausible coverage: " + offline);
        }
        System.out.println("tcp-report: " + (args.length - 1) + " outputs equal the coverage-runner's: " + offline);
    }

    private static JsonObject read(Gson gson, String path) throws Exception {
        return gson.fromJson(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8),
                JsonObject.class);
    }
}
JAVA

javac -cp "${agent_jar}" -d "${work_dir}/tcp-app" "${work_dir}/src/tcp/smoke/Shop.java" \
  "${work_dir}/src/tcp/smoke/Unused.java" "${work_dir}/src/tcp/smoke/Tier.java"
javac -cp "${work_dir}/tcp-app:${agent_jar}" -d "${work_dir}/tcp-harness" \
  "${work_dir}/src/tcp/smoke/SmokeTcpHarness.java" \
  "${work_dir}/src/tcp/smoke/SmokeCompare.java"

javac -cp "${agent_jar}" -d "${work_dir}/classes" \
  "${work_dir}/src/smoke/Work.java" \
  "${work_dir}/src/smoke/SmokeHarness.java"

run_smoke() {
  local mode="${1:?mode required}"
  shift

  echo "Running Java agent smoke: ${mode}"
  rm -f /tmp/coverage_control.sock /tmp/coverage_data.sock "/tmp/keploy-sdk-smoke-${mode}.exec"
  "$@"
}

java_with_agents() {
  local mode="${1:?mode required}"
  shift

  java \
    -javaagent:"${agent_jar}" \
    -javaagent:"${jacoco_jar}=destfile=/tmp/keploy-sdk-smoke-${mode}.exec" \
    "$@"
}

mkdir -p "${work_dir}/maven/target/classes" "${work_dir}/gradle/build/classes/java/main"
cp -R "${work_dir}/classes/." "${work_dir}/maven/target/classes/"
cp -R "${work_dir}/classes/." "${work_dir}/gradle/build/classes/java/main/"

(
  cd "${work_dir}/maven"
  run_smoke "maven-classes" \
    java_with_agents "maven-classes" \
      -cp "target/classes:${agent_jar}" \
      smoke.SmokeHarness "maven-classes"
)

(
  cd "${work_dir}/gradle"
  run_smoke "gradle-classes" \
    java_with_agents "gradle-classes" \
      -cp "build/classes/java/main:${agent_jar}" \
      smoke.SmokeHarness "gradle-classes"
)

run_smoke "classpath-fallback" \
  java_with_agents "classpath-fallback" \
    -cp "${work_dir}/classes:${agent_jar}" \
    smoke.SmokeHarness "classpath-fallback"

mkdir -p "${work_dir}/jar/lib"
cp "${agent_jar}" "${work_dir}/jar/lib/keploy-sdk.jar"
cat > "${work_dir}/jar/MANIFEST.MF" <<EOF
Manifest-Version: 1.0
Main-Class: smoke.SmokeHarness
Class-Path: lib/keploy-sdk.jar

EOF
jar cfm "${work_dir}/jar/plain-app.jar" "${work_dir}/jar/MANIFEST.MF" -C "${work_dir}/classes" .
(
  cd "${work_dir}/jar"
  run_smoke "plain-jar" \
    java_with_agents "plain-jar" \
      -jar plain-app.jar "plain-jar"
)

mkdir -p "${work_dir}/boot-root/BOOT-INF/classes" "${work_dir}/war-root/WEB-INF/classes"
cp -R "${work_dir}/classes/." "${work_dir}/boot-root/BOOT-INF/classes/"
cp -R "${work_dir}/classes/." "${work_dir}/war-root/WEB-INF/classes/"
jar cf "${work_dir}/spring-boot-layout.jar" -C "${work_dir}/boot-root" .
jar cf "${work_dir}/servlet-layout.war" -C "${work_dir}/war-root" .

KEPLOY_JAVA_CLASS_DIRS="${work_dir}/spring-boot-layout.jar" \
run_smoke "spring-boot-layout" \
  java_with_agents "spring-boot-layout" \
    -cp "${work_dir}/classes:${agent_jar}" \
    smoke.SmokeHarness "spring-boot-layout"

KEPLOY_JAVA_CLASS_DIRS="${work_dir}/servlet-layout.war" \
run_smoke "servlet-war-layout" \
  java_with_agents "servlet-war-layout" \
    -cp "${work_dir}/classes:${agent_jar}" \
    smoke.SmokeHarness "servlet-war-layout"

tcp_dir="${work_dir}/tcp"
mkdir -p "${tcp_dir}/runner-classes"
echo "Running Java agent smoke: tcp-report"
java \
  -javaagent:"${jacoco_jar}=destfile=${tcp_dir}/jacoco.exec" \
  -Dkeploy.java.class.dirs="${work_dir}/tcp-app" \
  -Dkeploy.java.classpath.fallback=false \
  -cp "${work_dir}/tcp-app:${work_dir}/tcp-harness:${agent_jar}" \
  smoke.SmokeTcpHarness "${tcp_dir}"
# The coverage-runner Job's own steps (coverage-run.sh): unzip the CLASSES zip
# into a directory, then run the offline reporter on it.
(cd "${tcp_dir}/runner-classes" && jar xf ../classes.zip)
# runner.json is the released reporter's, offline.json this build's.
java -cp "${runner_reporter_jar}" io.keploy.dedup.CoverageReporter \
  --classes "${tcp_dir}/runner-classes" --union "${tcp_dir}/union.json" \
  --manifest "${tcp_dir}/manifest.json" --out "${tcp_dir}/runner.json"
java -cp "${agent_jar}" io.keploy.dedup.CoverageReporter \
  --classes "${tcp_dir}/runner-classes" --union "${tcp_dir}/union.json" \
  --manifest "${tcp_dir}/manifest.json" --out "${tcp_dir}/offline.json"
java -cp "${work_dir}/tcp-harness:${agent_jar}" smoke.SmokeCompare "${tcp_dir}/runner.json" \
  "${tcp_dir}/offline.json" "${tcp_dir}/injvm-live.json" "${tcp_dir}/injvm-manifest.json"

# The same app shipping an older org.objectweb.asm ahead of the SDK on its flat
# classpath: the SDK's own (relocated) ASM still analyzes the classes. The ASM
# jar is also one of the app's class roots, and its classes must stay out of
# the index (the CLASSES zip and the totals), as before the relocation.
tcp_asm_dir="${work_dir}/tcp-older-asm"
mkdir -p "${tcp_asm_dir}"
echo "Running Java agent smoke: tcp-report-older-asm"
java \
  -javaagent:"${jacoco_jar}=destfile=${tcp_asm_dir}/jacoco.exec" \
  -Dkeploy.java.class.dirs="${work_dir}/tcp-app,${older_asm_jar}" \
  -Dkeploy.java.classpath.fallback=false \
  -cp "${older_asm_jar}:${work_dir}/tcp-app:${work_dir}/tcp-harness:${agent_jar}" \
  smoke.SmokeTcpHarness "${tcp_asm_dir}"
if ! diff <(jar tf "${tcp_dir}/classes.zip" | sort) <(jar tf "${tcp_asm_dir}/classes.zip" | sort); then
  echo "tcp-report-older-asm: the CLASSES zip differs when the app ships ASM" >&2
  exit 1
fi
java -cp "${work_dir}/tcp-harness:${agent_jar}" smoke.SmokeCompare "${tcp_dir}/runner.json" \
  "${tcp_asm_dir}/injvm-live.json" "${tcp_asm_dir}/injvm-manifest.json"
