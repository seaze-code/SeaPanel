package xyz.seaze.seapanel.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class PluginSecurityService {

    private record Rule(String id, String severity, String[] anyPatterns, String[] requiredContext, String description) {}

    // Ignore shaded third-party libraries to avoid false positives
    private static final List<String> SAFE_LIBRARY_PREFIXES = List.of(
            "io/netty/", "com/google/", "org/apache/", "org/slf4j/", "ch/qos/logback/",
            "kotlin/", "kotlinx/", "net/kyori/", "org/yaml/", "com/zaxxer/",
            "org/bstats/", "org/sqlite/", "com/mysql/", "org/h2/", "org/mariadb/",
            "org/postgresql/", "com/fasterxml/", "org/json/", "org/jetbrains/",
            "org/intellij/", "org/checkerframework/", "javax/", "jakarta/",
            "org/objectweb/asm/", "javassist/", "io/papermc/lib/", "com/github/benmanes/"
    );

    private static final List<Rule> RULES = List.of(
            new Rule(
                    "DISCORD_WEBHOOK",
                    "HIGH",
                    new String[]{"discord.com/api/webhooks/", "discordapp.com/api/webhooks/"},
                    null,
                    "Hardcoded Discord Webhook URL (common token/data exfiltration vector)"
            ),
            new Rule(
                    "SHELL_EXEC",
                    "HIGH",
                    new String[]{"/bin/sh", "/bin/bash", "cmd.exe", "powershell.exe", "powershell -"},
                    new String[]{"ProcessBuilder", "getRuntime", "exec"},
                    "Executes OS shell commands (cmd / bash / powershell)"
            ),
            new Rule(
                    "REMOTE_PAYLOAD_URL",
                    "HIGH",
                    new String[]{"pastebin.com/raw/", "hastebin.com/raw/", "paste.ee/r/", "transfer.sh/"},
                    null,
                    "Fetches raw payloads from paste/file-drop services"
            ),
            new Rule(
                    "REMOTE_CLASSLOADER",
                    "MEDIUM",
                    new String[]{"java/net/URLClassLoader"},
                    new String[]{"http://", "https://"},
                    "Loads external classes/JARs over HTTP(S) at runtime"
            ),
            new Rule(
                    "BYTECODE_INJECTION",
                    "MEDIUM",
                    new String[]{"java/lang/instrument/Instrumentation", "VirtualMachine.attach"},
                    null,
                    "Uses JVM Attach / Instrumentation API (can modify running server classes)"
            ),
            new Rule(
                    "PROCESS_SPAWN",
                    "MEDIUM",
                    new String[]{"java/lang/ProcessBuilder"},
                    null,
                    "Spawns an external OS process (verify if intentional)"
            )
    );

    private static final Set<String> EDITABLE_EXT = Set.of(
            ".yml", ".yaml", ".json", ".toml", ".properties", ".txt", ".conf", ".cfg"
    );

    private static final List<String> SERVER_CONFIG_FILES = List.of(
            "server.properties", "bukkit.yml", "spigot.yml", "commands.yml",
            "config/paper-global.yml", "config/paper-world-defaults.yml"
    );

    private final Plugin ownerPlugin;

    public PluginSecurityService(Plugin ownerPlugin) {
        this.ownerPlugin = ownerPlugin;
    }

    public JsonArray listPlugins() {
        JsonArray arr = new JsonArray();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            PluginDescriptionFile desc = p.getDescription();
            JsonObject obj = new JsonObject();
            obj.addProperty("name", p.getName());
            obj.addProperty("version", desc.getVersion());
            obj.addProperty("enabled", p.isEnabled());
            obj.addProperty("description", desc.getDescription() != null ? desc.getDescription() : "");
            obj.addProperty("authors", String.join(", ", desc.getAuthors()));

            File jarFile = getPluginJar(p);
            obj.addProperty("jarName", jarFile != null ? jarFile.getName() : "");
            obj.addProperty("sizeKb", jarFile != null ? Math.max(1, jarFile.length() / 1024) : 0);
            obj.addProperty("hasDataFolder", p.getDataFolder().exists() && p.getDataFolder().isDirectory());
            arr.add(obj);
        }
        return arr;
    }

    public boolean togglePlugin(String pluginName) {
        if (ownerPlugin.getName().equalsIgnoreCase(pluginName)) {
            throw new IllegalArgumentException("Cannot disable SeaPanel from the web dashboard");
        }
        Plugin target = Bukkit.getPluginManager().getPlugin(pluginName);
        if (target == null) {
            throw new IllegalArgumentException("Plugin not found: " + pluginName);
        }
        if (target.isEnabled()) {
            Bukkit.getPluginManager().disablePlugin(target);
            return false;
        } else {
            Bukkit.getPluginManager().enablePlugin(target);
            return true;
        }
    }

    public JsonObject scanPlugin(String pluginName) throws IOException {
        Plugin target = Bukkit.getPluginManager().getPlugin(pluginName);
        if (target == null) {
            throw new IllegalArgumentException("Plugin not found: " + pluginName);
        }
        File jarFile = getPluginJar(target);
        if (jarFile == null || !jarFile.exists()) {
            throw new IOException("Could not locate JAR file for " + pluginName);
        }
        return scanJarFile(target.getName(), jarFile);
    }

    public JsonArray scanAllPlugins() {
        JsonArray results = new JsonArray();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            File jarFile = getPluginJar(p);
            if (jarFile != null && jarFile.exists()) {
                try {
                    results.add(scanJarFile(p.getName(), jarFile));
                } catch (IOException e) {
                    JsonObject err = new JsonObject();
                    err.addProperty("plugin", p.getName());
                    err.addProperty("verdict", "ERROR");
                    err.addProperty("error", e.getMessage());
                    results.add(err);
                }
            }
        }
        return results;
    }

    private boolean isShadedLibrary(String entryName) {
        for (String prefix : SAFE_LIBRARY_PREFIXES) {
            if (entryName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private JsonObject scanJarFile(String pluginName, File jarFile) throws IOException {
        Map<String, List<String>> hitsByRule = new LinkedHashMap<>();
        int classCount = 0;

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jarFile.toPath()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String entryName = entry.getName();
                if (!entryName.endsWith(".class") || isShadedLibrary(entryName)) continue;

                classCount++;
                byte[] bytes = readLimited(zis, 2 * 1024 * 1024);
                String content = new String(bytes, StandardCharsets.ISO_8859_1);

                for (Rule rule : RULES) {
                    boolean matchedPrimary = false;
                    for (String p : rule.anyPatterns()) {
                        if (content.contains(p)) {
                            matchedPrimary = true;
                            break;
                        }
                    }
                    if (!matchedPrimary) continue;

                    if (rule.requiredContext() != null) {
                        boolean matchedCtx = false;
                        for (String ctx : rule.requiredContext()) {
                            if (content.contains(ctx)) {
                                matchedCtx = true;
                                break;
                            }
                        }
                        if (!matchedCtx) continue;
                    }

                    hitsByRule.computeIfAbsent(rule.id(), k -> new ArrayList<>());
                    List<String> classes = hitsByRule.get(rule.id());
                    if (classes.size() < 8) {
                        classes.add(entryName);
                    }
                }
            }
        }

        int highCount = 0;
        int medCount = 0;
        JsonArray findings = new JsonArray();

        for (Rule rule : RULES) {
            List<String> matchedClasses = hitsByRule.get(rule.id());
            if (matchedClasses == null || matchedClasses.isEmpty()) continue;

            if ("HIGH".equals(rule.severity())) highCount++;
            else medCount++;

            JsonObject f = new JsonObject();
            f.addProperty("id", rule.id());
            f.addProperty("severity", rule.severity());
            f.addProperty("description", rule.description());
            JsonArray clsArr = new JsonArray();
            for (String c : matchedClasses) clsArr.add(c);
            f.add("classes", clsArr);
            findings.add(f);
        }

        String verdict;
        if (highCount >= 1) {
            verdict = "HIGH_RISK";
        } else if (medCount >= 2) {
            verdict = "SUSPICIOUS";
        } else if (medCount == 1) {
            verdict = "LOW_RISK";
        } else {
            verdict = "CLEAN";
        }

        JsonObject out = new JsonObject();
        out.addProperty("plugin", pluginName);
        out.addProperty("jarName", jarFile.getName());
        out.addProperty("classCount", classCount);
        out.addProperty("verdict", verdict);
        out.addProperty("highCount", highCount);
        out.addProperty("mediumCount", medCount);
        out.addProperty("lowCount", 0);
        out.add("findings", findings);
        return out;
    }

    public JsonArray listConfigFiles(String scope) throws IOException {
        JsonArray arr = new JsonArray();
        if ("_server".equals(scope)) {
            Path root = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
            for (String rel : SERVER_CONFIG_FILES) {
                Path p = root.resolve(rel);
                if (Files.isRegularFile(p)) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("path", rel);
                    obj.addProperty("sizeBytes", Files.size(p));
                    arr.add(obj);
                }
            }
            return arr;
        }

        Plugin target = Bukkit.getPluginManager().getPlugin(scope);
        if (target == null) {
            throw new IllegalArgumentException("Plugin not found: " + scope);
        }
        Path folder = target.getDataFolder().toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(folder)) {
            return arr;
        }

        try (Stream<Path> walk = Files.walk(folder, 4)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> isEditable(p.getFileName().toString()))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(100)
                    .forEach(p -> {
                        try {
                            JsonObject obj = new JsonObject();
                            obj.addProperty("path", folder.relativize(p).toString().replace('\\', '/'));
                            obj.addProperty("sizeBytes", Files.size(p));
                            arr.add(obj);
                        } catch (IOException ignored) {}
                    });
        }
        return arr;
    }

    public String readConfigFile(String scope, String relPath) throws IOException {
        Path resolved = resolveSafeConfigPath(scope, relPath);
        if (!Files.isRegularFile(resolved)) {
            throw new IOException("Config file not found: " + relPath);
        }
        if (Files.size(resolved) > 1024 * 1024) {
            throw new IOException("File too large to edit in browser (>1MB)");
        }
        return Files.readString(resolved, StandardCharsets.UTF_8);
    }

    public void writeConfigFile(String scope, String relPath, String content) throws IOException {
        Path resolved = resolveSafeConfigPath(scope, relPath);
        Files.createDirectories(resolved.getParent());
        Files.writeString(resolved, content, StandardCharsets.UTF_8);
    }

    private Path resolveSafeConfigPath(String scope, String relPath) {
        if (relPath == null || relPath.isBlank() || relPath.contains("..")) {
            throw new SecurityException("Invalid file path");
        }
        if ("_server".equals(scope)) {
            if (!SERVER_CONFIG_FILES.contains(relPath)) {
                throw new SecurityException("File not in allowed server config list");
            }
            Path root = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
            return root.resolve(relPath).normalize();
        }

        Plugin target = Bukkit.getPluginManager().getPlugin(scope);
        if (target == null) {
            throw new IllegalArgumentException("Plugin not found: " + scope);
        }
        if (!isEditable(relPath)) {
            throw new SecurityException("Unsupported file extension");
        }
        Path base = target.getDataFolder().toPath().toAbsolutePath().normalize();
        Path resolved = base.resolve(relPath).normalize();
        if (!resolved.startsWith(base)) {
            throw new SecurityException("Path traversal blocked");
        }
        return resolved;
    }

    private boolean isEditable(String filename) {
        String lower = filename.toLowerCase();
        for (String ext : EDITABLE_EXT) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private File getPluginJar(Plugin plugin) {
        try {
            var location = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                return new File(location.toURI());
            }
        } catch (Exception ignored) {}
        return null;
    }

    private byte[] readLimited(InputStream is, int maxBytes) throws IOException {
        byte[] buf = new byte[8192];
        int total = 0;
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = is.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) break;
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }
}
