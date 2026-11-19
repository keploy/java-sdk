package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

// AppClassesTest checks which classes count as the app's own for each way a
// JVM is launched: a classpath (Jib), a Spring Boot jar, a shaded jar.
public class AppClassesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void rootIsTheMainPackageCutToThreePartsOrALongerGroupId() {
        assertEquals("org.apache.fineract",
                AppClasses.root("org.apache.fineract.ServerApplication", Collections.<String>emptySet()));
        assertEquals("com.acme", AppClasses.root("com.acme.Main", Collections.<String>emptySet()));
        assertEquals("com.acme.shop", AppClasses.root("com.acme.shop.web.boot.Main", Collections.<String>emptySet()));
        // A longer groupId the package is in narrows the root.
        assertEquals("com.acme.shop.orders", AppClasses.root("com.acme.shop.orders.app.Main",
                Arrays.asList("com.acme.shop.billing", "com.acme.shop.orders", "com.acme.shop.orders.app.x")));
        // The groupId equal to the whole package counts too.
        assertEquals("com.acme.shop.orders", AppClasses.root("com.acme.shop.orders.Main",
                Collections.singletonList("com.acme.shop.orders")));
        // A shorter groupId never widens it: Spring's own classes are not
        // PetClinic's.
        assertEquals("org.springframework.samples", AppClasses.root(
                "org.springframework.samples.petclinic.PetClinicApplication",
                Collections.singletonList("org.springframework")));
        // A groupId that only shares a prefix of a part is not the package's.
        assertEquals("com.acme.shop", AppClasses.root("com.acme.shop.orders.Main",
                Collections.singletonList("com.acme.shop.ord")));
        // One part, or none, is no app's own.
        assertNull(AppClasses.root("Main", Collections.<String>emptySet()));
        assertNull(AppClasses.root("app.Main", Collections.<String>emptySet()));
    }

    @Test
    public void classpathLaunchTakesItsDirsAndTheRootsClassesInItsJars() throws IOException {
        File classes = tmp.newFolder("app", "classes");
        write(new File(classes, "org/acme/shop/Main.class"));
        write(new File(classes, "org/acme/shop/web/Controller.class"));
        // A class outside the root in the app's own dir is still the app's.
        write(new File(classes, "org/acme/generated/Mapper.class"));
        File libs = tmp.newFolder("app", "libs");
        File core = jar(new File(libs, "shop-core.jar"), null,
                "org/acme/shop/core/Service.class", "META-INF/maven/org.acme.shop/shop-core/pom.properties",
                // The dir's copy wins, as on the JVM's classpath.
                "org/acme/shop/Main.class");
        File spring = jar(new File(libs, "spring-web.jar"), null,
                "org/springframework/web/Servlet.class", "META-INF/maven/org.springframework/spring-web/pom.properties");
        File commons = jar(new File(libs, "acme-commons.jar"), null, "org/acme/commons/Util.class");
        String classpath = join(classes, core, spring, commons, new File(libs, "missing.jar"));

        AppClasses app = AppClasses.derive(classpath, "org.acme.shop.Main --port 8080", tmp.getRoot().getPath());

        assertEquals("org.acme.shop", app.root);
        Map<String, AppClasses.Found> found = scan(app);
        assertEquals(new TreeSet<>(Arrays.asList("org/acme/shop/Main.class", "org/acme/shop/web/Controller.class",
                "org/acme/generated/Mapper.class", "org/acme/shop/core/Service.class")), found.keySet());
        assertTrue(found.get("org/acme/shop/Main.class").location,
                found.get("org/acme/shop/Main.class").location.startsWith(classes.getAbsolutePath()));
        assertEquals("org/acme/shop/core/Service.class",
                new String(found.get("org/acme/shop/core/Service.class").bytes, StandardCharsets.UTF_8));
    }

    @Test
    public void classpathLaunchNarrowsToALongerGroupId() throws IOException {
        File orders = jar(tmp.newFile("orders.jar"), null, "com/acme/shop/orders/Main.class",
                "com/acme/shop/orders/Order.class", "META-INF/maven/com.acme.shop.orders/orders/pom.properties");
        File billing = jar(tmp.newFile("billing.jar"), null, "com/acme/shop/billing/Invoice.class",
                "META-INF/maven/com.acme.shop.billing/billing/pom.properties");

        AppClasses app = AppClasses.derive(join(orders, billing), "com.acme.shop.orders.Main", null);

        assertEquals("com.acme.shop.orders", app.root);
        assertEquals(new TreeSet<>(Arrays.asList("com/acme/shop/orders/Main.class",
                "com/acme/shop/orders/Order.class")), scan(app).keySet());
    }

    @Test
    public void skipLeavesOutWhatTheIndexNeverTakes() throws IOException {
        File classes = tmp.newFolder("classes");
        write(new File(classes, "org/acme/shop/Main.class"));
        write(new File(classes, "org/acme/shop/MainTest.class"));
        File lib = jar(tmp.newFile("lib.jar"), null, "org/acme/shop/LibTest.class", "org/acme/shop/Lib.class");

        Map<String, AppClasses.Found> found = new LinkedHashMap<>();
        AppClasses.derive(join(classes, lib), "org.acme.shop.Main", null)
                .scan(found, key -> key.endsWith("Test.class"));

        assertEquals(new TreeSet<>(Arrays.asList("org/acme/shop/Main.class", "org/acme/shop/Lib.class")),
                new TreeSet<>(found.keySet()));
    }

    @Test
    public void springBootJarTakesItsClassesAndTheRootsClassesInItsLibs() throws IOException {
        File app = springBootJar("BOOT-INF/");

        AppClasses fromJar = AppClasses.derive(app.getName(), app.getName() + " --server.port=8080",
                app.getParent());
        assertEquals("com.example.demo", fromJar.root);
        Map<String, AppClasses.Found> found = scan(fromJar);
        assertEquals(new TreeSet<>(Arrays.asList("com/example/demo/DemoApplication.class",
                "com/example/other/Helper.class", "com/example/demo/model/Item.class")), found.keySet());
        // The app's own copy wins over a lib's.
        assertTrue(found.get("com/example/demo/DemoApplication.class").location,
                found.get("com/example/demo/DemoApplication.class").location
                        .endsWith("!BOOT-INF/classes/com/example/demo/DemoApplication.class"));
        assertTrue(found.get("com/example/demo/model/Item.class").location,
                found.get("com/example/demo/model/Item.class").location
                        .endsWith("!BOOT-INF/lib/demo-model.jar!com/example/demo/model/Item.class"));

        // skip applies to its classes and its libs' alike.
        Map<String, AppClasses.Found> skipped = new LinkedHashMap<>();
        fromJar.scan(skipped, key -> key.endsWith("Helper.class") || key.endsWith("Item.class"));
        assertEquals(Collections.singleton("com/example/demo/DemoApplication.class"), skipped.keySet());

        // The same jar run by Spring Boot's launcher on -cp.
        AppClasses fromLauncher = AppClasses.derive(app.getPath(),
                "org.springframework.boot.loader.launch.JarLauncher", null);
        assertEquals("com.example.demo", fromLauncher.root);
        assertEquals(found.keySet(), scan(fromLauncher).keySet());
    }

    @Test
    public void springBootWarUsesWebInf() throws IOException {
        File war = springBootJar("WEB-INF/");

        AppClasses app = AppClasses.derive(war.getPath(), war.getPath(), null);

        assertEquals("com.example.demo", app.root);
        assertEquals(new TreeSet<>(Arrays.asList("com/example/demo/DemoApplication.class",
                "com/example/other/Helper.class", "com/example/demo/model/Item.class")), scan(app).keySet());
    }

    @Test
    public void extractedSpringBootDirIsReadLikeItsJar() throws IOException {
        File dir = tmp.newFolder("application");
        write(new File(dir, "BOOT-INF/classes/com/example/demo/DemoApplication.class"));
        write(new File(dir, "BOOT-INF/classes/com/example/other/Helper.class"));
        jar(new File(dir, "BOOT-INF/lib/demo-model.jar"), null, "com/example/demo/model/Item.class");
        jar(new File(dir, "BOOT-INF/lib/jackson.jar"), null, "com/fasterxml/jackson/databind/ObjectMapper.class");
        String launcher = "org.springframework.boot.loader.launch.JarLauncher";
        assertFailed(AppClasses.derive(dir.getPath(), launcher, null), "names no Start-Class");

        File manifest = new File(dir, "META-INF/MANIFEST.MF");
        manifest.getParentFile().mkdirs();
        Manifest withStart = manifest(launcher);
        withStart.getMainAttributes().putValue("Start-Class", "com.example.demo.web.DemoApplication");
        try (FileOutputStream out = new FileOutputStream(manifest)) {
            withStart.write(out);
        }
        // A longer groupId narrows the root here too: demo-model's
        // com.example.demo.model is a sibling's, not under com.example.demo.web.
        new File(dir, "META-INF/maven/com.example.demo.web/demo").mkdirs();
        jar(new File(dir, "BOOT-INF/lib/demo-extra.jar"), null, "com/example/demo/web/x/Extra.class");
        AppClasses app = AppClasses.derive(dir.getPath(), launcher, null);

        assertEquals("com.example.demo.web", app.root);
        assertEquals(new TreeSet<>(Arrays.asList("com/example/demo/DemoApplication.class",
                "com/example/other/Helper.class", "com/example/demo/web/x/Extra.class")), scan(app).keySet());

        // Extracted from a war: WEB-INF.
        File war = tmp.newFolder("war");
        write(new File(war, "WEB-INF/classes/com/example/shop/ShopApplication.class"));
        File warManifest = new File(war, "META-INF/MANIFEST.MF");
        warManifest.getParentFile().mkdirs();
        Manifest warStart = manifest("org.springframework.boot.loader.launch.WarLauncher");
        warStart.getMainAttributes().putValue("Start-Class", "com.example.shop.ShopApplication");
        try (FileOutputStream out = new FileOutputStream(warManifest)) {
            warStart.write(out);
        }
        assertEquals(Collections.singleton("com/example/shop/ShopApplication.class"),
                scan(AppClasses.derive(war.getPath(), "org.springframework.boot.loader.launch.WarLauncher", null))
                        .keySet());
    }

    @Test
    public void plainJarAddsTheJarsItsClassPathNames() throws IOException {
        File dir = tmp.newFolder("extracted");
        jar(new File(dir, "lib/shop-core.jar"), null, "com/acme/shop/core/Service.class");
        jar(new File(dir, "lib/jackson.jar"), null, "com/fasterxml/jackson/databind/ObjectMapper.class");
        File app = new File(dir, "app.jar");
        Manifest manifest = manifest("com.acme.shop.Main");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, " lib/shop-core.jar  lib/jackson.jar lib/gone.jar ");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(app), manifest)) {
            entry(out, "com/acme/shop/Main.class", null);
        }

        AppClasses fromJar = AppClasses.derive(app.getPath(), app.getPath(), null);

        assertEquals("com.acme.shop", fromJar.root);
        assertEquals(new TreeSet<>(Arrays.asList("com/acme/shop/Main.class", "com/acme/shop/core/Service.class")),
                scan(fromJar).keySet());
    }

    @Test
    public void shadedJarTakesOnlyTheRootsClasses() throws IOException {
        File shaded = jar(tmp.newFile("hello.jar"), "com.example.helloworld.HelloWorldApplication",
                "com/example/helloworld/HelloWorldApplication.class",
                "com/example/helloworld/resources/HelloResource.class",
                "org/eclipse/jetty/server/Server.class",
                "org/glassfish/jersey/server/ResourceConfig.class",
                "META-INF/maven/com.example/helloworld/pom.properties",
                "META-INF/maven/org.eclipse.jetty/jetty-server/pom.properties");

        AppClasses app = AppClasses.derive(shaded.getPath(), shaded.getPath(), null);

        assertEquals("com.example.helloworld", app.root);
        assertEquals(new TreeSet<>(Arrays.asList("com/example/helloworld/HelloWorldApplication.class",
                "com/example/helloworld/resources/HelloResource.class")), scan(app).keySet());
    }

    @Test
    public void failsWithAReasonWhenTheAppCannotBeToldApart() throws IOException {
        assertFailed(AppClasses.derive("", "", null), "names no main class or jar");
        assertFailed(AppClasses.derive("", "Main", null), "no dir or jar on the classpath holds Main.class");
        assertFailed(AppClasses.derive("", "com.acme.app/com.acme.app.Main --x", null), "(java -m)");
        assertFailed(AppClasses.derive("", "./run-app.sh", null), "neither a jar nor a class");
        File noMain = jar(tmp.newFile("lib.jar"), null, "com/acme/shop/A.class");
        assertFailed(AppClasses.derive(noMain.getPath(), noMain.getPath(), null), "names no Main-Class");
        File bootNoStart = jar(tmp.newFile("boot.jar"), "org.springframework.boot.loader.launch.JarLauncher",
                "BOOT-INF/classes/com/acme/shop/A.class");
        assertFailed(AppClasses.derive(bootNoStart.getPath(), bootNoStart.getPath(), null), "names no Start-Class");
        assertFailed(AppClasses.derive(noMain.getPath(), "org.springframework.boot.loader.launch.JarLauncher", null),
                "no Spring Boot jar on the classpath");
        File notAJar = tmp.newFile("broken.jar");
        Files.write(notAJar.toPath(), "not a zip".getBytes(StandardCharsets.UTF_8));
        assertFailed(AppClasses.derive(notAJar.getPath(), notAJar.getPath(), null), "could not be read");
    }

    @Test
    public void aFileSystemRootIsReadOnlyUnderThePackageRoot() throws IOException {
        File fsRoot = File.listRoots()[0];
        assertEquals(new File(fsRoot, "org/acme/"), AppClasses.walkFrom(fsRoot, "org/acme/"));
        File dir = tmp.newFolder("classes");
        assertEquals(dir, AppClasses.walkFrom(dir, "org/acme/"));
        // With no package root, / is not read at all.
        assertNull(AppClasses.walkFrom(fsRoot, null));
        assertEquals(dir, AppClasses.walkFrom(dir, null));
    }

    @Test
    public void firstTokenIsTheCommandsFirstWordQuotedOrNot() {
        assertEquals("/app/my app.jar", AppClasses.firstToken("\"/app/my app.jar\" --x"));
        assertEquals("/app/a.jar", AppClasses.firstToken("'/app/a.jar"));
        assertEquals("org.acme.Main", AppClasses.firstToken("  org.acme.Main\t--x"));
        assertEquals("", AppClasses.firstToken(null));
        assertEquals("", AppClasses.firstToken("   "));
    }

    @Test
    public void aLauncherIsNeverTakenForTheApp() throws IOException {
        // A jar whose Main-Class is only in a jar its Class-Path names is a
        // launcher stub (Quarkus's quarkus-run.jar, Maven's test fork booter).
        File dir = tmp.newFolder("stub");
        jar(new File(dir, "lib/boot/runner.jar"), null, "com/acme/runner/Entry.class", "com/acme/runner/Loader.class");
        File stub = new File(dir, "run.jar");
        Manifest manifest = manifest("com.acme.runner.Entry");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "lib/boot/runner.jar");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(stub), manifest)) {
            entry(out, "META-INF/app.dat", null);
        }
        assertFailed(AppClasses.derive(stub.getPath(), stub.getPath(), null),
                "its main class com.acme.runner.Entry is not in the jar, which only launches the app");

        // A known launcher's main class, in its own jar or on -cp.
        File start = jar(tmp.newFile("start.jar"), "org.eclipse.jetty.start.Main", "org/eclipse/jetty/start/Main.class");
        assertFailed(AppClasses.derive(start.getPath(), start.getPath(), null),
                "is the launcher of org.eclipse.jetty.start, not the app");
        File bootstrap = jar(tmp.newFile("bootstrap.jar"), null, "org/apache/catalina/startup/Bootstrap.class");
        assertFailed(AppClasses.derive(bootstrap.getPath(), "org.apache.catalina.startup.Bootstrap start", null),
                "the launcher of org.apache.catalina.startup, not the app");
        assertNull(AppClasses.launcher("org.acme.shop.Main"));
        assertEquals("worker.org.gradle",
                AppClasses.launcher("worker.org.gradle.process.internal.worker.GradleWorkerMain"));
    }

    @Test
    public void aThinJarUsesItsStartClass() throws IOException {
        File thin = tmp.newFile("thin.jar");
        Manifest manifest = manifest("org.springframework.boot.loader.wrapper.ThinJarWrapper");
        manifest.getMainAttributes().putValue("Start-Class", "com.acme.shop.ShopApplication");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(thin), manifest)) {
            entry(out, "org/springframework/boot/loader/wrapper/ThinJarWrapper.class", null);
            entry(out, "com/acme/shop/ShopApplication.class", null);
            entry(out, "com/acme/generated/Api.class", null);
        }

        AppClasses app = AppClasses.derive(thin.getPath(), thin.getPath(), null);

        assertEquals("com.acme.shop", app.root);
        // The wrapper is Spring Boot's, never the app's.
        TreeSet<String> expected = new TreeSet<>(Arrays.asList("com/acme/shop/ShopApplication.class",
                "com/acme/generated/Api.class"));
        assertEquals(expected, scan(app).keySet());
        // The same jar run by Spring Boot's PropertiesLauncher on -cp.
        assertEquals(expected, scan(AppClasses.derive(thin.getPath(),
                "org.springframework.boot.loader.launch.PropertiesLauncher", null)).keySet());

        File noStart = jar(tmp.newFile("wrapper.jar"), "org.springframework.boot.loader.wrapper.ThinJarWrapper",
                "org/springframework/boot/loader/wrapper/ThinJarWrapper.class");
        assertFailed(AppClasses.derive(noStart.getPath(), noStart.getPath(), null), "names no Start-Class");
    }

    @Test
    public void theJarThatHoldsTheMainClassIsTakenWhole() throws IOException {
        // Spring Boot's tools extract: app.jar holds the app's classes, and
        // its Class-Path names the libs, as URLs.
        File dir = tmp.newFolder("extract");
        File model = jar(new File(dir, "lib/demo model.jar"), null, "com/example/demo/model/Item.class",
                "com/example/other/Gen.class");
        File jackson = jar(new File(dir, "lib/jackson.jar"), null, "com/fasterxml/jackson/databind/ObjectMapper.class",
                "META-INF/maven/com.fasterxml.jackson.core/jackson-databind/pom.properties");
        File app = new File(dir, "app.jar");
        Manifest manifest = manifest("com.example.demo.DemoApplication");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "lib/demo%20model.jar lib/jackson.jar");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(app), manifest)) {
            entry(out, "com/example/demo/DemoApplication.class", null);
            // Generated code outside the main class's package is the app's too.
            entry(out, "org/openapitools/model/Pet.class", null);
            entry(out, "META-INF/maven/com.example/demo/pom.properties", null);
        }
        TreeSet<String> expected = new TreeSet<>(Arrays.asList("com/example/demo/DemoApplication.class",
                "org/openapitools/model/Pet.class", "com/example/demo/model/Item.class"));

        AppClasses fromJar = AppClasses.derive(app.getPath(), app.getPath(), null);

        assertEquals("com.example.demo", fromJar.root);
        assertEquals(expected, scan(fromJar).keySet());
        // The same jars on a -cp (Jib's packaged mode).
        assertEquals(expected, scan(AppClasses.derive(join(app, model, jackson),
                "com.example.demo.DemoApplication", null)).keySet());

        // A jar with a space in its path, run with java -jar unquoted.
        File spaced = new File(tmp.newFolder("my app"), "app.jar");
        Files.copy(app.toPath(), spaced.toPath());
        AppClasses unquoted = AppClasses.derive(spaced.getPath(), spaced.getPath() + " --x", null);
        assertEquals("java -jar " + spaced.getPath(), unquoted.launch);
        assertEquals(new TreeSet<>(Arrays.asList("com/example/demo/DemoApplication.class",
                "org/openapitools/model/Pet.class")), scan(unquoted).keySet());
    }

    @Test
    public void aShadedJarOnTheClasspathGivesOnlyTheRootsClasses() throws IOException {
        File shaded = jar(tmp.newFile("hello.jar"), null,
                "com/example/hello/Main.class", "com/example/hello/Res.class",
                "org/eclipse/jetty/server/Server.class",
                "META-INF/maven/com.example/hello/pom.properties",
                "META-INF/maven/org.eclipse.jetty/jetty-server/pom.properties");

        AppClasses app = AppClasses.derive(shaded.getPath(), "com.example.hello.Main", null);

        assertEquals(new TreeSet<>(Arrays.asList("com/example/hello/Main.class", "com/example/hello/Res.class")),
                scan(app).keySet());

        // With no package root, a shaded jar has nothing to tell apart by.
        File flat = jar(tmp.newFile("flat.jar"), "Main", "Main.class", "org/eclipse/jetty/server/Server.class",
                "META-INF/maven/acme/flat/pom.properties",
                "META-INF/maven/org.eclipse.jetty/jetty-server/pom.properties");
        assertFailed(AppClasses.derive(flat.getPath(), flat.getPath(), null), "a shaded jar whose main class Main");
        assertFailed(AppClasses.derive(flat.getPath(), "Main", null), "from a shaded jar");
    }

    @Test
    public void isShadedCountsOtherProjectsPoms() {
        assertFalse(AppClasses.isShaded(1, Collections.singleton("org.eclipse.jetty"), "com.acme.shop.Main"));
        assertFalse(AppClasses.isShaded(2, Arrays.asList("com.acme", "com.acme.shop.billing"), "com.acme.shop.Main"));
        assertFalse(AppClasses.isShaded(2, Collections.singleton("com.acme.shop"), "com.acme.shop.web.Main"));
        assertTrue(AppClasses.isShaded(2, Arrays.asList("com.acme.shop", "org.eclipse.jetty"), "com.acme.shop.Main"));
        assertTrue(AppClasses.isShaded(2, Collections.singleton("com.acme.shop"), "Main"));
        // A sibling under the same first two parts is another project's.
        assertTrue(AppClasses.isShaded(2, Collections.singleton("com.acme.tools"), "com.acme.shop.Main"));
    }

    @Test
    public void withNoPackageRootTheAppsOwnPlacesStillCount() throws IOException {
        File classes = tmp.newFolder("flat");
        write(new File(classes, "Main.class"));
        write(new File(classes, "util/Helper.class"));
        File lib = jar(tmp.newFile("lib.jar"), null, "util/Other.class");

        AppClasses fromDir = AppClasses.derive(join(classes, lib), "Main", null);

        assertNull(fromDir.root);
        assertFalse(fromDir.launch, fromDir.failed);
        assertEquals(new TreeSet<>(Arrays.asList("Main.class", "util/Helper.class")), scan(fromDir).keySet());

        // A Spring Boot jar: BOOT-INF/classes, never its libs.
        File model = jar(tmp.newFile("model.jar"), null, "demo/model/Item.class");
        File boot = tmp.newFile("demo.jar");
        Manifest manifest = manifest("org.springframework.boot.loader.launch.JarLauncher");
        manifest.getMainAttributes().putValue("Start-Class", "demo.DemoApplication");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(boot), manifest)) {
            entry(out, "BOOT-INF/classes/demo/DemoApplication.class", null);
            entry(out, "BOOT-INF/lib/model.jar", Files.readAllBytes(model.toPath()));
        }
        AppClasses fromBoot = AppClasses.derive(boot.getPath(), boot.getPath(), null);
        assertNull(fromBoot.root);
        assertEquals(Collections.singleton("demo/DemoApplication.class"), scan(fromBoot).keySet());

        // A plain jar: all of it, but none of the jars its Class-Path names.
        File plain = tmp.newFile("plain.jar");
        Manifest plainManifest = manifest("Main");
        plainManifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "lib.jar");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(plain), plainManifest)) {
            entry(out, "Main.class", null);
            entry(out, "util/Helper.class", null);
        }
        assertEquals(new TreeSet<>(Arrays.asList("Main.class", "util/Helper.class")),
                scan(AppClasses.derive(plain.getPath(), plain.getPath(), null)).keySet());
    }

    @Test
    public void nestedJarsInflateOnlyTheRootsClasses() throws IOException {
        // The first entry's data is damaged: a JarInputStream stops there,
        // as it must inflate it to step over it; the directory never reads it.
        byte[] nested = zip(new String[] {"org/broken/Big.class", "com/acme/shop/A.class", "com/acme/shop/B.class",
                "com/acme/shop/CTest.class"}, new boolean[] {false, false, true, false});
        nested[30 + u16(nested, 26) + u16(nested, 28)] = (byte) 0xff;
        Map<String, AppClasses.Found> out = new TreeMap<>();

        assertTrue(AppClasses.scanZipBytes(nested, "com/acme/shop/", "lib.jar!", out,
                key -> key.endsWith("Test.class")));

        assertEquals(new TreeSet<>(Arrays.asList("com/acme/shop/A.class", "com/acme/shop/B.class")), out.keySet());
        assertEquals("com/acme/shop/A.class", new String(out.get("com/acme/shop/A.class").bytes, StandardCharsets.UTF_8));
        assertEquals("com/acme/shop/B.class", new String(out.get("com/acme/shop/B.class").bytes, StandardCharsets.UTF_8));
        assertEquals("lib.jar!com/acme/shop/B.class", out.get("com/acme/shop/B.class").location);
        assertFalse(AppClasses.scanZipBytes("not a zip".getBytes(StandardCharsets.UTF_8), "com/", "x!", out,
                key -> false));
    }

    @Test
    public void aNestedJarItsDirectoryCannotReadIsStreamed() throws IOException {
        // Data past the end record, beyond where the directory read looks
        // for it: a JarInputStream still reads the entries in order.
        byte[] model = zip(new String[] {"com/example/demo/model/Item.class"}, new boolean[] {false});
        byte[] padded = Arrays.copyOf(model, model.length + 70000);
        assertFalse(AppClasses.scanZipBytes(padded, "com/", "x!", new TreeMap<String, AppClasses.Found>(),
                key -> false));
        File boot = tmp.newFile("demo.jar");
        Manifest manifest = manifest("org.springframework.boot.loader.launch.JarLauncher");
        manifest.getMainAttributes().putValue("Start-Class", "com.example.demo.DemoApplication");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(boot), manifest)) {
            entry(out, "BOOT-INF/classes/com/example/demo/DemoApplication.class", null);
            entry(out, "BOOT-INF/lib/model.jar", padded);
        }

        Map<String, AppClasses.Found> found = scan(AppClasses.derive(boot.getPath(), boot.getPath(), null));

        assertEquals(new TreeSet<>(Arrays.asList("com/example/demo/DemoApplication.class",
                "com/example/demo/model/Item.class")), found.keySet());
        assertTrue(found.get("com/example/demo/model/Item.class").location,
                found.get("com/example/demo/model/Item.class").location
                        .endsWith("!BOOT-INF/lib/model.jar!com/example/demo/model/Item.class"));
    }

    @Test
    public void anUnreadableDirIsPassedOver() throws IOException {
        File classes = tmp.newFolder("classes");
        write(new File(classes, "com/acme/shop/Main.class"));
        File locked = new File(classes, "com/acme/shop/locked");
        write(new File(locked, "Hidden.class"));
        Files.setPosixFilePermissions(locked.toPath(), PosixFilePermissions.fromString("---------"));
        try {
            Assume.assumeFalse("root reads any dir", locked.canRead());
            assertEquals(Collections.singleton("com/acme/shop/Main.class"),
                    scan(AppClasses.derive(classes.getPath(), "com.acme.shop.Main", null)).keySet());
        } finally {
            Files.setPosixFilePermissions(locked.toPath(), PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    public void frameworkLaunchersAreNeverTakenForTheApp() throws IOException {
        // A Vert.x fat jar: Main-Class is Vert.x's launcher, Main-Verticle
        // the app's. Only the verticle's root counts, never io/vertx.
        File vertx = new File(tmp.newFolder("vertx"), "orders-fat.jar");
        Manifest manifest = manifest("io.vertx.core.Launcher");
        manifest.getMainAttributes().putValue("Main-Verticle", "com.acme.orders.MainVerticle");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(vertx), manifest)) {
            for (String name : new String[] {"io/vertx/core/Launcher.class", "io/vertx/core/impl/VertxImpl.class",
                    "io/netty/channel/Channel.class", "com/acme/orders/MainVerticle.class",
                    "com/acme/orders/api/OrderHandler.class", "META-INF/maven/io.vertx/vertx-core/pom.properties",
                    "META-INF/maven/io.netty/netty-transport/pom.properties",
                    "META-INF/maven/com.acme/orders/pom.properties"}) {
                entry(out, name, null);
            }
        }
        AppClasses fat = AppClasses.derive(vertx.getPath(), vertx.getPath(), null);
        assertEquals(fat.launch, "com.acme.orders", fat.root);
        assertEquals(new TreeSet<>(Arrays.asList("com/acme/orders/MainVerticle.class",
                "com/acme/orders/api/OrderHandler.class")), scan(fat).keySet());

        // The same on -cp, as Vert.x 4 and Vert.x 5 name the verticle.
        File core = jar(tmp.newFile("vertx-core.jar"), null, "io/vertx/core/Launcher.class",
                "io/vertx/core/http/HttpServer.class", "io/vertx/launcher/application/VertxApplication.class");
        File app = jar(tmp.newFile("orders.jar"), null, "com/acme/orders/MainVerticle.class",
                "com/acme/orders/api/OrderHandler.class");
        for (String command : new String[] {"io.vertx.core.Launcher run com.acme.orders.MainVerticle -conf c.json",
                "io.vertx.launcher.application.VertxApplication com.acme.orders.MainVerticle"}) {
            AppClasses onCp = AppClasses.derive(join(app, core), command, null);
            assertEquals(onCp.launch, "com.acme.orders", onCp.root);
            assertEquals(new TreeSet<>(Arrays.asList("com/acme/orders/MainVerticle.class",
                    "com/acme/orders/api/OrderHandler.class")), scan(onCp).keySet());
        }
        assertFailed(AppClasses.derive(join(app, core), "io.vertx.core.Launcher", null),
                "the launcher of io.vertx.core.Launcher, not the app");
        File noVerticle = jar(tmp.newFile("no-verticle.jar"), "io.vertx.core.Launcher",
                "io/vertx/core/Launcher.class");
        assertFailed(AppClasses.derive(noVerticle.getPath(), noVerticle.getPath(), null),
                "names no Main-Verticle class");

        // Play (sbt-native-packager), Helidon MP in a Jib image, Karaf: the
        // launcher's package is not the app's, and the app is not found.
        File play = jar(tmp.newFile("play-server.jar"), null, "play/core/server/ProdServerStart.class");
        File shop = jar(tmp.newFile("shop.jar"), null, "controllers/HomeController.class",
                "com/acme/shop/Module.class");
        assertFailed(AppClasses.derive(join(shop, play), "play.core.server.ProdServerStart", null),
                "the launcher of play.core.server, not the app");
        File classes = tmp.newFolder("app", "classes");
        write(new File(classes, "com/acme/shop/GreetResource.class"));
        File helidon = jar(tmp.newFile("helidon-microprofile-cdi.jar"), null,
                "io/helidon/microprofile/cdi/Main.class");
        assertFailed(AppClasses.derive(join(classes, helidon), "io.helidon.microprofile.cdi.Main", null),
                "the launcher of io.helidon.microprofile.cdi, not the app");
        File karaf = jar(tmp.newFile("karaf.jar"), "org.apache.karaf.main.Main", "org/apache/karaf/main/Main.class");
        assertFailed(AppClasses.derive(karaf.getPath(), karaf.getPath(), null),
                "is the launcher of org.apache.karaf.main, not the app");
        assertNull(AppClasses.launcher("io.vertx.core.LauncherTest"));
        assertNull(AppClasses.verticle("io.vertx.core.Launcher run"));
        assertNull(AppClasses.verticle("io.vertx.core.Launcher run -conf"));
    }

    @Test
    public void aSymlinkedClassFileIsTaken() throws IOException {
        File classes = tmp.newFolder("linked");
        write(new File(classes, "com/acme/shop/Main.class"));
        File generated = tmp.newFile("Gen.class");
        Files.write(generated.toPath(), "generated".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(classes, "com/acme/shop/Gen.class").toPath(), generated.toPath());
        // A dangling link is no class.
        Files.createSymbolicLink(new File(classes, "com/acme/shop/Gone.class").toPath(),
                new File(tmp.getRoot(), "missing.class").toPath());

        Map<String, AppClasses.Found> found = scan(AppClasses.derive(classes.getPath(), "com.acme.shop.Main", null));

        assertEquals(new TreeSet<>(Arrays.asList("com/acme/shop/Gen.class", "com/acme/shop/Main.class")),
                found.keySet());
        assertEquals("generated", new String(found.get("com/acme/shop/Gen.class").bytes, StandardCharsets.UTF_8));
    }

    @Test
    public void aStoredNestedJarIsReadInPlace() throws IOException {
        // Spring Boot stores its libs. The first entry's data is damaged: a
        // stream stops there, so finding B shows the lib was read by its
        // directory, in place.
        byte[] nested = zip(new String[] {"org/broken/Big.class", "com/example/demo/model/A.class",
                "com/example/demo/model/B.class"}, new boolean[] {false, false, true});
        nested[30 + u16(nested, 26) + u16(nested, 28)] = (byte) 0xff;
        File boot = tmp.newFile("stored.jar");
        Manifest manifest = manifest("org.springframework.boot.loader.launch.JarLauncher");
        manifest.getMainAttributes().putValue("Start-Class", "com.example.demo.DemoApplication");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(boot), manifest)) {
            entry(out, "BOOT-INF/classes/com/example/demo/DemoApplication.class", null);
            storedEntry(out, "BOOT-INF/lib/model.jar", nested);
        }

        Map<String, AppClasses.Found> found = scan(AppClasses.derive(boot.getPath(), boot.getPath(), null));

        assertEquals(new TreeSet<>(Arrays.asList("com/example/demo/DemoApplication.class",
                "com/example/demo/model/A.class", "com/example/demo/model/B.class")), found.keySet());
        assertEquals("com/example/demo/model/B.class",
                new String(found.get("com/example/demo/model/B.class").bytes, StandardCharsets.UTF_8));
    }

    @Test
    public void aLargeNestedJarIsNeverCopiedIntoTheHeap() throws Exception {
        // A 48 MB lib in a JVM of 32 MB of heap: copied whole into the
        // heap, it runs the JVM out of memory.
        ByteArrayOutputStream lib = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(lib)) {
            byte[] blob = new byte[48 << 20];
            new java.util.Random(7).nextBytes(blob);
            storedEntry(out, "native/libbig.so", blob);
            entry(out, "com/example/demo/model/Item.class", null);
        }
        File boot = tmp.newFile("big.jar");
        Manifest manifest = manifest("org.springframework.boot.loader.launch.JarLauncher");
        manifest.getMainAttributes().putValue("Start-Class", "com.example.demo.DemoApplication");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(boot), manifest)) {
            entry(out, "BOOT-INF/classes/com/example/demo/DemoApplication.class", null);
            storedEntry(out, "BOOT-INF/lib/big.jar", lib.toByteArray());
        }

        String javaBin = new File(System.getProperty("java.home"), "bin/java").getPath();
        Process process = new ProcessBuilder(javaBin, "-Xmx32m", "-XX:+ExitOnOutOfMemoryError", "-cp",
                System.getProperty("java.class.path"), ScanMain.class.getName(), boot.getPath())
                .redirectErrorStream(true).start();
        String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(output, 0, process.exitValue());
        assertEquals("com/example/demo/DemoApplication.class,com/example/demo/model/Item.class", output.trim());
    }

    // ScanMain prints the app classes of a jar run with java -jar, for a row
    // that needs its own JVM.
    public static final class ScanMain {
        public static void main(String[] args) {
            System.out.print(String.join(",",
                    scan(AppClasses.derive(args[0], args[0], null)).keySet()));
        }
    }

    @Test
    public void aDirectoryThatDisagreesWithItselfIsStreamed() throws IOException {
        byte[] good = zip(new String[] {"com/acme/A.class", "com/acme/B.class", "com/acme/C.class"},
                new boolean[] {false, false, false});
        assertTrue(AppClasses.scanZipBytes(good, "com/", "x!", new TreeMap<String, AppClasses.Found>(),
                key -> false));
        int end = good.length - 22;
        // The end record's count is 2 of 3, as a jar of 65538 entries
        // written without zip64 keeps it.
        byte[] fewer = good.clone();
        fewer[end + 10] = 2;
        fewer[end + 8] = 2;
        assertFalse(AppClasses.scanZipBytes(fewer, "com/", "x!", new TreeMap<String, AppClasses.Found>(),
                key -> false));
        // C's size in the directory is 10 of its 16 bytes.
        byte[] shorter = good.clone();
        int at = (int) (good.length - 22 - u32(good, end + 12));
        for (int i = 0; i < 2; i++) {
            at += 46 + u16(good, at + 28) + u16(good, at + 30) + u16(good, at + 32);
        }
        shorter[at + 24] = 10;
        Map<String, AppClasses.Found> out = new TreeMap<>();
        assertFalse(AppClasses.scanZipBytes(shorter, "com/acme/C", "x!", out, key -> false));
        assertTrue(out.isEmpty());
        // Data before the zip (a fully executable jar's launch script) is
        // allowed for.
        byte[] script = "#!/bin/bash\nexec java -jar \"$0\"\n".getBytes(StandardCharsets.UTF_8);
        byte[] prefixed = new byte[script.length + good.length];
        System.arraycopy(script, 0, prefixed, 0, script.length);
        System.arraycopy(good, 0, prefixed, script.length, good.length);
        out.clear();
        assertTrue(AppClasses.scanZipBytes(prefixed, "com/", "x!", out, key -> false));
        assertEquals("com/acme/C.class", new String(out.get("com/acme/C.class").bytes, StandardCharsets.UTF_8));
    }

    @Test
    public void onlyTheFirstJarThatHoldsTheMainClassIsTakenWhole() throws IOException {
        File app = jar(tmp.newFile("shop-2.jar"), null, "com/acme/shop/Main.class", "com/acme/gen/Api.class");
        File old = jar(tmp.newFile("shop-1.jar"), null, "com/acme/shop/Main.class", "com/acme/shop/Old.class",
                "org/lib/Util.class");

        assertEquals(new TreeSet<>(Arrays.asList("com/acme/gen/Api.class", "com/acme/shop/Main.class",
                "com/acme/shop/Old.class")), scan(AppClasses.derive(join(app, old), "com.acme.shop.Main", null))
                .keySet());
    }

    @Test
    public void aStartClassOutsideBootIsTheMainClass() throws IOException {
        File wrapped = tmp.newFile("wrapped.jar");
        Manifest manifest = manifest("com.acme.wrapper.Run");
        manifest.getMainAttributes().putValue("Start-Class", "com.acme.shop.App");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(wrapped), manifest)) {
            entry(out, "com/acme/wrapper/Run.class", null);
            entry(out, "com/acme/shop/App.class", null);
        }

        assertEquals("com.acme.shop", AppClasses.derive(wrapped.getPath(), wrapped.getPath(), null).root);
    }

    // zip writes a zip of the named entries, each holding its own name,
    // stored or deflated.
    private static byte[] zip(String[] names, boolean[] stored) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < names.length; i++) {
                byte[] data = names[i].getBytes(StandardCharsets.UTF_8);
                ZipEntry entry = new ZipEntry(names[i]);
                if (stored[i]) {
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(data.length);
                    entry.setCompressedSize(data.length);
                    entry.setCrc(crc.getValue());
                }
                out.putNextEntry(entry);
                out.write(data);
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static int u16(byte[] bytes, int at) {
        return (bytes[at] & 0xff) | (bytes[at + 1] & 0xff) << 8;
    }

    private static long u32(byte[] bytes, int at) {
        return u16(bytes, at) | (long) u16(bytes, at + 2) << 16;
    }

    private static byte[] readAll(java.io.InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            bytes.write(buffer, 0, read);
        }
        return bytes.toByteArray();
    }

    // storedEntry writes an entry uncompressed, as Spring Boot writes its
    // libs.
    private static void storedEntry(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        entry.setCrc(crc.getValue());
        out.putNextEntry(entry);
        out.write(bytes);
        out.closeEntry();
    }

    private File springBootJar(String inf) throws IOException {
        File model = jar(tmp.newFile("demo-model.jar"), null, "com/example/demo/model/Item.class",
                "com/example/demo/DemoApplication.class");
        File jackson = jar(tmp.newFile("jackson.jar"), null, "com/fasterxml/jackson/databind/ObjectMapper.class");
        File app = new File(tmp.newFolder(), "demo." + (inf.startsWith("WEB") ? "war" : "jar"));
        Manifest manifest = manifest("org.springframework.boot.loader.launch.JarLauncher");
        manifest.getMainAttributes().putValue("Start-Class", "com.example.demo.DemoApplication");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(app), manifest)) {
            entry(out, "org/springframework/boot/loader/launch/JarLauncher.class", null);
            entry(out, inf + "classes/com/example/demo/DemoApplication.class", null);
            entry(out, inf + "classes/com/example/other/Helper.class", null);
            entry(out, inf + "lib/demo-model.jar", Files.readAllBytes(model.toPath()));
            entry(out, inf + "lib/jackson.jar", Files.readAllBytes(jackson.toPath()));
            entry(out, "META-INF/maven/com.example/demo/pom.properties", null);
        }
        return app;
    }

    private static void assertFailed(AppClasses app, String reason) {
        assertTrue(app.launch, app.failed);
        assertNull(app.launch, app.root);
        assertTrue(app.launch, app.launch.contains(reason));
        Map<String, AppClasses.Found> found = new LinkedHashMap<>();
        app.scan(found, key -> false);
        assertTrue(found.isEmpty());
    }

    private static Map<String, AppClasses.Found> scan(AppClasses app) {
        Map<String, AppClasses.Found> found = new LinkedHashMap<>();
        app.scan(found, key -> false);
        Map<String, AppClasses.Found> sorted = new TreeMap<>(found);
        return sorted;
    }

    private static void write(File file) throws IOException {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), file.getName().getBytes(StandardCharsets.UTF_8));
    }

    private static Manifest manifest(String mainClass) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        return manifest;
    }

    // jar writes a jar of the named entries, each holding its own name.
    private static File jar(File file, String mainClass, String... names) throws IOException {
        file.getParentFile().mkdirs();
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(file), manifest(mainClass))) {
            for (String name : names) {
                entry(out, name, null);
            }
        }
        return file;
    }

    private static void entry(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes != null ? bytes : name.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static String join(File... files) {
        StringBuilder classpath = new StringBuilder();
        for (File file : files) {
            if (classpath.length() > 0) {
                classpath.append(File.pathSeparatorChar);
            }
            classpath.append(file.getPath());
        }
        return classpath.toString();
    }
}
