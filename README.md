# Keploy Java Coverage Agent

This repository contains the Java dynamic dedup coverage agent for Keploy Enterprise.

It collects per-testcase Java coverage during Keploy replay and sends that coverage back to Enterprise so duplicate testcases can be identified and removed.

The repository contains only the dedup-focused `keploy-sdk` module.

Supported runtimes in CI today are Java 8, 17, and 21.

Dynamic dedup works on applications compiled up to **Java 26**. See
[Supported Java versions](#supported-java-versions) for where that ceiling comes
from and how to raise it.

## How It Works

Keploy Enterprise drives dynamic dedup per testcase.

1. Enterprise sends `START <test-set>/<test-id>` on `/tmp/coverage_control.sock`.
2. The Java agent clears what the application's classes ran since the last testcase. What it clears is background (a health check, a scheduled job): it is never counted in a testcase's coverage.
3. Enterprise replays the testcase.
4. Enterprise sends `END <test-set>/<test-id>` on `/tmp/coverage_control.sock`.
5. The Java agent reads, and clears, the probes of the application's classes that the testcase hit, and sends the executed probe indices per class (`{className -> [probeIdx]}`) as JSON on `/tmp/coverage_data.sock`. Probes capture branch-level coverage (which branch a test took), so they distinguish tests that run the same lines but take different branches.
6. Enterprise writes the result to `dedupData.yaml` and uses it to identify duplicates.

Coverage is collected at per-testcase granularity, not process granularity.

With the JaCoCo agent in the application's JVM (the usual case), steps 2 and 5 are one pass over the probe arrays of the application's classes alone, split across up to four threads (half the cores): a probe is cleared only once it was read as hit, so a hit made while the pass runs is reported by that pass or the next one, never lost. Library classes are neither read nor cleared. If JaCoCo's runtime data cannot be read that way, or the pass fails once, the agent uses JaCoCo's own reset and dump for the rest of the run, as it does when JaCoCo is reached over TCP.

Before the first testcase's coverage, the agent sends Enterprise one INDEX record on `/tmp/coverage_data.sock`: a JSON object starting with `{"kind":"index",` that says how it found the application's own classes (`source`: `override` when the dirs and jars in `KEPLOY_JAVA_CLASS_DIRS` held them, `derived` from the launch or from `target/classes` or `build/classes/java/main` under the working directory, `whole-jar` or `classpath` when it could not tell them from the libraries, `none`), what it found them from in words (`launch`), the dirs and jars it read them from (`roots`, at most 64, and `rootCount`), the package roots it kept (`packages`), how many classes it indexed, the build id of those classes (`jv1-` and 32 hex), which JaCoCo agent measures them (`jacoco`: `embedded`, the one keploy-sdk.jar starts, `attached` by the command, `none`, or `unchecked` when the agent was not started as a `-javaagent`), that embedded agent's `includes=`, and two versions as their jars' manifests name them, or `""` when not known: keploy-sdk.jar's (`sdkVersion`) and that of the JaCoCo agent that measures the classes, embedded or attached (`jacocoVersion`). It carries no class list. It is sent as the agent starts when that path's socket file is there (Enterprise's receiver makes it), and otherwise, or when that send fails, right before the first testcase's coverage; a receiver Enterprise binds anew on that path gets it again before its first record. On the TCP transport used in Kubernetes no INDEX record is sent.

Since 2.2.0 Keploy can also ask for that background, to keep it out of what it compares: it sends the line `BACKGROUND` on `/tmp/coverage_control.sock`. The agent answers `ACK` when it reads JaCoCo's probes in place and a receiver is on `/tmp/coverage_data.sock`, and `IGNORED` otherwise (`KEPLOY_JAVA_SCAN=off`, JaCoCo reached over TCP, the in-place read failed, or no receiver), as an older agent answers a line it does not know. The ask holds for this run of the application and that receiver only: an application started again (native `keploy test` starts it again for each test set) and a receiver Enterprise binds anew on that path (the next docker-compose test set) get no record until Keploy asks again, so Keploy asks after each INDEX record, which comes once per start of the application and once per new receiver. From then on, each `START` that cleared hits made since the previous testcase's `END` sends one BACKGROUND record on `/tmp/coverage_data.sock`, right after that `START` is answered (after the INDEX record, when that receiver does not have it yet): `{"kind":"background","v":1,"build":"<the INDEX record's build id>","after":"<the testcase that ended>","before":"<the testcase that starts>","executedLinesByFile":{className:[probeIdx]}}`, under the key and in the shape of a testcase's record, plus `"lost":N` when the records of the N windows before it could not be made or sent. A window with no hits sends nothing. What ran before the first testcase (the application's start, the warmup) is not background, and neither is what ran after an `END` that was not taken or a `START` whose `END` never came (a testcase's own hits): none of it is sent. A test's own work that goes on after its `END` (an async task it started) lands in the next window, and so is reported as background. A Keploy that never asks gets no record, short of a new receiver that replaces the one that asked within the same instant (the agent tells receivers apart by the socket file's inode and modification time). On the TCP transport used in Kubernetes there is no BACKGROUND.

The agent binds `/tmp/coverage_control.sock` before the application's `main` runs, so the socket is listening before the application can answer a health check. It answers `ACK` to each command it took, and `IGNORED` to an `END` that no `START` for that testcase came before: nothing is published for it, and Enterprise keeps that testcase out of the comparison. On the TCP transport used in Kubernetes the same `END` is answered with `IGNORED <testcase>` and then `ACK`.

## How to Use

### 1. Download the Keploy Java Agent

Download the `keploy-sdk` jar and keep it outside your application dependencies. The jar is a Java agent and should be attached only when you run Keploy dynamic deduplication.

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-dependency-plugin</artifactId>
  <version>3.6.1</version>
  <executions>
    <execution>
      <id>copy-keploy-java-agent</id>
      <phase>package</phase>
      <goals>
        <goal>copy</goal>
      </goals>
      <configuration>
        <artifactItems>
          <artifactItem>
            <groupId>io.keploy</groupId>
            <artifactId>keploy-sdk</artifactId>
            <version>2.0.5</version>
            <outputDirectory>${project.build.directory}</outputDirectory>
            <destFileName>keploy-sdk.jar</destFileName>
          </artifactItem>
        </artifactItems>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Do not add `io.keploy:keploy-sdk` to the app's dependencies, and remove it if it is there: it must not be on the
app's classpath, where an older copy would run in place of the attached `keploy-sdk.jar` and start no JaCoCo, so no
test's coverage would be measured. Application code should not import `io.keploy.*` classes for dynamic deduplication.

### 2. Run the App with the Keploy Java Agent

`keploy-sdk.jar` is the only agent the app needs:

```bash
java -javaagent:/path/to/keploy-sdk.jar -jar your-app.jar
```

It carries the JaCoCo agent (`jacocoagent.jar`, JaCoCo 0.8.15, EPL-2.0; see
`META-INF/THIRD-PARTY-NOTICES.txt` in the jar) and starts it itself, before the
app's `main`, with `includes=` set to the app's own classes, which it finds from
how the JVM was launched (Spring Boot, Jib, shaded and plain jars, class dirs;
`KEPLOY_JAVA_CLASS_DIRS` overrides it). JaCoCo then instruments only those
classes, not every library class the app loads. The startup log says which:

```
INFO: Keploy dedup: started JaCoCo 0.8.15 on the app's <n> classes alone (includes=com.example.shop.*)
```

When the SDK cannot tell the app's classes from its libraries, it says so at
WARNING and starts JaCoCo on every class; set `KEPLOY_JAVA_CLASS_DIRS` to the
app's class dirs and jars to fix that.

If the command attaches a JaCoCo agent of its own (`-javaagent:jacocoagent.jar`,
in any order, or in `JAVA_TOOL_OPTIONS`), the SDK starts none and reads coverage
from that one, through JaCoCo's in-process runtime API
(`org.jacoco.agent.rt.RT.getAgent()`). That JaCoCo instruments every class its
own options take in. If the in-process API is unavailable (for example because
the JaCoCo agent is loaded into an isolated classloader), the SDK falls back to
JaCoCo's TCP server mode. To use the fallback explicitly, start JaCoCo in
`tcpserver` mode and set `KEPLOY_JACOCO_HOST` / `KEPLOY_JACOCO_PORT`:

```bash
java \
  -javaagent:/path/to/keploy-sdk.jar \
  -javaagent:/path/to/jacocoagent.jar=address=127.0.0.1,port=36320,output=tcpserver \
  -jar your-app.jar
```

### 3. Replay with Keploy Enterprise

Run replay with dynamic dedup enabled:

```bash
keploy test -c "java -javaagent:/path/to/keploy-sdk.jar -jar your-app.jar" \
  --dedup \
  --language java
```

When using the TCP fallback, also pass `--pass-through-ports <jacoco-port>` so Keploy does not try to mock the JaCoCo control connection.

After replay, run:

```bash
keploy dedup
```

To remove duplicates:

```bash
keploy dedup --rm
```

## Docker and Restricted Docker

Java dedup works in native, Docker, and restricted Docker environments as long as `/tmp` is shared and writable between Keploy Enterprise and the Java process. In Docker Compose flows, Enterprise can inject that shared `/tmp` mount when it rewrites the Compose file for replay.

Keploy Enterprise and the Java SDK communicate over these Unix sockets:

- `/tmp/coverage_control.sock`
- `/tmp/coverage_data.sock`

Without a shared `/tmp`, dedup will not work inside containers because Enterprise and the Java process will be writing to different socket paths.

## Supported Java versions

Dynamic dedup can only handle bytecode that **both** of its jars can read, so the
supported ceiling is the lower of two limits — not one:

| Component | Role | Reads up to |
| --- | --- | --- |
| `jacocoagent.jar` (carried in `keploy-sdk.jar`; on k8s, the k8s-proxy init image's) | analyzes coverage | Java 26 (JaCoCo 0.8.15) |
| `keploy-sdk.jar` (this repo) | instruments the app, via the ASM shaded in through `org.jacoco:org.jacoco.core` | Java 26 (jacoco.core 0.8.15) |

Both currently sit at **Java 26**, so that is the effective ceiling.

### Why this repo is part of the ceiling

It is easy to assume the JaCoCo agent alone decides this. It does not. The dedup
agent here does its own instrumentation through the ASM that `jacoco.core` brings
in, and ASM refuses class files newer than the release it was built against. So
the `jacoco.core` version in `keploy-sdk/pom.xml` is an **independent limit**, and
for a long time it was the *lower* one: while it sat on 0.8.12 it capped dedup at
Java 22 even though the agent could already read more.

### What happens above the ceiling

Nothing crashes, which is what makes it worth documenting. The app is instrumented,
the replay runs, and every test passes — coverage is simply never produced, so no
duplicates are found. That is indistinguishable from "this app genuinely has no
duplicates" unless something says otherwise, which is why k8s-proxy now detects the
app's real class-file version from the classes this agent uploads and reports the
reason instead of failing silently.

### Raising it

1. Bump `jacoco.version` in `keploy-sdk/pom.xml` (both `org.jacoco.core` and the
   `jacocoagent.jar` the SDK carries), change the JaCoCo and ASM versions named in
   `keploy-sdk/src/main/resources/META-INF/THIRD-PARTY-NOTICES.txt` and in this
   README to match, and cut a release.
2. In k8s-proxy, bump `KEPLOY_SDK_VERSION` (`build/dedup-jars/build.sh`) to that
   release, and set `BundledJaCoCoVersion`, `JaCoCoOfficialMaxJava` and
   `SDKShadedASMMaxJava` in `pkg/platform/dedupinject/jacocompat.go`.

Take the Java number from JaCoCo's
[release notes](https://www.jacoco.org/jacoco/trunk/doc/changes.html) — the
**officially supported** version, not the "experimental support for Java N+1 class
files" one. Each JaCoCo release ships an ASM that *declares* one version beyond
what it accepts by default, so reading the highest declared `Opcodes.V<n>`
over-states real support by one. k8s-proxy's image build checks the constant
against the ASM actually inside the jar and fails the build if it over-promises.

A scheduled k8s-proxy pipeline also fails when a newer JaCoCo is published, so this
ceiling does not quietly fall behind again.

## Configuration

- `KEPLOY_JACOCO_HOST`: JaCoCo TCP host used when the in-process runtime API is unavailable. Default: `127.0.0.1`
- `KEPLOY_JACOCO_PORT`: JaCoCo TCP port used when the in-process runtime API is unavailable. Default: `36320`
- `KEPLOY_JAVA_CLASS_DIRS`: optional comma-separated class, jar, war, ear, or zip locations of the app's own classes. You rarely need it: see "Which classes count as the app's" below
- `KEPLOY_JAVA_CLASSPATH_FALLBACK`: scans the full classpath when neither the app's own classes nor the executable jar give any class. `false` leaves the index empty then, and no test's coverage is measured. Default: `true`
- `KEPLOY_JAVA_DEDUP_DISABLED`: disables the Java dedup agent when set to `true`, `1`, or `yes`
- `KEPLOY_JAVA_DEDUP_DIAGNOSTICS`: when `true`, `1`, or `yes`, logs one line per START and END that says how long the command took and how long each step did: `warmup`, then `scan` (the pass that clears the background) for a START; `scan` (the pass that reads and clears the test's hits), `filter` (the hits made into the test's probes by class), `json` and `send` for an END, plus `classes` on TCP (the check for new app classes, and the class-zip frame when there are some). Where JaCoCo's own reset and dump are used instead, a START has `reset`, and an END has `dump` (JaCoCo's dump), `parse` and `filter` (to the app's classes) in place of `scan` and `filter`; a step that did not run is left out, so an empty dump has only `dump`. A START that cleared background hits also logs how many classes and probes they were. The app's class files are read once: before the app starts, when keploy-sdk.jar starts JaCoCo itself; otherwise on the first START, by the warmup's thread (timed under `warmup`, and, past the 10 seconds that START waits for the warmup, under its `scan`, which waits for the read, or, with JaCoCo's reset and dump, which never wait for it, on the first END whose dump is not empty, under `filter`), or, with the warmup off, under that START's `scan` (or, with JaCoCo's dump, on the first END whose dump is not empty, under `filter`). On the unix socket `took` ends before the answer is written; on TCP it includes the answer. Use it to see where a test's time goes on your app
- `KEPLOY_JAVA_SCAN`: `off` (in any case) turns the one-pass read of the application's probes off: each testcase then takes JaCoCo's own reset and dump, as when JaCoCo is reached over TCP, a `START` no longer sees background hits, and `BACKGROUND` is answered `IGNORED`. Use it if the in-place read misbehaves on your JVM; at 50k application classes JaCoCo's reset and dump took about 20 ms per testcase in our profile, against under 2 ms with the in-place read. Default: on
- `KEPLOY_JAVA_DEDUP_WARMUP_DISABLED`: when `true`, `1`, or `yes`, skips the warmup, which loads and initializes the app's own classes once, on the first START, so that no test is charged with their static initializers. It loads them through the class loader the app runs them with, such as a Spring Boot fat jar's own loader (since 2.2.0; before, a fat jar's classes were not warmed up), so every app class not loaded yet is loaded then, and JaCoCo instruments it: on an app with many classes it never uses, that is more class metadata (metaspace) than the app alone would load. It runs on a thread of its own: the first START waits for it for at most 10 seconds, then goes on, and the warmup stops after the class it is initializing. Its static initializer goes on while the tests run, and each line it runs from then on counts in the test running at the time (between two tests, in none: the next START clears it); classes it did not reach initialize when the app first uses them, as with the warmup off. The log says how long the warmup took, and, when the START stopped waiting, which class it was initializing

## Which classes count as the app's

Dedup fingerprints a test by the app's own code it runs, not by library code
(Spring, Hibernate, Jetty), which runs on nearly every request. The agent finds
the app's classes, in this order:

1. `KEPLOY_JAVA_CLASS_DIRS`, and `target/classes` or `build/classes/java/main`
   under the working dir.
2. From how the JVM was launched. The dir or jar that holds the main class is
   the app's own and counts whole, so generated code outside its package
   (OpenAPI, jOOQ, protobuf) counts too. Other jars give only the classes under
   the main class's package root: its package cut to three parts
   (`org.apache.fineract` for `org.apache.fineract.ServerApplication`), or a
   longer Maven groupId that the package is in.
   - `java -cp ... Main` (Jib's `/app/classes` and `/app/libs`, or any other
     classpath): every class in the classpath's directories and in the jar
     that holds the main class, and the classes under the root in its other
     jars.
   - A Spring Boot jar or war, or the dir it was extracted to: every class in
     `BOOT-INF/classes`, and the classes under the root in `BOOT-INF/lib`'s
     jars. A thin jar counts by its `Start-Class`.
   - A Vert.x app: its `Main-Verticle`, or the verticle its command runs, is
     the main class.
   - Any other jar: all of it, and the classes under the root in the jars its
     manifest's `Class-Path` names. A shaded jar (one that holds other
     projects' `META-INF/maven` metadata) gives only the classes under the root.
     A jar shaded of libraries that carry no such metadata (as Gradle's shadow
     often is) counts whole, library classes too.
   A launch the agent cannot read this way is not guessed at: a main class in
   none of the jars or dirs launched (a launcher stub, as Quarkus runs), a
   known server's, framework's or test runner's launcher (Tomcat, Jetty,
   WildFly, Play, Helidon MP, Karaf, Felix, Equinox, Maven, Gradle), or
   `java -m`. The list is of known launchers: a launcher it does not know is
   taken for the app, so set `KEPLOY_JAVA_CLASS_DIRS` for one.
3. Failing those, the whole executable jar, then the whole classpath
   (`KEPLOY_JAVA_CLASSPATH_FALLBACK`). The agent then logs a WARNING: library
   code makes duplicate tests look different, so dedup keeps more tests than
   it should, and the startup warmup is skipped. When even those give no
   class, the WARNING says no test's coverage is measured. Either way, set
   `KEPLOY_JAVA_CLASS_DIRS`.

The agent logs where its classes came from, and how many, at the first test.

## Sample

For a working reference, see the Java dedup sample in `keploy/samples-java`:

- `samples-java/java-dedup`

That sample is used in CI to validate Java dynamic dedup for JDK 8, 17, and 21 across native, classpath, Docker, distroless, and restricted Docker runs.
The CI matrix is narrower than the supported range: it pins the runtimes the sample is exercised on, not the ceiling — see [Supported Java versions](#supported-java-versions).
