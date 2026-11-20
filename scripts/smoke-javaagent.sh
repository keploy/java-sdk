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

# keploy-sdk.jar carries JaCoCo (EPL-2.0), ASM, Gson and junixsocket: it must
# hold keploy-sdk's own license, JaCoCo's EPL-2.0 text and about.html, and the
# notices that name each and say where JaCoCo's source is. A change to the
# shade plugin's filters or to the jar's resources that drops one fails here.
licenses_dir="${work_dir}/licenses"
jacoco_maven=META-INF/maven/org.jacoco
license_entries=(META-INF/LICENSE META-INF/LICENSE-EPL-2.0.txt META-INF/THIRD-PARTY-NOTICES.txt about.html
  "${jacoco_maven}/org.jacoco.core/pom.properties" "${jacoco_maven}/org.jacoco.agent/pom.properties")
mkdir -p "${licenses_dir}"
(cd "${licenses_dir}" && jar xf "${agent_jar}" "${license_entries[@]}")
for entry in "${license_entries[@]}"; do
  if [[ ! -s "${licenses_dir}/${entry}" ]]; then
    echo "licenses: ${agent_jar} holds no ${entry}" >&2
    exit 1
  fi
done
if ! cmp -s "${repo_root}/LICENSE" "${licenses_dir}/META-INF/LICENSE"; then
  echo "licenses: the jar's META-INF/LICENSE is not the repo's LICENSE" >&2
  exit 1
fi
for entry in META-INF/LICENSE-EPL-2.0.txt META-INF/THIRD-PARTY-NOTICES.txt; do
  if ! cmp -s "${module_dir}/src/main/resources/${entry}" "${licenses_dir}/${entry}"; then
    echo "licenses: the jar's ${entry} is not keploy-sdk/src/main/resources/${entry}" >&2
    exit 1
  fi
done
# about.html at the jar's root is org.jacoco.core's, as the notices say, and
# org.jacoco.agent's, which the shade filter leaves out, is the same file as
# the about.html inside jacocoagent.jar.
for artifact in org.jacoco.core org.jacoco.agent; do
  version="$(sed -n 's/^version=//p' "${licenses_dir}/${jacoco_maven}/${artifact}/pom.properties" | tr -d '\r')"
  mvn -q -f "${repo_root}/pom.xml" org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy \
    -Dartifact="org.jacoco:${artifact}:${version}" -DoutputDirectory="${licenses_dir}"
  mkdir -p "${licenses_dir}/${artifact}"
  (cd "${licenses_dir}/${artifact}" && jar xf "../${artifact}-${version}.jar" about.html)
done
if ! cmp -s "${licenses_dir}/org.jacoco.core/about.html" "${licenses_dir}/about.html"; then
  echo "licenses: the jar's about.html is not org.jacoco.core's" >&2
  exit 1
fi
mkdir -p "${licenses_dir}/jacocoagent"
(cd "${licenses_dir}/jacocoagent" && jar xf "${agent_jar}" jacocoagent.jar && jar xf jacocoagent.jar about.html)
if ! cmp -s "${licenses_dir}/org.jacoco.agent/about.html" "${licenses_dir}/jacocoagent/about.html"; then
  echo "licenses: org.jacoco.agent's about.html is not the one inside the jar's jacocoagent.jar" >&2
  exit 1
fi
# The jar holds keploy-sdk's own packages and the parts the notices name, and
# nothing else: a dependency or a package that lands in the jar without its
# notice fails here. A third-party part goes in THIRD-PARTY-NOTICES.txt first,
# then in want_parts; a new package of keploy-sdk's own, in want_parts.
jar_parts="$(jar tf "${agent_jar}" | grep -v '/$' | awk -F/ '
  $1 == "io" && $2 == "keploy" && $3 == "shaded" { print $1 "/" $2 "/" $3 "/" $4 "/" $5 "/" $6; next }
  $1 == "org" && $2 == "jacoco" && $3 == "agent" { print; next }
  $1 == "META-INF" && $2 == "versions" && NF > 6 { print $1 "/" $2 "/*/" $4 "/" $5 "/" $6; next }
  $1 == "META-INF" && ($2 == "maven" || $2 == "native-image") && NF > 3 { print $1 "/" $2 "/" $3; next }
  $1 == "lib" && NF == 4 && $3 == "jni" && $4 ~ /^(lib)?junixsocket-native-/ { print "lib/*/jni/junixsocket-native"; next }
  NF <= 3 { print; next }
  { print $1 "/" $2 "/" $3 }' | LC_ALL=C sort -u)"
want_parts="$(LC_ALL=C sort <<'PARTS'
META-INF/LICENSE
META-INF/LICENSE-EPL-2.0.txt
META-INF/MANIFEST.MF
META-INF/THIRD-PARTY-NOTICES.txt
META-INF/maven/com.google.code.gson
META-INF/maven/com.kohlschutter.junixsocket
META-INF/maven/io.keploy
META-INF/maven/org.jacoco
META-INF/native-image/com.kohlschutter.junixsocket
META-INF/versions/*/org/newsclub/lib
META-INF/versions/*/org/newsclub/net
about.html
io/keploy/dedup
io/keploy/servlet
io/keploy/shaded/com/google/gson
io/keploy/shaded/org/jacoco/core
io/keploy/shaded/org/objectweb/asm
jacocoagent.jar
lib/*/jni/junixsocket-native
org/jacoco/agent/AgentJar.class
org/jacoco/agent/package-info.class
org/newsclub/lib
org/newsclub/net
PARTS
)"
if [[ "${jar_parts}" != "${want_parts}" ]]; then
  echo "licenses: ${agent_jar} does not hold exactly the parts THIRD-PARTY-NOTICES.txt names (< the jar, > want_parts):" >&2
  diff <(printf '%s\n' "${jar_parts}") <(printf '%s\n' "${want_parts}") >&2 || true
  exit 1
fi
echo "licenses: keploy-sdk.jar holds its license, JaCoCo's EPL-2.0 text and about.html, and the notices of every part it holds"

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

# keploy-sdk.jar as built, on a real Tomcat, with JaCoCo's own agent attached
# after it and the index holding Tomcat's classes (as an uber jar's does).
# The jar's manifest lets the SDK's transformer retransform, so the JVM runs
# JaCoCo's before it, whatever the -javaagent order: JaCoCo ids Tomcat's own
# CoyoteAdapter, as the index read it, so the record measures it and names
# no class in classMismatch, and the SDK still counts Tomcat's requests: the
# servlet's code after the response (Tail) runs while the END waits for the
# request, and is in the record. The unit rows attach agent jars they write
# themselves; this case reads the manifest the release ships.
# The Tomcat keploy-sdk/pom.xml tests with (ServerTailTest's).
tomcat_version="$(sed -n '/<artifactId>tomcat-embed-core<\/artifactId>/{n;s/.*<version>\([^<]*\)<\/version>.*/\1/p;}' \
  "${repo_root}/keploy-sdk/pom.xml")"
if [[ ! "${tomcat_version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "tomcat-jacoco-after: keploy-sdk/pom.xml names no tomcat-embed-core version on the line after its artifactId (read '${tomcat_version}')" >&2
  exit 1
fi
for artifact in "org.apache.tomcat.embed:tomcat-embed-core:${tomcat_version}" \
  "org.apache.tomcat:tomcat-annotations-api:${tomcat_version}"; do
  mvn -q -f "${repo_root}/pom.xml" org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy \
    -Dartifact="${artifact}" -DoutputDirectory="${work_dir}/tomcat-libs"
done
tomcat_core="${work_dir}/tomcat-libs/tomcat-embed-core-${tomcat_version}.jar"
tomcat_libs="$(find "${work_dir}/tomcat-libs" -name '*.jar' | sort | paste -sd: -)"

mkdir -p "${work_dir}/tomcat-src/smoke" "${work_dir}/tomcat-classes"
cat > "${work_dir}/tomcat-src/smoke/TomcatHarness.java" <<'JAVA'
package smoke;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public final class TomcatHarness {
    private static final File CONTROL_SOCKET = new File("/tmp/coverage_control.sock");
    private static final File DATA_SOCKET = new File("/tmp/coverage_data.sock");
    private static final String ADAPTER = "org/apache/catalina/connector/CoyoteAdapter";

    // Hello answers, then runs Tail once the END waits for the request
    // (a thread is in RequestGate.await), or after 1500 ms when none does:
    // then the END did not wait, and Tail is not in the record.
    public static final class Hello extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setContentLength(5);
            response.getWriter().write("hello");
            response.flushBuffer();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500L);
            try {
                while (!endWaits() && System.nanoTime() < deadline) {
                    Thread.sleep(10L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Tail.run();
        }

        private static boolean endWaits() {
            for (StackTraceElement[] stack : Thread.getAllStackTraces().values()) {
                for (StackTraceElement frame : stack) {
                    if ("io.keploy.dedup.RequestGate".equals(frame.getClassName())
                            && "await".equals(frame.getMethodName())) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    static final class Tail {
        static volatile int runs;

        static void run() {
            runs++;
        }
    }

    // main ends the JVM with 1 when a check fails: Tomcat's utility threads
    // are not daemons, so a check that threw with Tomcat still running would
    // leave the JVM up, and the case would hang instead of failing.
    public static void main(String[] args) {
        try {
            run(args[0], new File(args[1]));
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String mode, File base) throws Exception {
        if (!base.mkdirs()) {
            throw new IllegalStateException("failed to create " + base);
        }
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(base.getPath());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        Context context = tomcat.addContext("", base.getPath());
        Tomcat.addServlet(context, "hello", new Hello());
        context.addServletMappingDecoded("/hello", "hello");
        tomcat.start();
        int port = tomcat.getConnector().getLocalPort();

        if (DATA_SOCKET.exists() && !DATA_SOCKET.delete()) {
            throw new IllegalStateException("failed to delete " + DATA_SOCKET);
        }
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
        long deadline = System.currentTimeMillis() + 10000L;
        while (!DATA_SOCKET.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }

        String id = "test-set-0/" + mode;
        command("START " + id);
        String body = get(port);
        command("END " + id);
        String report = next(records, mode);
        // The JaCoCo measuring is the one attached after keploy-sdk.jar (the
        // SDK started none of its own), and the index read Tomcat's jar.
        if (!report.startsWith("{\"kind\":\"index\",")
                || !report.contains("\"source\":\"override\"")
                || !report.contains("\"jacoco\":\"attached\"")
                || !report.matches(".*\"roots\":\\[[^\\]]*tomcat-embed-core-[^\\]]*\\].*")) {
            throw new IllegalStateException("the first record on the data socket was no INDEX report with source"
                    + " override, jacoco attached and Tomcat's jar among its roots in " + mode + ": " + report);
        }
        String json = next(records, mode);
        if (!"hello".equals(body)
                || !json.contains("\"id\":\"" + id + "\"")
                || !json.contains("\"" + ADAPTER + "\":[")
                || !json.contains("\"smoke/TomcatHarness$Tail\":[")
                || json.contains("\"classMismatch\"")) {
            throw new IllegalStateException("the record of a request to Tomcat, with JaCoCo attached after"
                    + " keploy-sdk.jar, does not measure " + ADAPTER + " and the servlet's code after the"
                    + " response with no class in classMismatch in " + mode + " (body " + body + "): " + json);
        }
        System.out.println(mode + ": " + json);
        tomcat.stop();
        tomcat.destroy();
    }

    private static String get(int port) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/hello")
                .toURL().openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        try (InputStream input = connection.getInputStream()) {
            return readAll(input);
        } finally {
            connection.disconnect();
        }
    }

    private static void command(String command) throws Exception {
        try (AFUNIXSocket socket = AFUNIXSocket.newInstance()) {
            socket.connect(AFUNIXSocketAddress.of(CONTROL_SOCKET), 3000);
            // The END waits at most KEPLOY_JAVA_END_WAIT_MS (2000) before it ACKs.
            socket.setSoTimeout(15000);
            OutputStream output = socket.getOutputStream();
            output.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            String ack = readAll(socket.getInputStream());
            if (!"ACK proto=2\n".equals(ack)) {
                throw new IllegalStateException("not taken: " + command + ": " + ack);
            }
        }
    }

    private static String next(BlockingQueue<String> records, String mode) throws InterruptedException {
        String record = records.poll(10, TimeUnit.SECONDS);
        if (record == null) {
            throw new IllegalStateException("timed out waiting for a record on the data socket in " + mode);
        }
        return record;
    }

    private static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
JAVA
javac -cp "${tomcat_libs}:${agent_jar}" -d "${work_dir}/tomcat-classes" \
  "${work_dir}/tomcat-src/smoke/TomcatHarness.java"

tomcat_log="${work_dir}/tomcat-jacoco-after.log"
tomcat_rc=0
KEPLOY_JAVA_DEDUP_DIAGNOSTICS=1 KEPLOY_JAVA_END_WAIT_MS=2000 \
KEPLOY_JAVA_CLASS_DIRS="${work_dir}/tomcat-classes:${tomcat_core}" \
run_smoke "tomcat-jacoco-after" \
  timeout -k 10 180 java \
    -javaagent:"${agent_jar}" \
    -javaagent:"${jacoco_jar}=includes=smoke.*:org.apache.catalina.connector.CoyoteAdapter,output=none" \
    -cp "${work_dir}/tomcat-classes:${tomcat_libs}:${agent_jar}" \
    smoke.TomcatHarness "tomcat-jacoco-after" "${work_dir}/tomcat-base" 2>"${tomcat_log}" || tomcat_rc=$?
if [[ "${tomcat_rc}" -eq 124 || "${tomcat_rc}" -eq 137 ]]; then
  cat "${tomcat_log}" >&2
  echo "tomcat-jacoco-after: the Tomcat harness was killed or did not end within 180 s (exit ${tomcat_rc}; 137 can also be the OOM killer)" >&2
  exit 1
fi
if [[ "${tomcat_rc}" -ne 0 ]] \
  || ! grep -q "Keploy dedup counts Tomcat's requests (org.apache.catalina.connector.CoyoteAdapter)" "${tomcat_log}" \
  || ! grep -q 'diagnostics: END test-set-0/tomcat-jacoco-after took .* requests=' "${tomcat_log}" \
  || grep -q 'Can-Retransform-Classes' "${tomcat_log}"; then
  cat "${tomcat_log}" >&2
  echo "tomcat-jacoco-after: JaCoCo attached after keploy-sdk.jar did not measure Tomcat's own CoyoteAdapter, or the requests were not counted (exit ${tomcat_rc})" >&2
  exit 1
fi
echo "tomcat-jacoco-after: JaCoCo attached after keploy-sdk.jar measured Tomcat's own CoyoteAdapter, and the requests were counted"

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
