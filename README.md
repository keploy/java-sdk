# Keploy Java Coverage Agent

This repository contains the Java dynamic dedup coverage agent for Keploy Enterprise.

It collects per-testcase Java coverage during Keploy replay and sends that coverage back to Enterprise so duplicate testcases can be identified and removed.

The repository contains only the dedup-focused `keploy-sdk` module.

Supported runtimes in CI today are Java 8, 17, and 21.

## How It Works

Keploy Enterprise drives dynamic dedup per testcase.

1. Enterprise sends `START <test-set>/<test-id>` on `/tmp/coverage_control.sock`.
2. The Java agent resets JaCoCo coverage counters for that testcase.
3. Enterprise replays the testcase.
4. Enterprise sends `END <test-set>/<test-id>` on `/tmp/coverage_control.sock`.
5. The Java agent dumps JaCoCo execution data, resolves executed Java lines, and sends them as JSON on `/tmp/coverage_data.sock`.
6. Enterprise writes the result to `dedupData.yaml` and uses it to identify duplicates.

Coverage is collected at per-testcase granularity, not process granularity.

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

The SDK no longer has to be added to `dependencies`, and application code should not import `io.keploy.*` classes for dynamic deduplication.

### 2. Run the App with the Keploy and JaCoCo Java Agents

The dedup agent reads coverage in-process via JaCoCo's runtime API (`org.jacoco.agent.rt.RT.getAgent()`), so attaching the JaCoCo Java agent is the only runtime requirement in the common cases below:

- Maven/Gradle dev runs where application classes are under `target/classes` or `build/classes/java/main`
- packaged `java -jar` runs where the application classes live inside the executable jar

```bash
java \
  -javaagent:/path/to/keploy-sdk.jar \
  -javaagent:/path/to/jacocoagent.jar \
  -jar your-app.jar
```

If the in-process API is unavailable (for example because the JaCoCo agent is loaded into an isolated classloader), the SDK transparently falls back to JaCoCo's TCP server mode. To use the fallback explicitly, start JaCoCo in `tcpserver` mode and set `KEPLOY_JACOCO_HOST` / `KEPLOY_JACOCO_PORT`:

```bash
java \
  -javaagent:/path/to/keploy-sdk.jar \
  -javaagent:/path/to/jacocoagent.jar=address=127.0.0.1,port=36320,output=tcpserver \
  -jar your-app.jar
```

### 3. Replay with Keploy Enterprise

Run replay with dynamic dedup enabled:

```bash
keploy test -c "java -javaagent:/path/to/keploy-sdk.jar -javaagent:/path/to/jacocoagent.jar -jar your-app.jar" \
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

## Configuration

- `KEPLOY_JACOCO_HOST`: JaCoCo TCP host used when the in-process runtime API is unavailable. Default: `127.0.0.1`
- `KEPLOY_JACOCO_PORT`: JaCoCo TCP port used when the in-process runtime API is unavailable. Default: `36320`
- `KEPLOY_JAVA_CLASS_DIRS`: optional comma-separated class, jar, war, ear, or zip locations to analyze for executed lines when your build output lives outside the standard locations
- `KEPLOY_JAVA_CLASSPATH_FALLBACK`: scans the full classpath if standard class roots and the executable archive do not provide application classes. Default: `true`
- `KEPLOY_JAVA_DEDUP_DISABLED`: disables the Java dedup agent when set to `true`, `1`, or `yes`

## Sample

For a working reference, see the Java dedup sample in `keploy/samples-java`:

- `samples-java/java-dedup`

That sample is used in CI to validate Java dynamic dedup for JDK 8, 17, and 21 across native, classpath, Docker, distroless, and restricted Docker runs.
