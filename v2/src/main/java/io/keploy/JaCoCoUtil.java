package io.keploy;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class JaCoCoUtil {

    public static void downloadAndExtractJaCoCoBinaries(String version, String resourceDir) throws Exception {
        Path cliPath = Paths.get(resourceDir, "jacococli.jar");
        Path agentPath = Paths.get(resourceDir, "jacocoagent.jar");
        Files.createDirectories(cliPath.getParent());
        Files.createDirectories(agentPath.getParent());

        if (Files.exists(cliPath) && Files.exists(agentPath)) {
            System.out.println("JaCoCo binaries already exist.");
            return;
        }

        String downloadUrl = "https://github.com/jacoco/jacoco/releases/download/v" + version + "/jacoco-" + version + ".zip";
        System.out.println("Download url: " + downloadUrl);

        try (InputStream inputStream = new URL(downloadUrl).openStream();
             ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
                System.out.println("Entered open string ");

            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.getName().endsWith("jacococli.jar")) {

                    Files.copy(zipInputStream, cliPath, StandardCopyOption.REPLACE_EXISTING);

                } else if (entry.getName().endsWith("jacocoagent.jar")) {

                    Files.copy(zipInputStream, agentPath, StandardCopyOption.REPLACE_EXISTING);

                }

                if (Files.exists(cliPath) && Files.exists(agentPath)) {

                    break; // Both binaries extracted, no need to continue
                }
            }
        }

        if (!Files.exists(cliPath) || !Files.exists(agentPath)) {
            System.out.println("cant ffind files " + cliPath + "  " + agentPath);

            throw new IllegalStateException("Failed to find JaCoCo binaries in the distribution.");
        }

    }

    public static void main(String[] args) {
        System.out.println("initiated jacoco binary download");
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected two arguments: version and resourceDir");
        }
        String version = args[0];
        String resourceDir = args[1];
        try {
            downloadAndExtractJaCoCoBinaries(version, resourceDir);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}