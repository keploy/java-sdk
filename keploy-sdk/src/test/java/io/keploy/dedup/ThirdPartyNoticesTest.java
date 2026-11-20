package io.keploy.dedup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.Test;

/**
 * keploy-sdk.jar carries JaCoCo (EPL-2.0), ASM (BSD 3-Clause), Gson and
 * junixsocket (Apache 2.0). META-INF/THIRD-PARTY-NOTICES.txt names each in
 * the version the build carries and quotes the attributions their licenses
 * ask for, and the license texts it points to sit next to it. A dependency
 * bumped without its notice fails here: update
 * keploy-sdk/src/main/resources/META-INF/THIRD-PARTY-NOTICES.txt.
 * scripts/smoke-javaagent.sh checks the built jar holds them.
 */
public class ThirdPartyNoticesTest {

    private static final String NOTICES = "META-INF/THIRD-PARTY-NOTICES.txt";

    @Test
    public void noticesNameTheVersionsTheJarCarries() throws IOException {
        String notices = flat(read(noticesUrl()));

        String jacoco = mavenVersion("org.jacoco", "org.jacoco.core");
        assertContains(notices, "JaCoCo " + jacoco + " - Eclipse Public License 2.0");
        assertContains(notices, "https://github.com/jacoco/jacoco, tag v" + jacoco);
        assertContains(notices, "org.jacoco:org.jacoco.core:" + jacoco + ":sources");
        assertContains(notices, "the classes of org.jacoco:org.jacoco.core:" + jacoco + ",");
        String agent = mavenVersion("org.jacoco", "org.jacoco.agent");
        assertContains(notices, "- jacocoagent.jar, unchanged, from org.jacoco:org.jacoco.agent:"
                + agent + ";");
        // keploy-sdk.jar carries AgentJar from the same artifact, under its own
        // name, outside jacocoagent.jar.
        assertContains(notices, "- the class " + org.jacoco.agent.AgentJar.class.getName()
                + " and its package-info, unchanged and not renamed, from"
                + " org.jacoco:org.jacoco.agent:" + agent + ",");

        String asm = org.objectweb.asm.ClassReader.class.getPackage().getImplementationVersion();
        assertNotNull("ASM's jar names no Implementation-Version", asm);
        assertEquals("asm-commons", asm,
                org.objectweb.asm.commons.Remapper.class.getPackage().getImplementationVersion());
        assertEquals("asm-tree", asm,
                org.objectweb.asm.tree.ClassNode.class.getPackage().getImplementationVersion());
        assertContains(notices, "ASM " + asm + " - BSD 3-Clause License");
        assertContains(notices, "asm-commons and asm-tree " + asm + ",");

        String gson = mavenVersion("com.google.code.gson", "gson");
        assertContains(notices, "Gson " + gson + " - Apache License 2.0");
        assertContains(notices, "tag gson-parent-" + gson);

        String junixsocket = mavenVersion("com.kohlschutter.junixsocket", "junixsocket-common");
        assertEquals("junixsocket-native-common", junixsocket,
                mavenVersion("com.kohlschutter.junixsocket", "junixsocket-native-common"));
        assertContains(notices, "junixsocket " + junixsocket + " - Apache License 2.0");
        assertContains(notices, "tag junixsocket-" + junixsocket);
        assertContains(notices, "junixsocket-native-common " + junixsocket + ",");
    }

    @Test
    public void licenseTextsSitNextToTheNotices() throws IOException {
        URL notices = noticesUrl();
        String text = read(notices);
        assertContains(flat(text), "keploy-sdk's own code is under the Apache License 2.0"
                + " (META-INF/LICENSE in the jar).");
        assertContains(text, "Apache License 2.0 (META-INF/LICENSE in the jar)");
        assertContains(text, "Eclipse Public License 2.0 (META-INF/LICENSE-EPL-2.0.txt in");

        String apache = read(new URL(notices, "LICENSE"));
        assertTrue("META-INF/LICENSE is not the Apache License 2.0",
                apache.trim().startsWith("Apache License\n"));
        assertContains(apache, "Version 2.0, January 2004");
        assertContains(apache, "4. Redistribution.");

        String epl = read(new URL(notices, "LICENSE-EPL-2.0.txt"));
        assertTrue("META-INF/LICENSE-EPL-2.0.txt is not the EPL-2.0",
                epl.startsWith("Eclipse Public License - v 2.0\n"));
        assertContains(epl, "3.1 If a Contributor Distributes the Program in any form, then:");
        assertContains(epl, "Exhibit A - Form of Secondary Licenses Notice");
    }

    // The texts the licenses ask a copy to carry: JaCoCo's copyright line,
    // ASM's copyright, conditions and disclaimer (BSD 3-Clause, clauses 1 and
    // 2), Gson's copyright line (Apache 2.0, 4(c)) and junixsocket's NOTICE
    // (4(d)).
    @Test
    public void noticesQuoteTheAttributionsTheLicensesAskFor() throws IOException {
        String notices = flat(read(noticesUrl()));
        assertContains(notices, "Copyright (c) 2009, 2026 Mountainminds GmbH & Co. KG and Contributors");

        assertContains(notices, "ASM: a very small and fast Java bytecode manipulation framework"
                + " Copyright (c) 2000-2011 INRIA, France Telecom All rights reserved.");
        assertContains(notices, "1. Redistributions of source code must retain the above copyright"
                + " notice, this list of conditions and the following disclaimer.");
        assertContains(notices, "2. Redistributions in binary form must reproduce the above copyright"
                + " notice, this list of conditions and the following disclaimer in the"
                + " documentation and/or other materials provided with the distribution.");
        assertContains(notices, "3. Neither the name of the copyright holders nor the names of its"
                + " contributors may be used to endorse or promote products derived from"
                + " this software without specific prior written permission.");
        assertContains(notices, "THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS \"AS IS\"");
        assertContains(notices, "IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE"
                + " LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR"
                + " CONSEQUENTIAL DAMAGES");
        assertContains(notices, "EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.");

        assertContains(notices, "Gson " + mavenVersion("com.google.code.gson", "gson")
                + " - Apache License 2.0 (META-INF/LICENSE in the jar) Copyright (C) 2008 Google Inc.");

        assertContains(notices, "Its NOTICE file reads: junixsocket Copyright 2009-2024 Christian"
                + " Kohlsch\u00fctter Licensed under the Apache License, Version 2.0 (the \"License\");");
        assertContains(notices, "See the License for the specific language governing permissions and"
                + " limitations under the License.");
    }

    private static URL noticesUrl() {
        URL url = ThirdPartyNoticesTest.class.getClassLoader().getResource(NOTICES);
        assertNotNull(NOTICES + " is not on the class path", url);
        return url;
    }

    // mavenVersion is the version in the pom.properties a dependency's jar
    // holds: the version the build resolved, so the version the jar carries.
    private static String mavenVersion(String group, String artifact) throws IOException {
        String path = "META-INF/maven/" + group + "/" + artifact + "/pom.properties";
        URL url = ThirdPartyNoticesTest.class.getClassLoader().getResource(path);
        assertNotNull(path + " is not on the class path", url);
        Properties props = new Properties();
        try (InputStream in = url.openStream()) {
            props.load(in);
        }
        String version = props.getProperty("version");
        assertNotNull(path + " names no version", version);
        return version;
    }

    private static String read(URL url) throws IOException {
        try (InputStream in = url.openStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    // flat folds each run of white space into one space, so that a phrase
    // the notices wrap across lines matches.
    private static String flat(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static void assertContains(String text, String want) {
        assertTrue("THIRD-PARTY-NOTICES.txt or a license text lacks \"" + want
                + "\": update keploy-sdk/src/main/resources/META-INF/", text.contains(want));
    }
}
