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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

public final class SmokeHarness {
    private static final File CONTROL_SOCKET = new File("/tmp/coverage_control.sock");
    private static final File DATA_SOCKET = new File("/tmp/coverage_data.sock");

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "unknown" : args[0];
        // What the INDEX report must say: where the app's classes came from,
        // and which JaCoCo measures them.
        String wantSource = args.length > 1 ? args[1] : "";
        String wantJacoco = args.length > 2 ? args[2] : "";
        // How many classes the index must hold, when the case says.
        String wantClasses = args.length > 3 ? args[3] : "";

        // Keploy fires its first test as soon as the app answers a health
        // check, so the agent has to be listening before main runs. This START
        // is main's first act and nothing waits for the control socket: an
        // agent that binds it any later fails here.
        command("START test-set-0/" + mode);

        delete(DATA_SOCKET);

        // Keploy's receiver: one record per connection. This one is bound
        // after the START: no socket file was there when the SDK bound its
        // control socket, so it sent no INDEX report then, and it must send
        // it here first, before the test's coverage.
        final BlockingQueue<String> records = new LinkedBlockingQueue<String>();
        Thread receiver = new Thread(new Runnable() {
            @Override
            public void run() {
                try (AFUNIXServerSocket server = AFUNIXServerSocket.newInstance()) {
                    server.bind(AFUNIXSocketAddress.of(DATA_SOCKET), 8);
                    for (;;) {
                        try (AFUNIXSocket socket = server.accept()) {
                            records.add(readAll(socket.getInputStream()));
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "coverage-data-receiver");
        receiver.setDaemon(true);
        receiver.start();

        waitFor(DATA_SOCKET);

        if (Work.exercise(1) != 4) {
            throw new IllegalStateException("unexpected application result");
        }
        command("END test-set-0/" + mode);

        String json = next(records, mode);
        String report = null;
        if (json.startsWith("{\"kind\":\"index\",")) {
            report = json;
            json = next(records, mode);
        }
        // The fingerprint is keyed by VM class name (probe-based coverage),
        // e.g. "smoke/Work" — not a source file like "Work.java". JaCoCo's id
        // of each class the app ran is the id of the bytes the index read,
        // so the record names no class in classMismatch: one named here
        // would lose that class's coverage in Keploy.
        if (!json.contains("\"id\":\"test-set-0/" + mode + "\"")
                || !json.contains("smoke/Work")
                || json.contains("\"classMismatch\"")) {
            throw new IllegalStateException("unexpected coverage payload for " + mode + ": " + json);
        }
        if (report == null
                || !report.contains("\"source\":\"" + wantSource + "\"")
                || !report.contains("\"jacoco\":\"" + wantJacoco + "\"")
                || ("embedded".equals(wantJacoco) != report.matches(".*\"includes\":\"[^\"]*smoke\\.Work.*"))
                || (!wantClasses.isEmpty() && !report.matches(".*\"classes\":" + wantClasses + "[,}].*"))) {
            throw new IllegalStateException("the first record on the data socket was no INDEX report with source "
                    + wantSource + ", jacoco " + wantJacoco
                    + (wantClasses.isEmpty() ? "" : " and " + wantClasses + " classes") + " in " + mode + ": " + report);
        }
        // keploy-sdk's version and the measuring JaCoCo's, as their jars'
        // manifests name them: the one the command attached, or the one
        // keploy-sdk.jar carries and started.
        String wantSdkVersion = versionOf(new File(System.getProperty("smoke.sdkJar")), null);
        String wantJacocoVersion = "attached".equals(wantJacoco)
                ? versionOf(new File(System.getProperty("smoke.jacocoJar")), null)
                : "embedded".equals(wantJacoco)
                ? versionOf(new File(System.getProperty("smoke.sdkJar")), "jacocoagent.jar") : "";
        if (wantSdkVersion.isEmpty() || wantJacocoVersion.isEmpty() != !wantJacoco.matches("attached|embedded")
                || !report.endsWith(",\"sdkVersion\":\"" + wantSdkVersion + "\",\"jacocoVersion\":\""
                        + wantJacocoVersion + "\"}")) {
            throw new IllegalStateException("the INDEX report did not name keploy-sdk " + wantSdkVersion
                    + " and JaCoCo " + wantJacocoVersion + " in " + mode + ": " + report);
        }
        String ownLibraries = System.getProperty("smoke.ownLibraries", "");
        if (!ownLibraries.isEmpty()) {
            loadsItsOwn(ownLibraries);
        }
        System.out.println(mode + ": " + report);
        System.out.println(mode + ": " + json);
        String otherWork = System.getProperty("smoke.otherWork", "");
        if (!otherWork.isEmpty()) {
            runsAnotherCopy(otherWork, mode, records);
        }
    }

    // runsAnotherCopy runs a test in which only another smoke.Work runs:
    // one built from other source, which the index never read, in a loader
    // of its own (as a second web app or an agent's woven copy would be).
    // JaCoCo measures it under the same name with another id, so the
    // record must send its probes under smoke/Work and name smoke/Work in
    // classMismatch, and no other class.
    private static void runsAnotherCopy(String dir, String mode, BlockingQueue<String> records)
            throws Exception {
        String id = "test-set-0/" + mode + "-another-copy";
        URLClassLoader loader = new URLClassLoader(new URL[] {new File(dir).toURI().toURL()}, null);
        try {
            Class<?> other = Class.forName("smoke.Work", false, loader);
            command("START " + id);
            Object result = other.getMethod("exercise", int.class).invoke(null, 1);
            command("END " + id);
            if (!Integer.valueOf(3).equals(result)) {
                throw new IllegalStateException("the copy in " + dir + " did not run in " + mode + ": " + result);
            }
            String json = next(records, mode);
            if (!json.contains("\"id\":\"" + id + "\"")
                    || !json.contains("\"smoke/Work\":[")
                    || !json.contains(",\"classMismatch\":[\"smoke/Work\"]}")) {
                throw new IllegalStateException("the record of a test that ran another copy of smoke/Work does"
                        + " not name it, alone, in classMismatch in " + mode + ": " + json);
            }
            System.out.println(mode + ": " + json);
        } finally {
            loader.close();
        }
    }

    private static String next(BlockingQueue<String> records, String mode) throws InterruptedException {
        String record = records.poll(10, TimeUnit.SECONDS);
        if (record == null) {
            throw new IllegalStateException("timed out waiting for a record on the data socket in " + mode);
        }
        return record;
    }

    // loadsItsOwn loads ASM, Gson and JaCoCo's core from the app's own jars
    // as Spring Boot's launcher loads BOOT-INF/lib: through a loader that asks
    // its parent, the system class loader, first. keploy-sdk.jar is on the
    // system class path, so a copy of these it carried under their own names
    // would be what the app gets, in keploy-sdk's version, not the app's.
    private static void loadsItsOwn(String jars) throws Exception {
        String[] paths = jars.split(File.pathSeparator);
        URL[] urls = new URL[paths.length];
        for (int i = 0; i < paths.length; i++) {
            urls[i] = new File(paths[i]).toURI().toURL();
        }
        URLClassLoader libraries = new URLClassLoader(urls, ClassLoader.getSystemClassLoader());
        try {
            for (String name : new String[] {
                    "org.objectweb.asm.ClassReader", "com.google.gson.Gson", "org.jacoco.core.JaCoCo"}) {
                Class<?> type = Class.forName(name, false, libraries);
                URL from = type.getProtectionDomain().getCodeSource() == null
                        ? null : type.getProtectionDomain().getCodeSource().getLocation();
                boolean own = false;
                for (URL url : urls) {
                    own |= from != null && new File(from.toURI()).equals(new File(url.toURI()));
                }
                if (!own) {
                    throw new IllegalStateException("the app's " + name + " came from " + from
                            + ", not from the app's own jars " + jars);
                }
            }
        } finally {
            libraries.close();
        }
    }

    private static void command(String command) throws Exception {
        try (AFUNIXSocket socket = AFUNIXSocket.newInstance()) {
            socket.connect(AFUNIXSocketAddress.of(CONTROL_SOCKET), 3000);
            OutputStream output = socket.getOutputStream();
            output.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            // The whole answer: one line saying the command was taken, then
            // the close.
            String ack = readAll(socket.getInputStream());
            if (!"ACK proto=2\n".equals(ack)) {
                throw new IllegalStateException("not taken: " + command + ": " + ack);
            }
        }
    }

    // versionOf is the Implementation-Version of jar's manifest, or of the
    // manifest of the jar nested in it at entry when entry is not null.
    private static String versionOf(File jar, String entry) throws Exception {
        try (JarFile file = new JarFile(jar)) {
            Manifest manifest = file.getManifest();
            if (entry != null) {
                try (JarInputStream nested = new JarInputStream(file.getInputStream(file.getEntry(entry)))) {
                    manifest = nested.getManifest();
                }
            }
            String version = manifest == null ? null : manifest.getMainAttributes().getValue("Implementation-Version");
            return version == null ? "" : version;
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

cat > "${work_dir}/src/smoke/PlainApp.java" <<'JAVA'
package smoke;

public final class PlainApp {
    private PlainApp() {
    }

    public static void main(String[] args) {
        System.out.println("plain-app: main ran");
    }
}
JAVA

# Another smoke.Work, from other source, compiled into a dir of its own that
# the index never reads: the copy runsAnotherCopy loads.
mkdir -p "${work_dir}/other-src/smoke" "${work_dir}/other-work"
cat > "${work_dir}/other-src/smoke/Work.java" <<'JAVA'
package smoke;

public final class Work {
    private Work() {
    }

    public static int exercise(int input) {
        return input * 3;
    }
}
JAVA
javac -d "${work_dir}/other-work" "${work_dir}/other-src/smoke/Work.java"

javac -cp "${agent_jar}" -d "${work_dir}/classes" \
  "${work_dir}/src/smoke/Work.java" \
  "${work_dir}/src/smoke/SmokeHarness.java" \
  "${work_dir}/src/smoke/PlainApp.java"

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
    -Dsmoke.sdkJar="${agent_jar}" -Dsmoke.jacocoJar="${jacoco_jar}" \
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
      smoke.SmokeHarness "maven-classes" derived attached
)

(
  cd "${work_dir}/gradle"
  run_smoke "gradle-classes" \
    java_with_agents "gradle-classes" \
      -cp "build/classes/java/main:${agent_jar}" \
      smoke.SmokeHarness "gradle-classes" derived attached
)

# Named for the whole-classpath fallback this layout once took: the agent now
# takes the dir that holds the main class (its package, smoke, has one part,
# so the INDEX lists no package root).
run_smoke "classpath-fallback" \
  java_with_agents "classpath-fallback" \
    -cp "${work_dir}/classes:${agent_jar}" \
    smoke.SmokeHarness "classpath-fallback" derived attached

# With JaCoCo's agent in the JVM, a START and an END read and clear the app's
# probes in place, in one pass, instead of taking JaCoCo's reset and dump.
# This is the real (shaded) JaCoCo runtime, which the unit tests do not load:
# its layout must be the one the pass reads. The END's diagnostics line names
# the step: `scan=` for the pass, `dump=` for JaCoCo's dump. A second test
# runs only another copy of smoke.Work (smoke.otherWork): a class JaCoCo
# first sees there, which the pass must find, and name in classMismatch.
scan_log="${work_dir}/in-place-scan.log"
scan_rc=0
KEPLOY_JAVA_DEDUP_DIAGNOSTICS=1 run_smoke "in-place-scan" \
  java_with_agents "in-place-scan" \
    -Dsmoke.otherWork="${work_dir}/other-work" \
    -cp "${work_dir}/classes:${agent_jar}" \
    smoke.SmokeHarness "in-place-scan" derived attached 2>"${scan_log}" || scan_rc=$?
if [[ "${scan_rc}" -ne 0 ]] \
  || ! grep -q 'diagnostics: START test-set-0/in-place-scan took .* scan=' "${scan_log}" \
  || ! grep -q 'diagnostics: END test-set-0/in-place-scan took .* scan=' "${scan_log}" \
  || ! grep -q 'diagnostics: END test-set-0/in-place-scan-another-copy took .* scan=' "${scan_log}" \
  || grep -q 'cannot be read in place\|the probe scan failed' "${scan_log}"; then
  cat "${scan_log}" >&2
  echo "in-place-scan: the agent did not read JaCoCo's probes in place (exit ${scan_rc})" >&2
  exit 1
fi
echo "in-place-scan: START and END read JaCoCo's probes in place"

# KEPLOY_JAVA_SCAN=off: each test takes JaCoCo's dump and reset instead, and
# its records must be the pass's: no class named for the app's own copy, and
# the other copy named.
dump_log="${work_dir}/jacoco-dump.log"
dump_rc=0
KEPLOY_JAVA_SCAN=off KEPLOY_JAVA_DEDUP_DIAGNOSTICS=1 run_smoke "jacoco-dump" \
  java_with_agents "jacoco-dump" \
    -Dsmoke.otherWork="${work_dir}/other-work" \
    -cp "${work_dir}/classes:${agent_jar}" \
    smoke.SmokeHarness "jacoco-dump" derived attached 2>"${dump_log}" || dump_rc=$?
if [[ "${dump_rc}" -ne 0 ]] \
  || ! grep -q 'diagnostics: END test-set-0/jacoco-dump took .* dump=' "${dump_log}" \
  || ! grep -q 'diagnostics: END test-set-0/jacoco-dump-another-copy took .* dump=' "${dump_log}" \
  || grep -q 'diagnostics: END test-set-0/jacoco-dump.* scan=' "${dump_log}"; then
  cat "${dump_log}" >&2
  echo "jacoco-dump: with KEPLOY_JAVA_SCAN=off the ENDs did not take JaCoCo's dump (exit ${dump_rc})" >&2
  exit 1
fi
echo "jacoco-dump: with KEPLOY_JAVA_SCAN=off each END took JaCoCo's dump"

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
      -jar plain-app.jar "plain-jar" derived attached
)

# keploy-sdk.jar alone: it starts the JaCoCo agent it carries, with includes=
# the app's classes, so the command attaches no jacocoagent.jar. Its log
# must say it did, or a JaCoCo found elsewhere could pass these cases.
java_with_sdk_only() {
  local mode="${1:?mode required}"
  shift
  java -javaagent:"${agent_jar}" -Dsmoke.sdkJar="${agent_jar}" "$@" 2>"${work_dir}/${mode}.err" \
    || { cat "${work_dir}/${mode}.err" >&2; exit 1; }
  if ! grep -q "started JaCoCo .* on the app's .* classes alone (includes=" "${work_dir}/${mode}.err"; then
    cat "${work_dir}/${mode}.err" >&2
    echo "${mode}: keploy-sdk.jar did not start its JaCoCo agent on the app's classes" >&2
    exit 1
  fi
}

(
  cd "${work_dir}/maven"
  run_smoke "sdk-only-maven-classes" \
    java_with_sdk_only "sdk-only-maven-classes" \
      -cp "target/classes:${agent_jar}" \
      smoke.SmokeHarness "sdk-only-maven-classes" derived embedded
)

(
  cd "${work_dir}/jar"
  run_smoke "sdk-only-plain-jar" \
    java_with_sdk_only "sdk-only-plain-jar" \
      -jar plain-app.jar "sdk-only-plain-jar" derived embedded
)

# The app's own ASM, Gson and JaCoCo core, in versions other than the ones
# keploy-sdk.jar carries. keploy-sdk.jar carries its copies renamed under
# io/keploy/shaded, so the app gets its own and keploy-sdk its own, whichever
# comes first: the app's on the class path before keploy-sdk.jar (Jib's
# launch), or the app's in a loader under the system class loader (Spring
# Boot's BOOT-INF/lib).
for artifact in org.ow2.asm:asm:5.2 com.google.code.gson:gson:2.11.0 org.jacoco:org.jacoco.core:0.7.9; do
  mvn -q -f "${repo_root}/pom.xml" org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy \
    -Dartifact="${artifact}" -DoutputDirectory="${work_dir}/app-libs"
done
app_libs="$(find "${work_dir}/app-libs" -name '*.jar' | sort | paste -sd: -)"

run_smoke "sdk-only-app-libraries-first" \
  java_with_sdk_only "sdk-only-app-libraries-first" \
    -cp "${app_libs}:${work_dir}/classes:${agent_jar}" \
    smoke.SmokeHarness "sdk-only-app-libraries-first" derived embedded

run_smoke "sdk-only-app-libraries-in-own-loader" \
  java_with_sdk_only "sdk-only-app-libraries-in-own-loader" \
    -Dsmoke.ownLibraries="${app_libs}" \
    -cp "${work_dir}/classes:${agent_jar}" \
    smoke.SmokeHarness "sdk-only-app-libraries-in-own-loader" derived embedded

# keploy-sdk.jar's own classes, the libraries it carries among them, are
# never the app's: an override that names keploy-sdk.jar still indexes the
# app's classes alone.
app_class_count="$(find "${work_dir}/classes" -name '*.class' | wc -l | tr -d ' ')"
KEPLOY_JAVA_CLASS_DIRS="${work_dir}/classes:${agent_jar}" \
run_smoke "sdk-only-override-names-keploy-sdk" \
  java_with_sdk_only "sdk-only-override-names-keploy-sdk" \
    -cp "${work_dir}/classes:${agent_jar}" \
    smoke.SmokeHarness "sdk-only-override-names-keploy-sdk" override embedded "${app_class_count}"

# The same pass over the JaCoCo keploy-sdk.jar starts itself, the usual case.
(
  cd "${work_dir}/maven"
  KEPLOY_JAVA_DEDUP_DIAGNOSTICS=1 run_smoke "sdk-only-in-place-scan" \
    java_with_sdk_only "sdk-only-in-place-scan" \
      -Dsmoke.otherWork="${work_dir}/other-work" \
      -cp "target/classes:${agent_jar}" \
      smoke.SmokeHarness "sdk-only-in-place-scan" derived embedded
)
sdk_scan_log="${work_dir}/sdk-only-in-place-scan.err"
if ! grep -q 'diagnostics: START test-set-0/sdk-only-in-place-scan took .* scan=' "${sdk_scan_log}" \
  || ! grep -q 'diagnostics: END test-set-0/sdk-only-in-place-scan took .* scan=' "${sdk_scan_log}" \
  || ! grep -q 'diagnostics: END test-set-0/sdk-only-in-place-scan-another-copy took .* scan=' "${sdk_scan_log}" \
  || grep -q 'cannot be read in place\|the probe scan failed' "${sdk_scan_log}"; then
  cat "${sdk_scan_log}" >&2
  echo "sdk-only-in-place-scan: the agent did not read its own JaCoCo's probes in place" >&2
  exit 1
fi
echo "sdk-only-in-place-scan: START and END read the probes of the JaCoCo keploy-sdk.jar started in place"

mkdir -p "${work_dir}/boot-root/BOOT-INF/classes" "${work_dir}/war-root/WEB-INF/classes"
cp -R "${work_dir}/classes/." "${work_dir}/boot-root/BOOT-INF/classes/"
cp -R "${work_dir}/classes/." "${work_dir}/war-root/WEB-INF/classes/"
jar cf "${work_dir}/spring-boot-layout.jar" -C "${work_dir}/boot-root" .
jar cf "${work_dir}/servlet-layout.war" -C "${work_dir}/war-root" .

KEPLOY_JAVA_CLASS_DIRS="${work_dir}/spring-boot-layout.jar" \
run_smoke "spring-boot-layout" \
  java_with_agents "spring-boot-layout" \
    -cp "${work_dir}/classes:${agent_jar}" \
    smoke.SmokeHarness "spring-boot-layout" override attached

KEPLOY_JAVA_CLASS_DIRS="${work_dir}/servlet-layout.war" \
run_smoke "servlet-war-layout" \
  java_with_agents "servlet-war-layout" \
    -cp "${work_dir}/classes:${agent_jar}" \
    smoke.SmokeHarness "servlet-war-layout" override attached

# The agent binds the control socket in premain, so junixsocket's native
# library loads there, before main runs. A JVM that cannot load it must still
# run the app, without dedup: whatever the bind throws, premain must not fail
# the app's start. junixsocket's override properties make the library
# unloadable; the agent's SEVERE line shows they did, so this case cannot pass
# with the library loaded.
echo "Running Java agent smoke: native-library-unloadable"
rm -f /tmp/coverage_control.sock /tmp/coverage_data.sock /tmp/keploy-sdk-smoke-native-library-unloadable.exec
unloadable_log="${work_dir}/native-library-unloadable.log"
unloadable_rc=0
java_with_agents "native-library-unloadable" \
  -Dorg.newsclub.net.unix.library.override=/nonexistent/libjunixsocket.so \
  -Dorg.newsclub.net.unix.library.override.force=true \
  -cp "${work_dir}/classes:${agent_jar}" \
  smoke.PlainApp >"${unloadable_log}" 2>&1 || unloadable_rc=$?
if [[ "${unloadable_rc}" -ne 0 ]] || ! grep -qx 'plain-app: main ran' "${unloadable_log}"; then
  cat "${unloadable_log}" >&2
  echo "native-library-unloadable: the app did not run (exit ${unloadable_rc}) with junixsocket's native library unloadable" >&2
  exit 1
fi
if ! grep -q 'Java dedup control socket server is unavailable' "${unloadable_log}"; then
  cat "${unloadable_log}" >&2
  echo "native-library-unloadable: the agent did not say the control socket is unavailable: the library loaded, so this case tested nothing" >&2
  exit 1
fi
echo "native-library-unloadable: main ran, and the agent ran it without dedup"
