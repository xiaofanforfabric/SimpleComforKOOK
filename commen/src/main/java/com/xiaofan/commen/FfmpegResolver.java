package com.xiaofan.commen;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Resolves the bundled ffmpeg executable (from resources) by OS: Windows -> ffmpeg.exe, Linux -> ffmpeg.
 * Extracts to the given directory and returns the absolute path.
 */
public final class FfmpegResolver {
    private static final String RESOURCE_WIN = "ffmpeg.exe";
    private static final String RESOURCE_LINUX = "ffmpeg";

    private FfmpegResolver() {}

    /**
     * @param resourceContext Class loaded from the jar that contains ffmpeg/ffmpeg.exe at root (e.g. plugin main class)
     * @param extractToDir    Directory to extract the executable into (e.g. plugin data folder)
     * @param logger          Optional logger
     * @return Absolute path to the ffmpeg executable, or null if resource not found or extraction failed
     */
    public static String getFfmpegPath(Class<?> resourceContext, File extractToDir, Logger logger) {
        boolean win = isWindows();
        String resourceName = win ? RESOURCE_WIN : RESOURCE_LINUX;
        String fileName = resourceName;

        InputStream in = resourceContext.getResourceAsStream("/" + resourceName);
        if (in == null) {
            if (logger != null) {
                logger.warning("[ffmpeg] Bundled resource not found: /" + resourceName);
            }
            return null;
        }

        if (!extractToDir.exists() && !extractToDir.mkdirs()) {
            if (logger != null) {
                logger.warning("[ffmpeg] Cannot create extract directory: " + extractToDir);
            }
            return null;
        }

        File exe = new File(extractToDir, fileName);
        try {
            copyStreamToFile(in, exe);
            if (!win) {
                exe.setExecutable(true, false);
            }
            if (logger != null) {
                logger.info("[ffmpeg] Using bundled executable: " + exe.getAbsolutePath());
            }
            return exe.getAbsolutePath();
        } catch (IOException e) {
            if (logger != null) {
                logger.warning("[ffmpeg] Failed to extract: " + e.getMessage());
            }
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    private static void copyStreamToFile(InputStream in, File out) throws IOException {
        FileOutputStream fos = new FileOutputStream(out);
        try {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                fos.write(buf, 0, r);
            }
        } finally {
            fos.close();
        }
    }
}
