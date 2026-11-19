package io.keploy.dedup;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.Buffer;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * AppClasses tells the app's own classes from its libraries' by how the JVM
 * was launched, so that dedup fingerprints, and warms up, only the app's.
 * <p>
 * Library code (Spring, Hibernate, Jetty, Jackson) runs on nearly every
 * request, so in a fingerprint it makes tests that run the same app code look
 * different. On a large app it is also most of the classpath (Fineract: about
 * 7k own classes in 112k), and an index of all of it made the warmup, the
 * per-test dump and the class upload too big to finish.
 * <p>
 * The app's own code is where its main class is: the classpath dir or jar
 * that holds it is taken whole, so generated code outside the main class's
 * package (OpenAPI, jOOQ, protobuf) still counts. The other jars give only
 * the classes under the package root of the main class
 * ({@link #root(String, Collection)}):
 * <ul>
 *   <li>{@code java -cp ... Main} (Jib's {@code /app/classes} and
 *   {@code /app/libs}, or any other classpath): every class in the
 *   classpath's dirs and in the jar that holds the main class, and the
 *   classes under the root in its other jars;</li>
 *   <li>a Spring Boot jar ({@code java -jar}, or its launcher on
 *   {@code -cp}, the jar or the dir it was extracted to): every class in
 *   {@code BOOT-INF/classes}, and the classes under the root in the jars of
 *   {@code BOOT-INF/lib} ({@code WEB-INF} for a war);</li>
 *   <li>any other jar: all of it, or only the classes under the root when it
 *   is shaded (it holds the Maven metadata of other projects' artifacts), and
 *   the classes under the root in the jars its manifest's {@code Class-Path}
 *   names.</li>
 * </ul>
 * A jar counts by the classes it holds, not by its Maven groupId: Gradle
 * builds write no {@code pom.properties}, so the app's own module jars often
 * carry none.
 * <p>
 * A launch it cannot read this way fails with a reason instead of guessing:
 * a main class that is not in the jar or classpath it names (a launcher
 * stub, as Quarkus and Maven's test forks run), or a known launcher's main
 * class (Tomcat, Jetty, WildFly, Play, Helidon MP, Karaf), whose package is
 * not the app's. Vert.x's launcher runs the app's verticle, which is then
 * the main class.
 */
final class AppClasses {

    private static final Pattern CLASS_NAME =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final String MAVEN_DIR = "META-INF/maven/";
    private static final String BOOT_LAUNCHER = "org.springframework.boot.loader.";
    private static final String BOOT_LAUNCHER_PATH = "org/springframework/boot/loader/";
    // The package root is at most this many parts of the main class's
    // package, unless a Maven groupId on the classpath names a longer one.
    private static final int ROOT_PARTS = 3;
    // Main classes of servers, frameworks, build tools and test runners,
    // which load the app themselves: their package is never the app's. A
    // name that ends in a dot is a package; any other is one class.
    private static final String[] LAUNCHERS = {
        "org.apache.catalina.startup.",
        "org.eclipse.jetty.start.",
        "org.jboss.modules.",
        "io.quarkus.bootstrap.",
        "io.vertx.core.Launcher",
        "io.vertx.launcher.",
        "play.core.server.",
        "io.helidon.microprofile.cdi.",
        "org.apache.karaf.main.",
        "org.apache.felix.main.",
        "org.eclipse.equinox.launcher.",
        "com.intellij.rt.",
        "org.apache.maven.surefire.",
        "org.codehaus.plexus.classworlds.",
        "org.gradle.",
        "worker.org.gradle.",
        "org.junit.platform.console.",
    };
    // Vert.x's launchers, which run the verticle the manifest's
    // Main-Verticle or the command names: that class is the app's.
    private static final String VERTX_LAUNCHER = "io.vertx.core.Launcher";
    private static final String VERTX_APPLICATION = "io.vertx.launcher.application.VertxApplication";

    // One class found: its key ("org/acme/Foo.class"), where it was read
    // from, and its bytes.
    static final class Found {
        final String location;
        final byte[] bytes;

        Found(String location, byte[] bytes) {
            this.location = location;
            this.bytes = bytes;
        }
    }

    // root is the app's package root ("org.apache.fineract"), or null when
    // the main class has no package of two parts or more: then only the dirs
    // and jars taken whole count.
    final String root;
    // launch says how the JVM was launched, or, when failed, why the app's
    // classes could not be told apart.
    final String launch;
    final boolean failed;
    private final List<File> dirs;
    private final List<File> wholeJars;
    private final List<File> rootJars;
    private final File bootJar;
    private final String bootClasses;
    private final String bootLib;

    private AppClasses(String root, String launch, boolean failed, List<File> dirs, List<File> wholeJars,
                       List<File> rootJars, File bootJar, String bootClasses, String bootLib) {
        this.root = root;
        this.launch = launch;
        this.failed = failed;
        this.dirs = dirs;
        this.wholeJars = wholeJars;
        this.rootJars = rootJars;
        this.bootJar = bootJar;
        this.bootClasses = bootClasses;
        this.bootLib = bootLib;
    }

    private static AppClasses failed(String why) {
        List<File> none = Collections.emptyList();
        return new AppClasses(null, why, true, none, none, none, null, null, null);
    }

    /**
     * derive finds how the JVM was launched from its classpath
     * ({@code java.class.path}), its command ({@code sun.java.command}: the
     * main class or jar, then the app's arguments) and its working dir.
     */
    static AppClasses derive(String classpath, String command, String userDir) {
        String first = firstToken(command);
        if (first.isEmpty()) {
            return failed("the JVM's command names no main class or jar");
        }
        File jar = archiveFile(first, userDir);
        if (jar == null) {
            jar = soleArchive(classpath, command, userDir);
        }
        if (jar != null) {
            return fromJar(jar, "java -jar " + jar.getPath());
        }
        int slash = first.indexOf('/');
        if (slash > 0 && CLASS_NAME.matcher(first.substring(0, slash)).matches()
                && CLASS_NAME.matcher(first.substring(slash + 1)).matches()) {
            return failed("the JVM runs the module " + first + " (java -m), and the app's classes are read only"
                    + " from a classpath");
        }
        if (!CLASS_NAME.matcher(first).matches()) {
            return failed("the JVM's command starts with " + first + ", which is neither a jar nor a class");
        }
        List<File> entries = classpathEntries(classpath, userDir);
        if (first.startsWith(BOOT_LAUNCHER)) {
            for (File entry : entries) {
                if (entry.isFile() && isArchive(entry) && isBootJar(entry)) {
                    return fromJar(entry, "Spring Boot's launcher on " + entry.getPath());
                }
                // A jar extracted to a dir, as Spring Boot's layered Docker
                // images run it.
                for (String inf : new String[] {"BOOT-INF/", "WEB-INF/"}) {
                    if (entry.isDirectory() && new File(entry, inf + "classes").isDirectory()) {
                        return fromExtractedBoot(entry, inf, "Spring Boot's launcher on " + entry.getPath());
                    }
                }
            }
            return failed("Spring Boot's launcher " + first + " runs with no Spring Boot jar on the classpath");
        }
        if (isVertx(first)) {
            String verticle = verticle(command);
            if (verticle != null) {
                return fromClasspath(verticle, entries, "java -cp ... " + first + " (Vert.x) running " + verticle);
            }
        }
        String launcher = launcher(first);
        if (launcher != null) {
            return failed("java -cp ... " + first + ", the launcher of " + launcher + ", not the app");
        }
        return fromClasspath(first, entries, "java -cp ... " + first);
    }

    // fromJar reads a jar run with java -jar (or a Spring Boot jar run by its
    // launcher).
    private static AppClasses fromJar(File jar, String launch) {
        String[] boot;
        String main;
        Set<String> groupIds = new LinkedHashSet<>();
        List<String> classPath = new ArrayList<>();
        boolean shaded;
        try (JarFile jarFile = new JarFile(jar)) {
            boot = bootLayout(jarFile);
            int poms = addGroupIds(jarFile, groupIds);
            Manifest manifest = jarFile.getManifest();
            Attributes attributes = manifest == null ? new Attributes() : manifest.getMainAttributes();
            String mainClass = trimmed(attributes.getValue(Attributes.Name.MAIN_CLASS));
            String start = trimmed(attributes.getValue("Start-Class"));
            // A Spring Boot jar's Main-Class is its launcher (or, in a thin
            // jar, the launcher's wrapper, with no BOOT-INF); Start-Class is
            // the app's.
            boolean boots = boot != null || start != null
                    || (mainClass != null && mainClass.startsWith(BOOT_LAUNCHER));
            main = boots ? start : mainClass;
            if (main == null) {
                return failed(launch + ", but its manifest names no " + (boots ? "Start-Class" : "Main-Class"));
            }
            if (!boots && isVertx(main)) {
                // A Vert.x fat jar: Main-Class is Vert.x's launcher, and
                // Main-Verticle the app's (or "js:..." and the like, which
                // is no class).
                String verticle = trimmed(attributes.getValue("Main-Verticle"));
                if (verticle == null || !CLASS_NAME.matcher(verticle).matches()) {
                    return failed(launch + ", whose main class " + main + " is Vert.x's launcher, and its"
                            + " manifest names no Main-Verticle class");
                }
                main = verticle;
            }
            if (boot == null) {
                String launcher = launcher(main);
                if (launcher != null) {
                    return failed(launch + ", whose main class " + main + " is the launcher of " + launcher
                            + ", not the app");
                }
                // A jar whose main class is in a jar its Class-Path names is
                // a launcher stub (Quarkus's quarkus-run.jar, Maven's test
                // fork booter): its main class's package is not the app's.
                if (jarFile.getEntry(classFile(main)) == null) {
                    return failed(launch + ", but its main class " + main + " is not in the jar, which only"
                            + " launches the app");
                }
                shaded = isShaded(poms, groupIds, main);
                String listed = attributes.getValue(Attributes.Name.CLASS_PATH);
                if (listed != null) {
                    classPath.addAll(Arrays.asList(listed.trim().split("\\s+")));
                }
            } else {
                shaded = false;
            }
        } catch (IOException e) {
            return failed(launch + ", but the jar could not be read: " + e.getMessage());
        }
        String root = root(main, groupIds);
        List<File> none = Collections.emptyList();
        if (boot != null) {
            return new AppClasses(root, launch, false, none, none, none, jar, boot[0], boot[1]);
        }
        if (shaded && root == null) {
            return failed(launch + ", a shaded jar whose main class " + main
                    + " has no package of two parts or more");
        }
        // The jars its manifest's Class-Path names (as Spring Boot's own
        // extract writes them, lib/*.jar) may hold the app's other modules.
        List<File> rootJars = new ArrayList<>();
        if (shaded) {
            rootJars.add(jar);
        }
        for (String part : classPath) {
            File listed = classPathFile(jar, part);
            if (listed != null && listed.isFile() && isArchive(listed)) {
                rootJars.add(listed);
            }
        }
        return new AppClasses(root, launch, false, none,
                shaded ? none : Collections.singletonList(jar), rootJars, null, null, null);
    }

    // fromExtractedBoot reads a Spring Boot jar extracted to dir: its
    // classes dir whole, and the classes under the root in its lib dir's jars.
    private static AppClasses fromExtractedBoot(File dir, String inf, String launch) {
        String main = null;
        File manifestFile = new File(dir, JarFile.MANIFEST_NAME);
        try (InputStream input = Files.newInputStream(manifestFile.toPath())) {
            main = trimmed(new Manifest(input).getMainAttributes().getValue("Start-Class"));
        } catch (IOException e) {
            // Said below as a manifest with no Start-Class.
        }
        if (main == null) {
            return failed(launch + ", but " + manifestFile.getPath() + " names no Start-Class");
        }
        Set<String> groupIds = new LinkedHashSet<>();
        File[] mavenDirs = new File(dir, MAVEN_DIR).listFiles(File::isDirectory);
        if (mavenDirs != null) {
            for (File groupDir : mavenDirs) {
                groupIds.add(groupDir.getName());
            }
        }
        String root = root(main, groupIds);
        List<File> libs = new ArrayList<>();
        File[] files = new File(dir, inf + "lib").listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && isArchive(file)) {
                    libs.add(file);
                }
            }
            Collections.sort(libs);
        }
        return new AppClasses(root, launch, false, Collections.singletonList(new File(dir, inf + "classes")),
                Collections.<File>emptyList(), libs, null, null, null);
    }

    // fromClasspath reads a classpath run as java -cp ... main: its dirs and
    // the jar that holds the main class whole (that jar only under the root
    // when it is shaded), and its other jars that hold a class under the root.
    private static AppClasses fromClasspath(String main, List<File> entries, String launch) {
        String classFile = classFile(main);
        String cut = root(main, Collections.<String>emptySet());
        // The root is cut, or a longer groupId under it, so a jar with no
        // class under cut has none under the root either.
        String cutPrefix = cut == null ? null : cut.replace('.', '/') + "/";
        Set<String> groupIds = new LinkedHashSet<>();
        List<File> dirs = new ArrayList<>();
        List<File> wholeJars = new ArrayList<>();
        List<File> rootJars = new ArrayList<>();
        boolean home = false;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                dirs.add(entry);
                home |= new File(entry, classFile).isFile();
            } else if (entry.isFile() && isArchive(entry)) {
                try (JarFile jarFile = new JarFile(entry)) {
                    Set<String> own = new LinkedHashSet<>();
                    int poms = addGroupIds(jarFile, own);
                    groupIds.addAll(own);
                    // The first entry that holds the main class is where the
                    // JVM loads it from: the app's own jar.
                    if (!home && jarFile.getEntry(classFile) != null) {
                        home = true;
                        if (!isShaded(poms, own, main)) {
                            wholeJars.add(entry);
                            continue;
                        }
                    }
                    if (cutPrefix != null && hasClassUnder(jarFile, cutPrefix)) {
                        rootJars.add(entry);
                    }
                } catch (IOException e) {
                    // A jar the JVM cannot read either holds none of the
                    // app's classes.
                }
            }
        }
        if (!home) {
            return failed(launch + ", but no dir or jar on the classpath holds " + classFile);
        }
        String root = root(main, groupIds);
        if (root == null && dirs.isEmpty() && wholeJars.isEmpty()) {
            return failed(launch + ", from a shaded jar, and the main class has no package of two parts or more");
        }
        return new AppClasses(root, launch, false, dirs, wholeJars, rootJars, null, null, null);
    }

    /**
     * root is the app's package root: the main class's package cut to its
     * first three parts ({@code org.apache.fineract} for
     * {@code org.apache.fineract.ServerApplication}), or a longer Maven
     * groupId that the package is in, so that {@code com.acme.shop.orders}
     * stays apart from a sibling {@code com.acme.shop.billing} library. A
     * shorter groupId never widens it: {@code org.springframework} must not
     * make Spring's classes the app's for
     * {@code org.springframework.samples.petclinic}. Null when the package
     * has fewer than two parts, as one part ({@code com}) is no app's own.
     */
    static String root(String mainClass, Collection<String> groupIds) {
        int lastDot = mainClass.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        String pkg = mainClass.substring(0, lastDot);
        String[] parts = pkg.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        String root = cut(parts);
        for (String groupId : groupIds) {
            if (groupId.length() > root.length() && (pkg.equals(groupId) || pkg.startsWith(groupId + "."))) {
                root = groupId;
            }
        }
        return root;
    }

    private static String cut(String[] parts) {
        StringBuilder cut = new StringBuilder(parts[0]);
        for (int i = 1; i < Math.min(ROOT_PARTS, parts.length); i++) {
            cut.append('.').append(parts[i]);
        }
        return cut.toString();
    }

    /**
     * isShaded says whether a jar that holds the main class holds other
     * projects' code too: maven-shade, the assembly plugin and Gradle's
     * shadow copy each dependency's {@code pom.properties}, so a shaded jar
     * has more than one, and some of another project. A groupId is the app's
     * when it and the main class's package cut to three parts are one in the
     * other ({@code com.example} for {@code com.example.demo}). A jar shaded
     * of dependencies that carry no {@code pom.properties} (built by Gradle)
     * reads as not shaded, and is taken whole, as it was before.
     */
    static boolean isShaded(int poms, Collection<String> groupIds, String mainClass) {
        if (poms < 2) {
            return false;
        }
        int lastDot = mainClass.lastIndexOf('.');
        String app = lastDot <= 0 ? "" : cut(mainClass.substring(0, lastDot).split("\\."));
        for (String groupId : groupIds) {
            boolean own = !app.isEmpty() && (app.equals(groupId) || app.startsWith(groupId + ".")
                    || groupId.startsWith(app + "."));
            if (!own) {
                return true;
            }
        }
        return false;
    }

    // launcher names what a main class launches when it is a known
    // launcher's, or null.
    static String launcher(String mainClass) {
        for (String name : LAUNCHERS) {
            if (name.endsWith(".") ? mainClass.startsWith(name) : mainClass.equals(name)) {
                return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
            }
        }
        return null;
    }

    private static boolean isVertx(String mainClass) {
        return VERTX_LAUNCHER.equals(mainClass) || VERTX_APPLICATION.equals(mainClass);
    }

    // verticle is the class a Vert.x launch command runs: "Launcher run
    // com.acme.Main ..." (Vert.x 4) or "VertxApplication com.acme.Main ..."
    // (Vert.x 5), or null when it names none.
    static String verticle(String command) {
        String[] words = command.trim().split("\\s+");
        int at = 1;
        if (at < words.length && words[at].equals("run")) {
            at++;
        }
        if (at >= words.length || words[at].indexOf('.') < 0 || !CLASS_NAME.matcher(words[at]).matches()) {
            return null;
        }
        return words[at];
    }

    /**
     * scan puts every app class it finds in out, keyed "org/acme/Foo.class",
     * unless skip takes the key or out has one by that key already: the first
     * one found wins, as with the JVM's class loading.
     */
    void scan(Map<String, Found> out, Predicate<String> skip) {
        if (failed) {
            return;
        }
        String prefix = root == null ? null : root.replace('.', '/') + "/";
        for (File dir : dirs) {
            File from = walkFrom(dir, prefix);
            if (from != null) {
                scanDirectory(from, dir, out, skip);
            }
        }
        for (File jar : wholeJars) {
            scanJar(jar, "", out, skip);
        }
        // The root-filtered jars give nothing when there is no root.
        if (prefix != null) {
            for (File jar : rootJars) {
                scanJar(jar, prefix, out, skip);
            }
        }
        if (bootJar != null) {
            scanBootJar(prefix, out, skip);
        }
    }

    // walkFrom is where a classpath dir is read from: the whole dir, but for
    // the file system's root ("java Main" run in /, whose classpath is "."),
    // only the package root's own dir, as a walk of / never ends in time;
    // null there when there is no root.
    static File walkFrom(File dir, String prefix) {
        if (dir.getAbsoluteFile().toPath().getParent() != null) {
            return dir;
        }
        return prefix == null ? null : new File(dir, prefix);
    }

    /**
     * walkClassFiles calls each with every .class file under start. A file
     * or dir it cannot read is passed over, as the JVM cannot load from it
     * either, and never ends the walk: Files.walk throws for the first one,
     * which left the whole index empty.
     */
    static void walkClassFiles(Path start, Consumer<Path> each) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                // Without FOLLOW_LINKS the attributes are a link's own, so a
                // link to a class file (a symlink farm, a projected volume)
                // is followed here, as the JVM follows it.
                boolean regular = attributes.isRegularFile()
                        || (attributes.isSymbolicLink() && Files.isRegularFile(file));
                if (regular && file.toString().endsWith(".class")) {
                    each.accept(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void scanDirectory(File walkFrom, File base, Map<String, Found> out, Predicate<String> skip) {
        if (!walkFrom.isDirectory()) {
            return;
        }
        Path basePath = base.toPath();
        try {
            walkClassFiles(walkFrom.toPath(), path -> {
                String key = basePath.relativize(path).toString().replace(File.separatorChar, '/');
                if (skip.test(key) || out.containsKey(key)) {
                    return;
                }
                try {
                    out.put(key, new Found(path.toAbsolutePath().toString()
                            .replace(File.separatorChar, '/'), Files.readAllBytes(path)));
                } catch (IOException e) {
                    // Unreadable: the JVM cannot load it either.
                }
            });
        } catch (IOException e) {
            // A directory gone or unreadable holds none of the app's classes.
        }
    }

    // scanJar reads the classes under prefix in a jar ("" for all of it, but
    // for a Spring Boot launcher's own classes, never the app's).
    private static void scanJar(File jar, String prefix, Map<String, Found> out, Predicate<String> skip) {
        try (JarFile jarFile = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(prefix) || !name.endsWith(".class") || name.startsWith(BOOT_LAUNCHER_PATH)
                        || skip.test(name) || out.containsKey(name)) {
                    continue;
                }
                try (InputStream input = jarFile.getInputStream(entry)) {
                    out.put(name, new Found(jar.getAbsolutePath() + "!" + name, readAllBytes(input)));
                }
            }
        } catch (IOException e) {
            // As for a directory: an unreadable jar holds none of the app's classes.
        }
    }

    // scanBootJar reads a Spring Boot jar: its classes dir whole first, as
    // its launcher puts it first on the classpath, then the classes under
    // the root in its lib dir's jars (none when there is no root).
    private void scanBootJar(String prefix, Map<String, Found> out, Predicate<String> skip) {
        String location = bootJar.getAbsolutePath();
        try (JarFile jarFile = new JarFile(bootJar)) {
            List<JarEntry> libs = new ArrayList<>();
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith(bootLib) && name.endsWith(".jar")) {
                    libs.add(entry);
                    continue;
                }
                if (!name.startsWith(bootClasses) || !name.endsWith(".class")) {
                    continue;
                }
                String key = name.substring(bootClasses.length());
                if (skip.test(key) || out.containsKey(key)) {
                    continue;
                }
                try (InputStream input = jarFile.getInputStream(entry)) {
                    out.put(key, new Found(location + "!" + name, readAllBytes(input)));
                }
            }
            if (prefix == null || libs.isEmpty()) {
                return;
            }
            // Spring Boot stores its libs uncompressed, so each is read in
            // place from the jar mapped read-only, off the app's heap: a lib
            // copied whole into the heap (an AWS SDK bundle, a native-library
            // jar: hundreds of MB) could run the app out of memory. A lib
            // read this way fails over to a stream, whose heap use is one
            // class at a time.
            ByteBuffer mapped = map(bootJar);
            Map<String, ZipItem> stored = new HashMap<>();
            List<ZipItem> items = mapped == null ? null : centralDirectory(mapped, 0, mapped.limit());
            if (items != null) {
                for (ZipItem item : items) {
                    if (item.method == 0 && item.compressed == item.size) {
                        stored.put(item.name, item);
                    }
                }
            }
            for (JarEntry lib : libs) {
                String libLocation = location + "!" + lib.getName() + "!";
                ZipItem item = stored.get(lib.getName());
                long data = item == null ? -1 : dataOffset(mapped, 0, mapped.limit(), item);
                if (data >= 0 && data + item.size <= mapped.limit()
                        && scanZip(mapped, (int) data, (int) item.size, prefix, libLocation, out, skip)) {
                    continue;
                }
                try (InputStream input = jarFile.getInputStream(lib)) {
                    scanJarStream(input, prefix, libLocation, out, skip);
                } catch (IOException e) {
                    // One unreadable nested jar does not hide the others.
                }
            }
        } catch (IOException e) {
            // As for a plain jar.
        }
    }

    // map is a file mapped read-only, or null when it cannot be (over 2 GB,
    // or a file system that does not map).
    private static ByteBuffer map(File file) {
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            long size = channel.size();
            return size > Integer.MAX_VALUE ? null : channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    private static void scanJarStream(InputStream input, String prefix, String location, Map<String, Found> out,
                                      Predicate<String> skip) throws IOException {
        try (JarInputStream nested = new JarInputStream(input)) {
            JarEntry entry;
            while ((entry = nested.getNextJarEntry()) != null) {
                String name = entry.getName();
                if (!name.startsWith(prefix) || !name.endsWith(".class") || skip.test(name)
                        || out.containsKey(name)) {
                    continue;
                }
                out.put(name, new Found(location + name, readAllBytes(nested)));
            }
        }
    }

    // One entry of a zip's central directory; local is its local header's
    // offset from the zip's first byte.
    static final class ZipItem {
        final String name;
        final int method;
        final long compressed;
        final long size;
        final long local;

        ZipItem(String name, int method, long compressed, long size, long local) {
            this.name = name;
            this.method = method;
            this.compressed = compressed;
            this.size = size;
            this.local = local;
        }
    }

    // A class file is far smaller; an entry that says it is larger is read
    // by the stream, which reads what is there instead.
    private static final long MAX_CLASS_SIZE = 64L << 20;

    /**
     * scanZipBytes is scanZip over a jar held in a byte array.
     */
    static boolean scanZipBytes(byte[] zip, String prefix, String location, Map<String, Found> out,
                                Predicate<String> skip) {
        return scanZip(ByteBuffer.wrap(zip), 0, zip.length, prefix, location, out, skip);
    }

    /**
     * scanZip reads the classes under prefix from the jar at zip[base,
     * base + length) by its central directory, so that it inflates only
     * those: a JarInputStream must inflate every entry to step over it, and
     * a Spring Boot jar's libs are most of its size (1.25 s for 70 MB of
     * libs, inside the first START). False when it is not a zip read this
     * way (zip64, another compression, or a directory that disagrees with
     * itself or with the data), for the caller to stream it instead; out may
     * then hold some of the classes already, each read whole and right.
     */
    static boolean scanZip(ByteBuffer zip, int base, int length, String prefix, String location,
                           Map<String, Found> out, Predicate<String> skip) {
        try {
            List<ZipItem> items = centralDirectory(zip, base, length);
            if (items == null) {
                return false;
            }
            for (ZipItem item : items) {
                String name = item.name;
                if (!name.startsWith(prefix) || !name.endsWith(".class") || skip.test(name)
                        || out.containsKey(name)) {
                    continue;
                }
                byte[] bytes = entryBytes(zip, base, length, item);
                if (bytes == null) {
                    return false;
                }
                out.put(name, new Found(location + name, bytes));
            }
            return true;
        } catch (IndexOutOfBoundsException | BufferUnderflowException | IllegalArgumentException e) {
            // Every read is bounds-checked first; this is the last guard, as
            // this runs inside the app's JVM.
            return false;
        }
    }

    /**
     * centralDirectory lists the zip at zip[base, base + length), or is null
     * when it is not one read this way: zip64, damaged, or an end record
     * whose entry count or directory size disagrees with the directory (a
     * jar of over 65535 entries written without zip64 keeps its count mod
     * 65536). Data before the zip (a launch script, as Spring Boot's fully
     * executable jars have) is allowed for, as the JDK's ZipFile does.
     */
    static List<ZipItem> centralDirectory(ByteBuffer zip, int base, int length) {
        int end = -1;
        for (int at = base + length - 22; at >= base && at >= base + length - 22 - 0xFFFF; at--) {
            if (u32(zip, at) == 0x06054b50L) {
                end = at;
                break;
            }
        }
        if (end < 0 || (end - 20 >= base && u32(zip, end - 20) == 0x07064b50L)) {
            return null;
        }
        int count = u16(zip, end + 10);
        long size = u32(zip, end + 12);
        long directory = u32(zip, end + 16);
        if (count == 0xFFFF || size > end - base) {
            return null;
        }
        // The directory ends at the end record; its offset there is from the
        // zip's first byte, so the difference is the data before the zip.
        int at = (int) (end - size);
        long before = at - base - directory;
        if (before < 0) {
            return null;
        }
        List<ZipItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (at + 46 > end || u32(zip, at) != 0x02014b50L) {
                return null;
            }
            int nameLength = u16(zip, at + 28);
            int next = at + 46 + nameLength + u16(zip, at + 30) + u16(zip, at + 32);
            if (next > end) {
                return null;
            }
            String name = new String(copy(zip, at + 46, nameLength), StandardCharsets.UTF_8);
            items.add(new ZipItem(name, u16(zip, at + 10), u32(zip, at + 20), u32(zip, at + 24),
                    u32(zip, at + 42) + before));
            at = next;
        }
        return at == end ? items : null;
    }

    // dataOffset is where an entry's data starts, from base, or -1 when its
    // local header is not there.
    static long dataOffset(ByteBuffer zip, int base, int length, ZipItem item) {
        if (item.local + 30 > length || u32(zip, base + (int) item.local) != 0x04034b50L) {
            return -1;
        }
        int at = base + (int) item.local;
        return item.local + 30 + u16(zip, at + 26) + u16(zip, at + 28);
    }

    // entryBytes is one entry's bytes, or null when they cannot be read this
    // way, or the data is not the size the directory says.
    private static byte[] entryBytes(ByteBuffer zip, int base, int length, ZipItem item) {
        long data = dataOffset(zip, base, length, item);
        if (data < 0 || item.size > MAX_CLASS_SIZE || data + item.compressed > length) {
            return null;
        }
        if (item.method == 0) {
            return item.compressed == item.size ? copy(zip, base + (int) data, (int) item.size) : null;
        }
        if (item.method != 8) {
            return null;
        }
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(copy(zip, base + (int) data, (int) item.compressed));
            byte[] bytes = new byte[(int) item.size];
            byte[] past = new byte[1];
            int read = 0;
            boolean padded = false;
            while (!inflater.finished()) {
                int n = read < bytes.length
                        ? inflater.inflate(bytes, read, bytes.length - read)
                        : inflater.inflate(past);
                if (n > 0) {
                    if (read == bytes.length) {
                        // More data than the directory's size.
                        return null;
                    }
                    read += n;
                } else if (inflater.needsInput() && !padded) {
                    // zlib may ask for one byte past a raw deflate stream.
                    inflater.setInput(new byte[1]);
                    padded = true;
                } else {
                    return null;
                }
            }
            return read == bytes.length ? bytes : null;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    private static byte[] copy(ByteBuffer zip, int at, int length) {
        ByteBuffer from = zip.duplicate();
        // Through Buffer: ByteBuffer.position(int) is Java 9's, and this
        // runs on Java 8.
        ((Buffer) from).position(at);
        byte[] bytes = new byte[length];
        from.get(bytes);
        return bytes;
    }

    private static int u16(ByteBuffer zip, int at) {
        return (zip.get(at) & 0xff) | (zip.get(at + 1) & 0xff) << 8;
    }

    private static long u32(ByteBuffer zip, int at) {
        return u16(zip, at) | (long) u16(zip, at + 2) << 16;
    }

    // isBootJar says whether a jar on the classpath is a Spring Boot jar its
    // launcher runs: one with BOOT-INF or WEB-INF classes, or a Start-Class.
    private static boolean isBootJar(File jar) {
        try (JarFile jarFile = new JarFile(jar)) {
            Manifest manifest = jarFile.getManifest();
            return bootLayout(jarFile) != null
                    || (manifest != null && trimmed(manifest.getMainAttributes().getValue("Start-Class")) != null);
        } catch (IOException e) {
            return false;
        }
    }

    // bootLayout is {classes dir, lib dir} of a Spring Boot jar or war, or
    // null for any other jar.
    private static String[] bootLayout(JarFile jarFile) {
        if (hasClassUnder(jarFile, "BOOT-INF/classes/")) {
            return new String[] {"BOOT-INF/classes/", "BOOT-INF/lib/"};
        }
        if (hasClassUnder(jarFile, "WEB-INF/classes/")) {
            return new String[] {"WEB-INF/classes/", "WEB-INF/lib/"};
        }
        return null;
    }

    private static boolean hasClassUnder(JarFile jarFile, String prefix) {
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith(prefix) && name.endsWith(".class")) {
                return true;
            }
        }
        return false;
    }

    // addGroupIds adds the groupId of each
    // META-INF/maven/<groupId>/<artifactId>/pom.properties, and returns how
    // many there are.
    private static int addGroupIds(JarFile jarFile, Set<String> groupIds) {
        int poms = 0;
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.startsWith(MAVEN_DIR) || !name.endsWith("/pom.properties")) {
                continue;
            }
            String[] parts = name.substring(MAVEN_DIR.length()).split("/");
            if (parts.length == 3 && !parts[0].isEmpty()) {
                groupIds.add(parts[0]);
                poms++;
            }
        }
        return poms;
    }

    // classPathFile is the file a manifest Class-Path entry names: a URL
    // relative to the jar's dir ("lib/a%20b.jar"), or null when it is not
    // a local file.
    static File classPathFile(File jar, String entry) {
        if (entry.isEmpty()) {
            return null;
        }
        try {
            URI uri = jar.getAbsoluteFile().getParentFile().toURI().resolve(entry);
            return "file".equals(uri.getScheme()) ? new File(uri) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<File> classpathEntries(String classpath, String userDir) {
        List<File> entries = new ArrayList<>();
        if (classpath == null) {
            return entries;
        }
        for (String part : classpath.split(Pattern.quote(File.pathSeparator))) {
            if (!part.trim().isEmpty()) {
                entries.add(absolute(part.trim(), userDir));
            }
        }
        return entries;
    }

    private static File absolute(String path, String userDir) {
        File file = new File(path);
        return file.isAbsolute() || userDir == null ? file : new File(userDir, path);
    }

    // soleArchive is the jar run with java -jar when its path has a space
    // and the command does not quote it: the classpath is then that jar
    // alone, and the command starts with its path.
    private static File soleArchive(String classpath, String command, String userDir) {
        if (classpath == null || classpath.trim().isEmpty() || classpath.contains(File.pathSeparator)
                || !command.trim().startsWith(classpath.trim())) {
            return null;
        }
        return archiveFile(classpath, userDir);
    }

    // archiveFile is the jar a command starts with, or null when it starts
    // with something else (a class).
    static File archiveFile(String path, String userDir) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        File file = absolute(path.trim(), userDir);
        return file.isFile() && isArchive(file) ? file : null;
    }

    static boolean isArchive(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar")
                || name.endsWith(".war")
                || name.endsWith(".ear")
                || name.endsWith(".zip");
    }

    private static String classFile(String className) {
        return className.replace('.', '/') + ".class";
    }

    private static String trimmed(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    // firstToken is a command's first word, which may be quoted.
    static String firstToken(String command) {
        if (command == null) {
            return "";
        }
        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        char first = trimmed.charAt(0);
        if (first == '"' || first == '\'') {
            int end = trimmed.indexOf(first, 1);
            return end > 0 ? trimmed.substring(1, end) : trimmed.substring(1);
        }
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))) {
            end++;
        }
        return trimmed.substring(0, end);
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
