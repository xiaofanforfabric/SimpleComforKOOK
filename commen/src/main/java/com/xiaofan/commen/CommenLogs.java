package com.xiaofan.commen;

import java.nio.file.Path;
import java.util.logging.Logger;

public final class CommenLogs {
    private CommenLogs() {}

    public static void logCurrentJarLocation(Logger logger, Path jarPath) {
        if (logger == null) {
            return;
        }
        if (jarPath == null) {
            logger.info("Plugin jar path: <null>");
            return;
        }

        Path jarAbsolutePath = jarPath.toAbsolutePath().normalize();
        logger.info("jar的绝对路径: " + jarAbsolutePath);
        Path parent = jarAbsolutePath.getParent();
        if (parent != null) {
            logger.info("jar的目录: " + parent);
        }
    }
}

