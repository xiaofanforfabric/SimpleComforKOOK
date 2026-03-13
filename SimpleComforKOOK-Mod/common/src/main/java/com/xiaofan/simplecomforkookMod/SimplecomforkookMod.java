package com.xiaofan.simplecomforkookMod;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

public final class SimplecomforkookMod {
    public static final String MOD_ID = "simplecomforkook-mod";

    private static SimpleComforKOOKCore CORE;

    public static void init() {
        Logger logger = Logger.getLogger("SimplecomforkookMod");
        Path jarPath;
        try {
            jarPath = Paths.get(SimplecomforkookMod.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (URISyntaxException | NullPointerException e) {
            logger.severe("Failed to resolve mod jar location: " + e.getMessage());
            return;
        }

        CORE = new SimpleComforKOOKCore(logger, jarPath);
        CORE.start();
    }

    /**
     * 可选的关闭入口，由具体平台在合适的生命周期里调用。
     */
    public static void shutdown() {
        if (CORE != null) {
            CORE.stop();
            CORE = null;
        }
    }
}
