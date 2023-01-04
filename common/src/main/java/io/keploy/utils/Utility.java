package io.keploy.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Utility {

    private static final Logger logger = LogManager.getLogger(Utility.class);

    private static final String CROSS = new String(Character.toChars(0x274C));

    private static final String WARN = "\u26A0\uFE0F";

    public static String getFileNameFromHeader(String header) {
        String fileName = "";
        String regex = ".*filename=(.*)";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(header);
        if (matcher.matches()) {
            fileName = matcher.group(1);
            fileName = fileName.replaceAll("\"", "");
        }
        return fileName;
    }

    public static String lastFileNameFromDirectory(String path) {
        path = path.trim();
        File dir = new File(path);
        File[] files = dir.listFiles();

        if (files == null) {
            logger.error(CROSS + " no directory found at location:" + path);
            return null;
        }

        if (files.length > 0) {
            Arrays.sort(files);
            File lastFile = files[files.length - 1];
            String fileName = lastFile.getName();
            logger.debug("last file of directory {}: {}", path, fileName);
            return fileName;
        }
        return "";
    }

    public static String getFileNameFromPath(String filePath) {
        if (filePath.isEmpty()) return "";
        filePath = filePath.trim();

        String[] pathSplit = filePath.split("/");
        String fileName = pathSplit[pathSplit.length - 1];
        return fileName;
    }

    public static String getFileExtensionFromPath(String filePath) {

        String fileName = getFileNameFromPath(filePath);
        String[] split = fileName.split("\\.");
        String ext = split[split.length - 1];
        if (split.length == 1) {
            logger.error("no file found at location:" + fileName);
            return "";
        }
        return ext;
    }

    //it resolves the file name with path so that it can be stored in folder without naming conflict
    public static String resolveFileName(String folderPath) {
        folderPath = folderPath.trim();

        List<String> assets = new ArrayList<>();
        File dir = new File(folderPath);
        File[] files = dir.listFiles();

        if (files == null) {
            logger.warn(WARN + " no directory found at location:{}", folderPath);
            logger.warn(WARN + " hence saving in the current user directory");
            return System.getProperty("user.dir") + "/asset-1";
        }

        if (files.length == 0) {
            return folderPath + "/asset-1";
        }

        for (File file : files) {
            boolean isPresent = file.getName().contains("asset-");
            if (isPresent) {
                assets.add(file.getName());
            }
        }

        Collections.sort(assets);

        String lastFileName = assets.get(assets.size() - 1);

        int lastFileCount;

        if (lastFileName != null && !lastFileName.isEmpty()) {
            String ext = Utility.getFileExtensionFromPath(lastFileName);
            int idx = lastFileName.indexOf("." + ext);
            if (idx == -1) {
                logger.debug("no extension found in last file");
                return folderPath + "/asset-0";
            }
            lastFileName = lastFileName.substring(0, idx);
            boolean digit = Character.isDigit(lastFileName.charAt(lastFileName.length() - 1));
            if (digit) {
                lastFileCount = Character.getNumericValue(lastFileName.charAt(lastFileName.length() - 1)) + 1;
                return folderPath + "/asset-" + lastFileCount;
            } else {
                return folderPath + "/asset-1";
            }
        } else if (lastFileName != null) {
            return folderPath + "/asset-1";
        } else {
            return folderPath + "/asset-0";
        }
    }

    public static void createFolder(String folderPath) {
        File folder = new File(folderPath);

        if (!folder.exists()) {
            boolean result = folder.mkdir();
            if (!result) {
                logger.debug("trying again to create a directory at path: {}", folderPath);
                result = folder.mkdirs();
            }
            if (result) {
                logger.debug("new folder created:");
            } else {
                String WARN = "\u26A0\uFE0F";
                logger.warn(WARN + " failed to create assets directory, thus saving files in user directory");
                folderPath = System.getProperty("user.dir");
            }
        } else {
            logger.debug("directory already exists");
        }
    }
}
