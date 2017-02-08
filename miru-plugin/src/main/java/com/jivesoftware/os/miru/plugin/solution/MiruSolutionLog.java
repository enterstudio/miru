package com.jivesoftware.os.miru.plugin.solution;

import com.jivesoftware.os.mlogger.core.MessageFormatter;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author jonathan.colt
 */
public class MiruSolutionLog {

    private static final MetricLogger METRIC_LOGGER = MetricLoggerFactory.getLogger();

    private final MiruSolutionLogLevel level;
    private final List<String> log = new ArrayList<>();

    public MiruSolutionLog(MiruSolutionLogLevel level) {
        this.level = level;
    }

    public MiruSolutionLogLevel getLevel() {
        return level;
    }

    public boolean isLogLevelEnabled(MiruSolutionLogLevel checkLevel) {
        return level.ordinal() <= checkLevel.ordinal();
    }

    public void log(MiruSolutionLogLevel atLevel, String message) {
        if (isLogLevelEnabled(atLevel)) {
            log.add(System.currentTimeMillis() + " " + message);
            METRIC_LOGGER.debug(message);
        } else {
            METRIC_LOGGER.trace(message);
        }
    }

    public void log(MiruSolutionLogLevel atLevel, String message, Object... args) {
        if (isLogLevelEnabled(atLevel)) {
            log.add(System.currentTimeMillis() + " " + MessageFormatter.format(message, args));
            METRIC_LOGGER.debug(message, args);
        } else {
            METRIC_LOGGER.trace(message, args);
        }
    }

    public List<String> asList() {
        return log;
    }

    public void clear() {
        log.clear();
    }

    public void append(MiruSolutionLog append) {
        log.addAll(append.asList());
    }
}
