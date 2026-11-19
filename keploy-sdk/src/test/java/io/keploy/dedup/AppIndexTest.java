package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jacoco.core.internal.data.CRC64;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

/**
 * The index the agent fingerprints: the app's own classes when they are
 * named or derived from the launch, warmed up at the first START; else the
 * whole classpath, said at WARNING and never warmed up, as a library's static
 * initializer can do anything and a large app's took 54 s.
 */
public class AppIndexTest {

    private static final String[] PROPERTIES = {"java.class.path", "sun.java.command", "user.dir",
            "keploy.java.class.dirs", "keploy.java.dedup.warmup.disabled", "keploy.jacoco.port",
            "keploy.java.classpath.fallback"};

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Map<String, String> saved = new HashMap<>();
    private String savedControlPath;
    private String savedDataPath;
    private FakeJacoco jacoco;
    private final List<String> logged = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record.getLevel() + " " + record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @Before
    public void setUp() throws IOException {
        for (String name : PROPERTIES) {
            saved.put(name, System.getProperty(name));
        }
        System.clearProperty("keploy.java.class.dirs");
        System.clearProperty("keploy.java.dedup.warmup.disabled");
        System.clearProperty("keploy.java.classpath.fallback");
        // No target/classes or build/classes under the working dir.
        System.setProperty("user.dir", tmp.newFolder("cwd").getPath());
        savedControlPath = KeployDedupAgent.controlSocketPath;
        savedDataPath = KeployDedupAgent.dataSocketPath;
        File sockets = tmp.newFolder("sockets");
        KeployDedupAgent.controlSocketPath = new File(sockets, "control.sock").getAbsolutePath();
        KeployDedupAgent.dataSocketPath = new File(sockets, "data.sock").getAbsolutePath();
        Logger.getLogger(KeployDedupAgent.class.getName()).addHandler(handler);
        jacoco = new FakeJacoco();
        System.setProperty("keploy.jacoco.port", String.valueOf(jacoco.server.getLocalPort()));
    }

    @After
    public void tearDown() throws Exception {
        KeployDedupAgent.stop();
        Logger.getLogger(KeployDedupAgent.class.getName()).removeHandler(handler);
        KeployDedupAgent.controlSocketPath = savedControlPath;
        KeployDedupAgent.dataSocketPath = savedDataPath;
        for (String name : PROPERTIES) {
            if (saved.get(name) == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, saved.get(name));
            }
        }
        jacoco.close();
    }

    @Test
    public void derivedAppClassesAreFingerprintedAndWarmedUp() throws Exception {
        File classes = tmp.newFolder("app", "classes");
        write(new File(classes, "com/acme/shop/Main.class"));
        write(new File(classes, "com/acme/shop/web/Controller.class"));
        System.setProperty("java.class.path", classes.getPath());
        System.setProperty("sun.java.command", "com.acme.shop.Main --port 8080");

        startAndSendStart();

        assertLogged("INFO Keploy dedup: fingerprinting the app's 2 classes, from the package com.acme.shop"
                + " (java -cp ... com.acme.shop.Main)");
        assertIndex("derived", "java -cp ... com.acme.shop.Main", "[\"com.acme.shop\"]", 2,
                classes.getPath());
        // The fake classes cannot be loaded: the warmup tried them both.
        assertLogged("INFO Keploy dedup: warmed up application classes (initialized=0, skipped=2)");
        assertNotLogged("no warmup");
    }

    @Test
    public void configuredClassDirsAreFingerprintedAndWarmedUp() throws Exception {
        File classes = tmp.newFolder("module", "classes");
        write(new File(classes, "Main.class"));
        System.setProperty("keploy.java.class.dirs", classes.getPath());
        System.setProperty("java.class.path", classes.getPath());
        System.setProperty("sun.java.command", "Main");

        startAndSendStart();

        assertLogged("INFO Keploy dedup: fingerprinting the app's 1 classes, from the class dirs [" + classes.getPath() + "].");
        assertIndex("override", "the class dirs [" + classes.getPath() + "]", "[]", 1, classes.getPath());
        assertLogged("INFO Keploy dedup: warmed up application classes (initialized=0, skipped=1)");
        assertNotLogged("found no class in KEPLOY_JAVA_CLASS_DIRS");
    }

    @Test
    public void manyClassDirsAreSaidWithHowManyMore() throws Exception {
        List<String> dirs = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            dirs.add(tmp.newFolder("module" + i).getPath());
        }
        write(new File(dirs.get(6), "Main.class"));
        System.setProperty("keploy.java.class.dirs", String.join(File.pathSeparator, dirs));
        System.setProperty("java.class.path", dirs.get(6));
        System.setProperty("sun.java.command", "Main");

        startAndSendStart();

        assertIndex("override", "the class dirs [" + String.join(", ", dirs.subList(0, 5)) + ", and 2 more]", "[]",
                1, dirs.get(6));
    }

    @Test
    public void aJarOnlyAppWithNoSourceTreeIsDerivedByClassNames() throws Exception {
        // java -jar app.jar from an empty dir: no source, no build output,
        // no git. The index comes from the jar's class names alone.
        File withMain = tmp.newFile("shop.jar");
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MAIN_CLASS, "com.acme.shop.Main");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(withMain), manifest)) {
            for (String name : new String[] {"com/acme/shop/Main.class", "com/acme/shop/web/Controller.class",
                    "META-INF/maven/com.acme/shop/pom.properties"}) {
                out.putNextEntry(new ZipEntry(name));
                out.write(1);
                out.closeEntry();
            }
        }
        File cwd = new File(System.getProperty("user.dir"));
        assertTrue(cwd.list().length == 0);
        System.setProperty("java.class.path", withMain.getPath());
        System.setProperty("sun.java.command", withMain.getPath() + " --port 8080");

        startAndSendStart();

        assertLogged("INFO Keploy dedup: fingerprinting the app's 2 classes, from the package com.acme.shop"
                + " (java -jar " + withMain.getPath() + ")");
        assertLogged("INFO Keploy dedup: warmed up application classes (initialized=0, skipped=2)");
    }

    @Test
    public void aSpringBootJarWithNoSourceTreeIsDerivedByClassNames() throws Exception {
        // The v1 case: java -jar of a Spring Boot fat jar from an empty dir,
        // with no source, build output or git. Its libs are stored, as
        // Spring Boot writes them.
        File model = jar(tmp.newFile("shop-model.jar"), "com/acme/shop/model/Item.class");
        File jackson = jar(tmp.newFile("jackson.jar"), "com/fasterxml/jackson/databind/ObjectMapper.class");
        File boot = tmp.newFile("shop-boot.jar");
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MAIN_CLASS,
                "org.springframework.boot.loader.launch.JarLauncher");
        manifest.getMainAttributes().putValue("Start-Class", "com.acme.shop.ShopApplication");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(boot), manifest)) {
            for (String name : new String[] {"org/springframework/boot/loader/launch/JarLauncher.class",
                    "BOOT-INF/classes/com/acme/shop/ShopApplication.class",
                    "BOOT-INF/classes/com/acme/shop/web/Controller.class"}) {
                out.putNextEntry(new ZipEntry(name));
                out.write(1);
                out.closeEntry();
            }
            for (File lib : new File[] {model, jackson}) {
                byte[] bytes = java.nio.file.Files.readAllBytes(lib.toPath());
                ZipEntry entry = new ZipEntry("BOOT-INF/lib/" + lib.getName());
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(bytes);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(bytes.length);
                entry.setCompressedSize(bytes.length);
                entry.setCrc(crc.getValue());
                out.putNextEntry(entry);
                out.write(bytes);
                out.closeEntry();
            }
        }
        File cwd = new File(System.getProperty("user.dir"));
        assertTrue(cwd.list().length == 0);
        System.setProperty("java.class.path", boot.getPath());
        System.setProperty("sun.java.command", boot.getPath());

        startAndSendStart();

        assertLogged("INFO Keploy dedup: fingerprinting the app's 3 classes, from the package com.acme.shop"
                + " (java -jar " + boot.getPath() + ")");
        // No source, no git: the roots are inside the jar.
        assertIndex("derived", "java -jar " + boot.getPath(), "[\"com.acme.shop\"]", 3,
                boot.getAbsolutePath() + "!BOOT-INF/classes", boot.getAbsolutePath() + "!BOOT-INF/lib/shop-model.jar");
    }

    @Test
    public void derivedAppClassesNeedNoClasspathFallback() throws Exception {
        File classes = tmp.newFolder("app", "classes");
        write(new File(classes, "com/acme/shop/Main.class"));
        System.setProperty("java.class.path", classes.getPath());
        System.setProperty("sun.java.command", "com.acme.shop.Main");
        System.setProperty("keploy.java.classpath.fallback", "false");

        startAndSendStart();

        assertLogged("INFO Keploy dedup: fingerprinting the app's 1 classes, from the package com.acme.shop"
                + " (java -cp ... com.acme.shop.Main)");
        assertLogged("INFO Keploy dedup: warmed up application classes (initialized=0, skipped=1)");
    }

    @Test
    public void aMainClassWithNoPackageStillHasItsDir() throws Exception {
        File classes = tmp.newFolder("flat");
        write(new File(classes, "Main.class"));
        write(new File(classes, "lib/Helper.class"));
        System.setProperty("java.class.path", classes.getPath());
        System.setProperty("sun.java.command", "Main");

        startAndSendStart();

        assertLogged("INFO Keploy dedup: fingerprinting the app's 2 classes, from java -cp ... Main.");
        assertLogged("INFO Keploy dedup: warmed up application classes (initialized=0, skipped=2)");
    }

    @Test
    public void wholeClasspathIsSaidAndNeverWarmedUp() throws Exception {
        // Tomcat's launcher: its package is not the app's.
        File classes = tmp.newFolder("webapps");
        write(new File(classes, "com/acme/shop/Servlet.class"));
        File bootstrap = jar(tmp.newFile("bootstrap.jar"), "org/apache/catalina/startup/Bootstrap.class");
        System.setProperty("java.class.path", classes.getPath() + File.pathSeparator + bootstrap.getPath());
        System.setProperty("sun.java.command", "org.apache.catalina.startup.Bootstrap start");

        startAndSendStart();

        assertLogged("WARNING Keploy dedup could not tell the app's own classes from its libraries (java -cp ..."
                + " org.apache.catalina.startup.Bootstrap, the launcher of org.apache.catalina.startup, not the"
                + " app), so it fingerprints all 2 classes of the whole classpath");
        assertIndex("classpath", "java -cp ... org.apache.catalina.startup.Bootstrap, the launcher of"
                + " org.apache.catalina.startup, not the app", "[]", 2, bootstrap.getAbsolutePath(), classes.getPath());
        assertLogged("INFO Keploy dedup: no warmup, as the index is not the app's own classes alone"
                + " (from the whole classpath)");
        assertNotLogged("warmed up");
    }

    @Test
    public void nothingFoundIsSaidAtWarning() throws Exception {
        File classes = tmp.newFolder("webapps");
        write(new File(classes, "com/acme/shop/Servlet.class"));
        File bootstrap = jar(tmp.newFile("bootstrap.jar"), "org/apache/catalina/startup/Bootstrap.class");
        System.setProperty("java.class.path", classes.getPath() + File.pathSeparator + bootstrap.getPath());
        System.setProperty("sun.java.command", "org.apache.catalina.startup.Bootstrap start");
        System.setProperty("keploy.java.classpath.fallback", "false");

        startAndSendStart();

        assertLogged("WARNING Keploy dedup found none of the app's classes (java -cp ..."
                + " org.apache.catalina.startup.Bootstrap, the launcher of org.apache.catalina.startup, not the"
                + " app), nor any class in the jar, and KEPLOY_JAVA_CLASSPATH_FALLBACK=false keeps it off the"
                + " classpath, so no test's coverage is measured. Set KEPLOY_JAVA_CLASS_DIRS");
        assertIndex("none", "java -cp ... org.apache.catalina.startup.Bootstrap, the launcher of"
                + " org.apache.catalina.startup, not the app", "[]", 0);
        assertNotLogged("fingerprint");
        assertNotLogged("warmed up");
    }

    @Test
    public void aMainClassOffTheClasspathFallsBackAndSaysWhere() throws Exception {
        // The launch names com.acme.shop.Main, but the one jar does not hold it.
        File jar = jar(tmp.newFile("app.jar"), "org/other/Main.class");
        System.setProperty("java.class.path", jar.getPath());
        System.setProperty("sun.java.command", "com.acme.shop.Main");

        startAndSendStart();

        assertLogged("WARNING Keploy dedup could not tell the app's own classes from its libraries (java -cp ..."
                + " com.acme.shop.Main, but no dir or jar on the classpath holds com/acme/shop/Main.class), so it"
                + " fingerprints all 1 classes of the whole jar [" + jar.getPath() + "]");
        assertLogged("INFO Keploy dedup: no warmup");
        assertIndex("whole-jar", "java -cp ... com.acme.shop.Main, but no dir or jar on the classpath holds"
                + " com/acme/shop/Main.class", "[]", 1, jar.getPath());
    }

    @Test
    public void aRootWithNoClassFallsBackAndSaysWhere() throws Exception {
        // The main class's dir holds only what the index never takes.
        File classes = tmp.newFolder("tests");
        write(new File(classes, "com/acme/shop/MainTest.class"));
        File other = jar(tmp.newFile("other.jar"), "org/other/Lib.class");
        System.setProperty("java.class.path", classes.getPath() + File.pathSeparator + other.getPath());
        System.setProperty("sun.java.command", "com.acme.shop.MainTest");

        startAndSendStart();

        assertLogged("WARNING Keploy dedup could not tell the app's own classes from its libraries (no class found"
                + " from the package com.acme.shop (java -cp ... com.acme.shop.MainTest)), so it fingerprints all 1"
                + " classes of the whole classpath");
        assertIndex("classpath", "no class found from the package com.acme.shop (java -cp ..."
                + " com.acme.shop.MainTest)", "[]", 1, other.getPath());
    }

    @Test
    public void theBuildOutputUnderTheWorkingDirIsDerived() throws Exception {
        // mvn's target/classes under the dir the app runs from, no override.
        File classes = new File(System.getProperty("user.dir"), "target/classes");
        write(new File(classes, "com/acme/shop/Main.class"));
        System.setProperty("java.class.path", classes.getPath());
        System.setProperty("sun.java.command", "com.acme.shop.Main");

        startAndSendStart();

        assertIndex("derived", "the class dirs [" + classes.getPath() + "]", "[]", 1, classes.getPath());
        assertNotLogged("found no class in KEPLOY_JAVA_CLASS_DIRS");
    }

    @Test
    public void anOverrideWithNoClassIsSaidAndIsNotTheSource() throws Exception {
        // KEPLOY_JAVA_CLASS_DIRS names a dir with no class; the build output
        // under the working dir holds the app.
        File wrong = tmp.newFolder("wrong");
        File classes = new File(System.getProperty("user.dir"), "target/classes");
        write(new File(classes, "com/acme/shop/Main.class"));
        System.setProperty("keploy.java.class.dirs", wrong.getPath());
        System.setProperty("java.class.path", classes.getPath());
        System.setProperty("sun.java.command", "com.acme.shop.Main");

        startAndSendStart();

        assertLogged("WARNING Keploy dedup found no class in KEPLOY_JAVA_CLASS_DIRS [" + wrong.getPath()
                + "], so it looks for the app's classes itself. Set it to the app's class dirs and jars");
        assertIndex("derived", "the class dirs [" + classes.getPath() + "]", "[]", 1, classes.getPath());
    }

    @Test
    public void aJvmWithNoReceiverNeverReadsItsIndexForTheReport() throws Exception {
        // Keploy has bound no data socket: the INDEX report sent at the
        // control socket's bind reaches no one, so it is not made, and the
        // index (every class's bytes) is not read for it.
        File classes = tmp.newFolder("app", "classes");
        write(new File(classes, "com/acme/shop/Main.class"));
        System.setProperty("java.class.path", classes.getPath());
        System.setProperty("sun.java.command", "com.acme.shop.Main");

        assertTrue(KeployDedupAgent.start());
        // Answered after the send at the bind: the worker runs it before its
        // first accept.
        assertEquals("IGNORED proto=2\n", send("END test-set-0/test-0"));
        assertNotLogged("fingerprint");
    }

    private void startAndSendStart() throws IOException {
        assertTrue(KeployDedupAgent.start());
        assertEquals("ACK proto=2\n", send("START test-set-0/test-1"));
    }

    // assertIndex checks the INDEX report of the index the agent started on.
    // Every fake class here is the one byte 1, so a build has one class id.
    private static void assertIndex(String source, String launch, String packages, int classes, String... roots) {
        JsonObject index = JsonParser.parseString(KeployDedupAgent.indexReport()).getAsJsonObject();
        assertEquals(index.toString(), source, index.get("source").getAsString());
        assertEquals(index.toString(), launch, index.get("launch").getAsString());
        assertEquals(index.toString(), packages, index.get("packages").toString());
        assertEquals(index.toString(), classes, index.get("classes").getAsInt());
        List<String> want = new ArrayList<>(Arrays.asList(roots));
        Collections.sort(want);
        assertEquals(index.toString(), new Gson().toJson(want), index.get("roots").toString());
        assertEquals(index.toString(), want.size(), index.get("rootCount").getAsInt());
        String build = "";
        if (classes > 0) {
            String hex = String.format("%016x", CRC64.classId(new byte[] {1}));
            build = "jv1-" + sha256(hex).substring(0, 32);
        }
        assertEquals(index.toString(), build, index.get("build").getAsString());
        // start() is not premain: it starts no JaCoCo and looks for none.
        assertEquals(index.toString(), "unchecked", index.get("jacoco").getAsString());
        assertEquals(index.toString(), "", index.get("includes").getAsString());
    }

    private static String sha256(String text) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private void assertLogged(String prefix) {
        for (String line : logged) {
            if (line.startsWith(prefix)) {
                return;
            }
        }
        throw new AssertionError("nothing logged starts with: " + prefix + "\nlogged: " + logged);
    }

    private void assertNotLogged(String part) {
        for (String line : logged) {
            assertTrue("logged: " + line, !line.contains(part));
        }
    }

    private static File jar(File file, String... names) throws IOException {
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(file))) {
            for (String name : names) {
                out.putNextEntry(new ZipEntry(name));
                out.write(1);
                out.closeEntry();
            }
        }
        return file;
    }

    private static void write(File file) throws IOException {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), new byte[] {1});
    }

    private static String send(String command) throws IOException {
        try (AFUNIXSocket socket = AFUNIXSocket.newInstance()) {
            socket.connect(AFUNIXSocketAddress.of(new File(KeployDedupAgent.controlSocketPath)), 3000);
            socket.setSoTimeout(30000);
            OutputStream out = socket.getOutputStream();
            out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            StringBuilder answer = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                answer.append((char) b);
            }
            return answer.toString();
        }
    }
}
