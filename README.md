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

### 1. Add the SDK

Add `keploy-sdk` to your application:

```xml
<dependency>
  <groupId>io.keploy</groupId>
  <artifactId>keploy-sdk</artifactId>
  <version>N.N.N</version>
</dependency>
```

### 2. Activate the Agent

For Spring Boot, import the middleware in your application:

```java
import io.keploy.servlet.KeployMiddleware;
import org.springframework.context.annotation.Import;

@Import(KeployMiddleware.class)
public class Application {
}
```

For servlet-based applications, register the filter early in `web.xml`:

```xml
<filter>
  <filter-name>middleware</filter-name>
  <filter-class>io.keploy.servlet.KeployMiddleware</filter-class>
</filter>
<filter-mapping>
  <filter-name>middleware</filter-name>
  <url-pattern>/*</url-pattern>
</filter-mapping>
```

The middleware starts the Java dedup control server automatically.

For Jakarta Servlet stacks, non-servlet frameworks, or any application where the `javax.servlet` filter is not available, start the agent directly during application startup:

```java
import io.keploy.dedup.KeployDedupAgent;

KeployDedupAgent.start();
```

### 3. Run the App with the JaCoCo Java Agent

The dedup agent reads coverage in-process via JaCoCo's runtime API (`org.jacoco.agent.rt.RT.getAgent()`), so all you need is to attach the JaCoCo Java agent — no TCP server flags, no port choices:

```bash
java -javaagent:/path/to/jacocoagent.jar -jar your-app.jar
```

If the in-process API is unavailable (for example because the JaCoCo agent is loaded into an isolated classloader), the SDK transparently falls back to JaCoCo's TCP server mode. To use the fallback explicitly, start JaCoCo in `tcpserver` mode and set `KEPLOY_JACOCO_HOST` / `KEPLOY_JACOCO_PORT`:

```bash
java -javaagent:/path/to/jacocoagent.jar=address=127.0.0.1,port=36320,output=tcpserver \
  -jar your-app.jar
```

### 4. Replay with Keploy Enterprise

Run replay with dynamic dedup enabled:

```bash
keploy test -c "java -javaagent:/path/to/jacocoagent.jar -jar your-app.jar" \
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

Java dedup works in native, Docker, and restricted Docker environments as long as the following conditions are met:

- host `/tmp` is bind-mounted into the container as `/tmp`
- `/tmp` remains writable so the Unix sockets can be created
- if the SDK falls back to TCP, the JaCoCo TCP port is reachable from the Java process

The `/tmp` bind mount is required because Keploy Enterprise and the Java SDK communicate over these Unix sockets:

- `/tmp/coverage_control.sock`
- `/tmp/coverage_data.sock`

Without a shared `/tmp`, dedup will not work inside containers because Enterprise and the Java process will be writing to different socket paths.

## Configuration

- `KEPLOY_JACOCO_HOST`: JaCoCo TCP host used when the in-process runtime API is unavailable. Default: `127.0.0.1`
- `KEPLOY_JACOCO_PORT`: JaCoCo TCP port used when the in-process runtime API is unavailable. Default: `36320`
- `KEPLOY_JAVA_CLASS_DIRS`: optional comma-separated class or jar locations to analyze for executed lines
- `KEPLOY_JAVA_CLASSPATH_FALLBACK`: scans classpath directories and jars if no class roots are found. Default: `false`
- `KEPLOY_JAVA_DEDUP_DISABLED`: disables the Java dedup agent when set to `true`, `1`, or `yes`

## Sample

For a working reference, see the Java dedup sample in `keploy/samples-java`:

- `samples-java/java-dedup`

That sample is used in CI to validate Java dynamic dedup for JDK 8, 17, and 21 across native, Docker, and restricted Docker runs.
