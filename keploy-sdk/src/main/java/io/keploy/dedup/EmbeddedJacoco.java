package io.keploy.dedup;

import org.jacoco.agent.AgentJar;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * EmbeddedJacoco starts the JaCoCo agent that keploy-sdk.jar carries, so
 * that {@code -javaagent:keploy-sdk.jar} is all an app needs, and has it
 * instrument only the app's own classes.
 * <p>
 * JaCoCo reads includes= only from its own agent options, in its premain:
 * a JaCoCo agent the command attaches gets the options the command gives
 * it, and with none it instruments every class it loads (about 105k of
 * Fineract's 112k, against its 7k own, at about 15 s of startup). So the
 * SDK starts JaCoCo itself, with includes= taking in every class in the
 * app's index ({@link #includes(boolean, Collection)}), and leaves alone a
 * JaCoCo agent the command attaches.
 * <p>
 * JaCoCo is Eclipse Public License 2.0 software
 * (https://github.com/jacoco/jacoco), carried unchanged as the
 * {@code jacocoagent.jar} of {@code org.jacoco:org.jacoco.agent}; see
 * {@code META-INF/THIRD-PARTY-NOTICES.txt} in keploy-sdk.jar.
 */
final class EmbeddedJacoco {

    // A class every JaCoCo agent jar holds.
    static final String RUNTIME_CLASS = "org/jacoco/agent/rt/RT.class";
    private static final String JAVAAGENT = "-javaagent:";
    // A pattern takes in at most this many parts of a class's package, so
    // that an app over many packages needs only a few patterns; a package of
    // fewer than two parts ("Main", "app.Main") is never a pattern, as
    // "com.*" would take in every library under com.
    private static final int PATTERN_PARTS = 3;

    private EmbeddedJacoco() {
    }

    /**
     * attached finds the JVM's own JaCoCo agent: one the command or
     * JAVA_TOOL_OPTIONS attaches with -javaagent. An agent before
     * keploy-sdk.jar is on the system classpath already; one after it is
     * not until its own premain, so it is found by its jar among the JVM's
     * arguments (the RuntimeMXBean's, JAVA_TOOL_OPTIONS' among them). It is
     * null when there is none, else the Implementation-Version its jar's
     * manifest names: "" when it names none, or the agent is in no jar file
     * this can read.
     */
    static String attached(ClassLoader system, List<String> jvmArguments) {
        URL runtime = system.getResource(RUNTIME_CLASS);
        if (runtime != null) {
            return versionOfJarHolding(runtime);
        }
        for (String argument : jvmArguments) {
            if (!argument.startsWith(JAVAAGENT)) {
                continue;
            }
            String path = argument.substring(JAVAAGENT.length());
            int options = path.indexOf('=');
            try (JarFile jar = new JarFile(options < 0 ? path : path.substring(0, options))) {
                if (jar.getEntry(RUNTIME_CLASS) != null) {
                    return versionOf(jar);
                }
            } catch (IOException e) {
                // The JVM cannot load an agent from it either.
            }
        }
        return null;
    }

    // versionOf is the Implementation-Version jar's manifest names, or ""
    // when it names none or its manifest cannot be read: a jar that holds
    // the runtime is an attached agent whatever its manifest says.
    private static String versionOf(JarFile jar) {
        try {
            return implementationVersion(jar.getManifest());
        } catch (IOException e) {
            return "";
        }
    }

    // versionOfJarHolding is the Implementation-Version of the jar file
    // holding the resource at url ("jar:file:/x/jacocoagent.jar!/org/..."),
    // or "" when its manifest names none, url is in no jar file (a class
    // dir), in a jar nested in another ("...app.jar!/lib/x.jar!/org/..."),
    // or the jar cannot be read.
    static String versionOfJarHolding(URL url) {
        String spec = url.toString();
        int entry = spec.indexOf("!/");
        if (!"jar".equals(url.getProtocol()) || entry < 0 || spec.indexOf("!/", entry + 2) >= 0) {
            return "";
        }
        try {
            URI jar = new URI(spec.substring("jar:".length(), entry));
            if (!"file".equalsIgnoreCase(jar.getScheme())) {
                return "";
            }
            try (JarFile file = new JarFile(new File(jar))) {
                return versionOf(file);
            }
        } catch (IOException | URISyntaxException | IllegalArgumentException e) {
            return "";
        }
    }

    // implementationVersion is the Implementation-Version manifest names,
    // or "" when it names none or there is no manifest.
    static String implementationVersion(Manifest manifest) {
        String version = manifest == null ? null : manifest.getMainAttributes().getValue("Implementation-Version");
        return version == null ? "" : version;
    }

    /**
     * includes is JaCoCo's includes= for an index of the app's own classes,
     * given by VM name ("org/acme/web/Foo"): a pattern of the first three
     * parts of each class's package ("org.acme.web.*"), none under another
     * ("org.acme.*" takes in "org.acme.web.*"), and the class's own name for
     * a class in a package of fewer than two parts. Every class given
     * matches it. It is "", so that JaCoCo instruments every class, for an
     * index that is not the app's own classes alone (the whole jar or the
     * whole classpath, which would need a pattern for nearly every library).
     * It is null for an empty index: no class passes its cut, so no JaCoCo
     * agent is started.
     */
    static String includes(boolean appOnly, Collection<String> classNames) {
        if (classNames.isEmpty()) {
            return null;
        }
        if (!appOnly) {
            return "";
        }
        TreeSet<String> patterns = new TreeSet<>();
        for (String vmName : classNames) {
            String name = vmName.replace('/', '.');
            String[] parts = name.split("\\.");
            int packageParts = parts.length - 1;
            if (packageParts < 2) {
                patterns.add(name);
                continue;
            }
            StringBuilder pattern = new StringBuilder();
            for (int i = 0; i < Math.min(packageParts, PATTERN_PARTS); i++) {
                pattern.append(parts[i]).append('.');
            }
            patterns.add(pattern.append('*').toString());
        }
        StringBuilder joined = new StringBuilder();
        for (String pattern : patterns) {
            if (underAnother(pattern, patterns)) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(':');
            }
            joined.append(pattern);
        }
        return joined.toString();
    }

    // underAnother says whether a pattern of three parts ("a.b.c.*") is
    // taken in by the pattern of its first two ("a.b.*").
    // A class's own name has at most two parts, so it is never taken in.
    private static boolean underAnother(String pattern, Collection<String> patterns) {
        String[] parts = pattern.split("\\.");
        return parts.length == PATTERN_PARTS + 1
                && patterns.contains(parts[0] + "." + parts[1] + ".*");
    }

    /**
     * options are the JaCoCo agent options the SDK starts it with: no output
     * of its own (the SDK reads the counters in-process, through JaCoCo's
     * RT.getAgent()), and includes= when there are any.
     */
    static String options(String includes) {
        return includes.isEmpty() ? "output=none" : "output=none,includes=" + includes;
    }

    /**
     * start writes the JaCoCo agent jar into a new temp dir only this user
     * can read, puts it on the system classpath as the JVM does a
     * -javaagent jar, and runs its premain with options. It returns
     * JaCoCo's version as the jar's manifest names it ("0.8.15"; "" when it
     * names none). The jar is removed when the JVM exits, except on
     * Windows, where the JVM holds it open.
     */
    static String start(Instrumentation instrumentation, String options) throws Exception {
        File dir = Files.createTempDirectory("keploy-jacoco-").toFile();
        // Removed in the reverse of this order: the jar, then its dir.
        dir.deleteOnExit();
        File jar = new File(dir, "jacocoagent.jar");
        jar.deleteOnExit();
        try (InputStream input = AgentJar.getResourceAsStream()) {
            Files.copy(input, jar.toPath());
        }
        String premain;
        String version;
        try (JarFile jarFile = new JarFile(jar)) {
            Manifest manifest = jarFile.getManifest();
            Attributes attributes = manifest == null ? new Attributes() : manifest.getMainAttributes();
            premain = attributes.getValue("Premain-Class");
            version = implementationVersion(manifest);
            if (premain == null) {
                throw new IllegalStateException("the JaCoCo agent jar names no Premain-Class");
            }
            instrumentation.appendToSystemClassLoaderSearch(jarFile);
        }
        Class.forName(premain, true, ClassLoader.getSystemClassLoader())
                .getMethod("premain", String.class, Instrumentation.class)
                .invoke(null, options, instrumentation);
        return version;
    }
}
