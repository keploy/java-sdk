#!/usr/bin/env bash
# The e2e lane of keploy-junit5: this commit's extension, installed with
# Maven into a Maven repository of the lane's own, used by a small JUnit 5
# suite (e2e/shop) run with Maven under a real keploy that judges each test.
# The suite's code calls a provider over HTTP; keploy records those calls
# with the provider up, and replays them with the provider stopped.
#
#   KEPLOY_BIN=/path/to/keploy bash keploy-junit5/e2e/lane.sh
#
# KEPLOY_BIN is a keploy build that judges each test under KEPLOY_PROVEN=1
# (keploy's enterprise build; the CI job downloads a pinned release). keploy
# needs root for its eBPF hooks: the lane runs it through sudo unless it is
# root already. java (17 or later: the suite's release, and the provider runs
# as a source file) and mvn must be on PATH.
#
# Checks, each judged by what a user reads (exit codes, Surefire's summary
# and each test's report, the files keploy wrote):
#   1. without keploy the suite passes as plain JUnit, and nothing says
#      keploy;
#   2. keploy mock record keeps each test's calls under the test's name;
#   3. keploy mock replay, with the provider stopped, passes every test;
#   4. a test whose call has no recorded answer fails, with keploy's text
#      added to its failure; the others pass;
#   5. a test that no longer makes its recorded call fails, by keploy, with
#      keploy's text; the others pass;
#   6. a test quarantined in the set is skipped by JUnit with keploy's
#      reason, and the replay passes.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG="$(cd "$HERE/.." && pwd)"
KEPLOY_BIN="${KEPLOY_BIN:?set KEPLOY_BIN to a keploy binary}"
PORT="${SHOP_PORT:-18751}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/junit5-e2e.XXXXXX")"
FAILED=0
PROVIDER_PID=""
export SHOP_PROVIDER="http://127.0.0.1:$PORT"
# The lane's own Maven repository: this commit's extension, and everything
# the suite needs, fetched once (step 0) so that every later run is offline.
export MAVEN_OPTS="${MAVEN_OPTS:+$MAVEN_OPTS }-Dmaven.repo.local=$WORK/m2"

cleanup() {
  [ -z "$PROVIDER_PID" ] || kill "$PROVIDER_PID" 2>/dev/null
  wait 2>/dev/null
}
trap cleanup EXIT

ok() { echo "  ok: $*"; }
fail() { echo "  FAIL: $*"; FAILED=1; }
# show ID: the run's output, for a failing check.
show() {
  local stream
  for stream in out err; do
    echo "  --- $1: std$stream (last 150 lines, keploy's banner left out) ---"
    grep -av '38;5;' "$WORK/logs/$1.$stream" | tail -n 150 | sed 's/^/  | /'
  done
}

if [ "$(id -u)" -eq 0 ]; then AS_ROOT=(env); else AS_ROOT=(sudo -E env "PATH=$PATH" "HOME=$HOME"); fi

# The extension as Maven Central would serve it (its jar, installed), and
# the suite, from a copy of e2e/shop.
mkdir -p "$WORK/logs" "$WORK/pkg"
tar -C "$PKG" --exclude=./e2e --exclude=./target -cf - . | tar -C "$WORK/pkg" -xf -
cp -R "$HERE/shop" "$WORK/shop"
(cd "$WORK/pkg" && mvn -B -ntp -DskipTests install) >"$WORK/logs/install.log" 2>&1 ||
  { cat "$WORK/logs/install.log"; echo "FAIL: could not build and install the extension"; exit 1; }
JAR=$(ls "$WORK"/m2/io/keploy/keploy-junit5/*/keploy-junit5-*.jar)
cd "$WORK/shop" || exit 1
echo "installed $(basename "$JAR") into $WORK/m2; $(mvn -v 2>&1 | head -n 1); $(java -version 2>&1 | head -n 1); the suite is at $WORK/shop"

start_provider() {
  : >"$WORK/logs/provider.log"
  java "$WORK/shop/Provider.java" "$PORT" >"$WORK/logs/provider.log" 2>&1 &
  PROVIDER_PID=$!
  until grep -qx ready "$WORK/logs/provider.log"; do
    kill -0 "$PROVIDER_PID" 2>/dev/null || { cat "$WORK/logs/provider.log"; echo "FAIL: the provider exited before it listened"; exit 1; }
    sleep 0.2
  done
}
stop_provider() {
  kill "$PROVIDER_PID" 2>/dev/null
  wait "$PROVIDER_PID" 2>/dev/null
  PROVIDER_PID=""
}

# run ID CMD...: one run, stdin closed, stdout and stderr kept apart. Sets EC.
run() {
  local id=$1
  shift
  echo
  echo "== $id: $*"
  "$@" </dev/null >"$WORK/logs/$id.out" 2>"$WORK/logs/$id.err"
  EC=$?
  echo "  exit $EC"
  # LANE_VERBOSE=1 shows every run's output, not only a failing check's.
  [ -z "${LANE_VERBOSE:-}" ] || show "$id"
}
# keploy ID ARGS...: one keploy run under the proven switch, as root.
keploy() {
  local id=$1
  shift
  run "$id" "${AS_ROOT[@]}" KEPLOY_PROVEN=1 KEPLOY_INVOKER=ci "SHOP_PROVIDER=$SHOP_PROVIDER" \
    "SHOP_CHANGE=${SHOP_CHANGE:-}" "MAVEN_OPTS=$MAVEN_OPTS" ${JAVA_HOME:+"JAVA_HOME=$JAVA_HOME"} "$KEPLOY_BIN" "$@" --disable-tele
}
# report_of ID TEST: Surefire's report of the failed test TEST in a run
# ("[ERROR] TEST -- Time elapsed: ... <<< FAILURE!"): the lines after it, up
# to Maven's next log line.
report_of() {
  awk -v h="[ERROR] $2 -- " '
    on && /^\[[A-Z]+\] / { on = 0 }
    index($0, h) == 1 && / <<< (FAILURE|ERROR)!$/ { on = 1; next }
    on' "$WORK/logs/$1.out"
}
# surefire_summary ID: Surefire's closing count of a run.
surefire_summary() {
  grep -aE '^\[[A-Z]+\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$WORK/logs/$1.out" |
    tail -n 1 | sed -E 's/^\[[A-Z]+\] //'
}
# has ID TEXT: whether the run's output (Maven's and keploy's) has a line
# that is TEXT.
has() { grep -aqxF -- "$2" "$WORK/logs/$1.out" "$WORK/logs/$1.err"; }

MVN='mvn -B -ntp -o test'
# keploy's IDs: class::name, the class's binary name and JUnit's name of the
# method in it, with a parameterized test's invocation index.
PRICES='shop.OrdersTest$Books::pricesABook()'
RESERVES='shop.OrdersTest$Books::reservesABook()'
CHARGES='shop.PaymentsTest::chargesACard(int)[1]'
# judge ID WHAT: reports a check from the reasons WHY holds against it.
judge() {
  if [ "${#WHY[@]}" -eq 0 ]; then ok "$2"; return; fi
  fail "$2"
  printf '    - %s\n' "${WHY[@]}"
  show "$1"
}

# 0. Fill the lane's Maven repository: one plain run, online, with the
# provider up. Every later run is offline.
start_provider
run warm mvn -B -ntp test
[ "$EC" -eq 0 ] || { show warm; echo "FAIL: the suite does not pass online without keploy"; exit 1; }

# 1. Without keploy: plain JUnit, offline.
run inert $MVN
WHY=()
[ "$EC" -eq 0 ] || WHY+=("exit $EC, want 0")
[ "$(surefire_summary inert)" = "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0" ] || WHY+=("Surefire says '$(surefire_summary inert)'")
! cat "$WORK/logs/inert.out" "$WORK/logs/inert.err" | grep -aqi keploy || WHY+=("a line says keploy")
judge inert "without keploy the suite passes as plain JUnit, and nothing says keploy"

# 2. Record, with the provider up: keploy's own rounds replay it twice more.
keploy record mock record --local -c "$MVN"
stop_provider
WHY=()
[ "$EC" -eq 0 ] || WHY+=("exit $EC, want 0")
has record "keploy: committed keploy/shop and its verdicts.yaml: 3 replayable, 0 quarantined" ||
  WHY+=("keploy did not commit 3 replayable tests")
for t in "$PRICES" "$RESERVES" "$CHARGES"; do
  grep -aqxF -- "    - id: $t" keploy/shop/mappings.yaml 2>/dev/null || WHY+=("keploy/shop/mappings.yaml does not name $t")
done
judge record "keploy mock record keeps each test's calls under its class and JUnit's name of it"

# 3. Replay with the provider stopped.
keploy replay mock replay --local -c "$MVN"
WHY=()
[ "$EC" -eq 0 ] || WHY+=("exit $EC, want 0")
[ "$(surefire_summary replay)" = "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0" ] || WHY+=("Surefire says '$(surefire_summary replay)'")
has replay "keploy: shop: 3 test(s) ran: 3 passed, 0 failed (0 held by keploy), 0 skipped by the test; 0 quarantined (skipped by keploy)" ||
  WHY+=("keploy did not judge 3 tests passed")
has replay "VERDICT  passed" || WHY+=("keploy's verdict is not passed")
judge replay "keploy mock replay with the provider stopped passes every test"

# 4. A call the recording has no answer for: keploy answers it with an
# error, so the test fails by itself (an error: its IOException), and keploy's
# text is added to that failure; only that test fails.
SHOP_CHANGE=miss keploy replay-miss mock replay --local -c "$MVN"
WHY=()
[ "$EC" -eq 1 ] || WHY+=("exit $EC, want 1")
[ "$(surefire_summary replay-miss)" = "Tests run: 3, Failures: 0, Errors: 1, Skipped: 0" ] || WHY+=("Surefire says '$(surefire_summary replay-miss)'")
# keploy 3.8.106 names this client's destination "[127.0.0.1]:PORT (live
# 127.0.0.1:PORT)": the check takes any form that names the address and port.
report_of replay-miss 'shop.OrdersTest$Books.pricesABook' |
  grep -aqE "^[[:space:]]+Suppressed: io\.keploy\.junit5\.KeployException: keploy: GET /price/pen to .*127\.0\.0\.1.*:$PORT.* had no recorded answer \(path differs\)" ||
  WHY+=("Surefire's report of pricesABook does not carry keploy's miss, added to its failure")
has replay-miss "FAILED_BY_KEPLOY  $PRICES (failed): keploy/unmocked_call" || WHY+=("keploy does not hold the miss against pricesABook")
judge replay-miss "a call with no recorded answer fails its own test with keploy's text; the others pass"

# 5. A recorded call the test no longer makes: keploy fails it at its end.
SHOP_CHANGE=unconsumed keploy replay-unconsumed mock replay --local -c "$MVN"
WHY=()
[ "$EC" -eq 1 ] || WHY+=("exit $EC, want 1")
[ "$(surefire_summary replay-unconsumed)" = "Tests run: 3, Failures: 0, Errors: 1, Skipped: 0" ] || WHY+=("Surefire says '$(surefire_summary replay-unconsumed)'")
# The mock's number follows the order the recording made its calls in, and
# Surefire runs the classes in the order the file system lists them.
report_of replay-unconsumed 'shop.OrdersTest$Books.reservesABook' |
  grep -aqE "^keploy: recorded call POST /reserve \(mock-[0-9]+\) was never made\." ||
  WHY+=("Surefire's report of reservesABook does not carry keploy's unmade call")
has replay-unconsumed "FAILED_BY_KEPLOY  $RESERVES (failed): keploy/unconsumed_mock" || WHY+=("keploy does not hold the unmade call against reservesABook")
judge replay-unconsumed "a recorded call the test no longer makes fails that test with keploy's text; the others pass"

# 6. A test quarantined in the set: JUnit skips it, before its own
# callbacks, with keploy's reason; the replay passes.
keploy quarantine mock quarantine "$CHARGES" --reason 'e2e: set aside by the lane' --local --name shop
WHY=()
[ "$EC" -eq 0 ] || WHY+=("keploy mock quarantine exited $EC")
keploy replay-quarantined mock replay --local -c "$MVN"
[ "$EC" -eq 0 ] || WHY+=("replay exit $EC, want 0")
[ "$(surefire_summary replay-quarantined)" = "Tests run: 3, Failures: 0, Errors: 0, Skipped: 1" ] || WHY+=("Surefire says '$(surefire_summary replay-quarantined)'")
# Surefire's XML report, as CI's test reports read it: the abort's message is
# the skip's text.
grep -aA1 -F '<testcase name="chargesACard(int)[1]" classname="shop.PaymentsTest"' target/surefire-reports/TEST-shop.PaymentsTest.xml 2>/dev/null |
  grep -aqF '<skipped type="org.opentest4j.TestAbortedException"><![CDATA[org.opentest4j.TestAbortedException: keploy: quarantined (manual/e2e: set aside by the lane)' ||
  WHY+=("Surefire's report does not show keploy's reason for skipping chargesACard")
grep -aqE "^keploy-junit5: skipped shop\.PaymentsTest::chargesACard\(int\)\[1\] \(keploy: .*e2e: set aside by the lane" \
  "$WORK/logs/replay-quarantined.out" "$WORK/logs/replay-quarantined.err" ||
  WHY+=("keploy-junit5 does not say it skipped chargesACard")
grep -aqF "1 quarantined (skipped by keploy)" "$WORK/logs/replay-quarantined.err" || WHY+=("keploy does not count 1 quarantined test")
[ "${#WHY[@]}" -eq 0 ] || { echo "  --- Surefire's report of shop.PaymentsTest ---"; sed 's/^/  | /' target/surefire-reports/TEST-shop.PaymentsTest.xml 2>&1 | grep -av '<property '; ls target/surefire-reports 2>&1 | sed 's/^/  | /'; }
judge replay-quarantined "a quarantined test is skipped by JUnit with keploy's reason; the replay passes"

echo
if [ "$FAILED" -ne 0 ]; then echo "e2e: FAILED (logs in $WORK/logs)"; exit 1; fi
echo "e2e: every check passed"
