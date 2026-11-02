# keploy-junit5

A JUnit 5 (Jupiter) extension that joins a test suite to keploy's Universal
Mocker. `keploy mock record` keeps the calls each test's code makes to its
dependencies; `keploy mock replay` answers them from the recording, so the
suite runs with every dependency stopped. keploy judges each test: a call with
no recorded answer, or a recorded call the test no longer makes, fails that
test with keploy's text.

The extension only tells keploy where each test begins and ends; keploy keeps
every rule. It speaks the same wire as go-sdk's `keploytest`,
`@keploy/playwright`, `@keploy/jest` and `keploy-pytest`.

## Use

JUnit 5.10 or later (JUnit 6 included), Java 8 or later. The package is not
published yet: install it from this folder with `mvn install`, then add it to
the test scope:

```xml
<dependency>
  <groupId>io.keploy</groupId>
  <artifactId>keploy-junit5</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

Let JUnit load it for the whole run, in `src/test/resources/junit-platform.properties`:

```properties
junit.jupiter.extensions.autodetection.enabled=true
```

(Gradle: `test { useJUnitPlatform(); systemProperty 'junit.jupiter.extensions.autodetection.enabled', 'true' }`.)

keploy needs the project's `keploy.yml` to name the recording and CI's test
command; without `mock.command`, keploy refuses the run with
`no keploy project here: set mock.command ...`:

```yaml
# keploy.yml
mock:
  name: shop          # the recording: keploy/shop/
  command: mvn test   # CI's test command
```

Then run the suite under keploy with `KEPLOY_PROVEN=1`, in one test JVM.
`--local` keeps the recording on disk, in `keploy/<name>/`:

```sh
KEPLOY_PROVEN=1 keploy mock record --local -c "mvn test"
KEPLOY_PROVEN=1 keploy mock replay --local -c "mvn test"
```

Without keploy (`KEPLOY_MOCK_AGENT` unset or `off`) the extension does
nothing, and the suite runs as plain JUnit.

## What it does under keploy

- The test JVM claims keploy's run once, before the first test class's
  `@BeforeAll`, naming keploy's project folder (`KEPLOY_MOCK_PROJECT`).
- Each test begins after its class's `@BeforeAll` methods, before every other
  extension's BeforeEach callback and its own `@BeforeEach` methods. It ends
  after its `@AfterEach` methods and every other extension's AfterEach
  callback, with its outcome: passed, failed, or skipped (a failed
  assumption). The calls `@BeforeAll` and `@AfterAll` methods make belong to
  the test JVM, outside every test.
- A test keploy set aside is aborted (JUnit's skip) with keploy's reason, and
  none of its `@BeforeEach`, test or `@AfterEach` methods runs (other
  extensions' AfterEach callbacks do, as JUnit runs them after any BeforeEach
  callback stops a test). A line on stderr names it.
- A test keploy failed at its end fails with keploy's text; when it had failed
  already, keploy's text is added to its failure.
- Its ID is `<class>::<method>`: the class's binary name
  (`com.shop.OrdersTest$Refunds` for a `@Nested` class) and JUnit's name of
  the method, with a parameterized or repeated test's invocation index:
  `com.shop.OrdersTest::price(java.lang.String)[2]`. Each invocation of a
  `@ParameterizedClass` (JUnit 5.13 and later) runs each of the class's tests
  as a test of its own, with the class's invocation index before the method:
  `com.shop.OrdersTest::[2]price(java.lang.String)`. A test run through a
  JUnit Platform `@Suite` has the same ID as when it runs directly.

## Limits

Each of these fails loudly under keploy, so no Jupiter test runs unjudged:

- One test JVM at a time: keploy refuses a second claim. Keep Surefire's
  `forkCount` at 1 (its default), and Gradle's `maxParallelForks` at 1.
- JUnit's parallel execution (`junit.jupiter.execution.parallel.enabled=true`)
  fails every test class under keploy.
- Registering the extension only with `@ExtendWith`, without autodetection,
  fails every test class under keploy: some classes could run without it,
  and it must wrap every other extension.
- A `@TestFactory`'s dynamic tests run inside one BeforeEach/AfterEach pair,
  so keploy cannot judge them one by one: the factory fails under keploy.
  Write them as `@ParameterizedTest`.
- A test begun a second time in one JVM fails (a rerun, such as Surefire's
  `rerunFailingTestsCount`, or a second `@Suite` that selects the same test
  class): keploy names a test by its class and name.
- A JVM cannot change its own environment, so it does not export
  `KEPLOY_TEST_PID` to the processes its tests start; one of them that runs
  tests under a keploy SDK claims too, and keploy refuses it.

Only JUnit Jupiter tests are judged. The tests of every other JUnit Platform
engine run unjudged, as without keploy: JUnit 4 tests on the Vintage engine,
and Spock, Kotest, jqwik and Cucumber tests. This extension is a Jupiter
extension, and no other engine calls it; their calls are the test JVM's.
