package xyz.seaze.seapanel.console;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class ConsoleBuffer extends AbstractAppender {

    public record LogEntry(long id, String time, String level, String message) {}

    private static final int MAX_LINES = 500;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Deque<LogEntry> buffer = new ArrayDeque<>(MAX_LINES);
    private final AtomicLong nextId = new AtomicLong(1);
    private final Set<Consumer<LogEntry>> listeners = ConcurrentHashMap.newKeySet();
    private boolean attached = false;

    public ConsoleBuffer() {
        super("SeaPanelConsoleAppender", null, null, true, Property.EMPTY_ARRAY);
    }

    public void attach() {
        if (attached) return;
        start();
        org.apache.logging.log4j.Logger root = LogManager.getRootLogger();
        if (root instanceof Logger coreLogger) {
            coreLogger.addAppender(this);
            attached = true;
        }
    }

    public void detach() {
        if (!attached) return;
        org.apache.logging.log4j.Logger root = LogManager.getRootLogger();
        if (root instanceof Logger coreLogger) {
            coreLogger.removeAppender(this);
        }
        stop();
        listeners.clear();
        attached = false;
    }

    @Override
    public void append(LogEvent event) {
        String time = TIME_FMT.format(Instant.ofEpochMilli(event.getTimeMillis()));
        String level = event.getLevel() != null ? event.getLevel().name() : "INFO";
        String msg = event.getMessage() != null ? event.getMessage().getFormattedMessage() : "";
        // Strip Minecraft/ANSI color codes for clean web display
        String cleanMsg = msg.replaceAll("\u001B\\[[;\\d]*m", "").replaceAll("§[0-9a-fk-orxA-FK-ORX]", "");

        LogEntry entry = new LogEntry(nextId.getAndIncrement(), time, level, cleanMsg);
        synchronized (buffer) {
            if (buffer.size() >= MAX_LINES) {
                buffer.removeFirst();
            }
            buffer.addLast(entry);
        }

        for (Consumer<LogEntry> listener : listeners) {
            try {
                listener.accept(entry);
            } catch (Exception ignored) {
                // Listener cleanup handled by SSE handler
            }
        }
    }

    public List<LogEntry> getRecent(long sinceId) {
        synchronized (buffer) {
            List<LogEntry> result = new ArrayList<>();
            for (LogEntry entry : buffer) {
                if (entry.id() > sinceId) {
                    result.add(entry);
                }
            }
            return result;
        }
    }

    public void addListener(Consumer<LogEntry> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<LogEntry> listener) {
        listeners.remove(listener);
    }
}
