package xyz.seaze.seapanel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.seaze.seapanel.auth.AuthManager;
import xyz.seaze.seapanel.console.ConsoleBuffer;
import xyz.seaze.seapanel.service.PluginSecurityService;
import xyz.seaze.seapanel.service.ServerService;
import xyz.seaze.seapanel.web.WebDashboardServer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class SeaPanelPlugin extends JavaPlugin {

    private ConsoleBuffer consoleBuffer;
    private AuthManager authManager;
    private ServerService serverService;
    private WebDashboardServer webServer;

    private String host;
    private int port;
    private String password;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        consoleBuffer = new ConsoleBuffer();
        consoleBuffer.attach();

        serverService = new ServerService(this);
        PluginSecurityService pluginSecurityService = new PluginSecurityService(this);
        int sessionHours = getConfig().getInt("web.session-hours", 24);
        String serverName = getConfig().getString("web.server-name", "Minecraft Server");

        authManager = new AuthManager(password, sessionHours);
        webServer = new WebDashboardServer(
                host, port, serverName, authManager, consoleBuffer, serverService, pluginSecurityService, getLogger()
        );

        try {
            webServer.start();
            printStartupBanner();
        } catch (Exception e) {
            getLogger().severe("Failed to start SeaPanel web server on " + host + ":" + port + " -> " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop();
        }
        if (consoleBuffer != null) {
            consoleBuffer.detach();
        }
        getLogger().info("SeaPanel stopped.");
    }

    private void loadSettings() {
        reloadConfig();
        host = getConfig().getString("web.host", "0.0.0.0");
        port = getConfig().getInt("web.port", 8080);
        password = getConfig().getString("web.password", "");

        if (password == null || password.isBlank()) {
            AuthManager temp = new AuthManager("", 24);
            password = temp.generateRandomPassword(12);
            getConfig().set("web.password", password);
            saveConfig();
            getLogger().info("Generated a new SeaPanel password and saved it to config.yml.");
        }
    }

    private void printStartupBanner() {
        List<String> ips = detectLocalIps();
        getLogger().info("==========================================");
        getLogger().info("  SeaPanel Web Dashboard is running!");
        getLogger().info("  Local:    http://localhost:" + port);
        for (String ip : ips) {
            getLogger().info("  Network:  http://" + ip + ":" + port);
        }
        getLogger().info("  Password: " + password);
        getLogger().info("==========================================");
    }

    private List<String> detectLocalIps() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        result.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("seapanel")) {
            return false;
        }
        if (!sender.hasPermission("seapanel.admin")) {
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            sender.sendMessage(Component.text("SeaPanel Web Dashboard", NamedTextColor.WHITE));
            sender.sendMessage(Component.text("Local: http://localhost:" + port, NamedTextColor.GRAY));
            for (String ip : detectLocalIps()) {
                sender.sendMessage(Component.text("Network: http://" + ip + ":" + port, NamedTextColor.GREEN));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            int sessionHours = getConfig().getInt("web.session-hours", 24);
            String serverName = getConfig().getString("web.server-name", "Minecraft Server");
            authManager.updateConfig(password, sessionHours);
            webServer.setServerName(serverName);
            sender.sendMessage(Component.text("SeaPanel configuration reloaded.", NamedTextColor.GREEN));
            return true;
        }

        if (args[0].equalsIgnoreCase("password") && args.length >= 2) {
            password = args[1];
            getConfig().set("web.password", password);
            saveConfig();
            authManager.updateConfig(password, getConfig().getInt("web.session-hours", 24));
            authManager.clearAllSessions();
            sender.sendMessage(Component.text("SeaPanel password updated and existing sessions cleared.", NamedTextColor.GREEN));
            return true;
        }

        sender.sendMessage(Component.text("Usage: /seapanel <info|reload|password <new>>", NamedTextColor.YELLOW));
        return true;
    }
}
