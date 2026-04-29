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
        if (json == null
                || !json.contains("\"id\":\"test-set-0/" + mode + "\"")
                || !json.contains("Work.java")) {
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
