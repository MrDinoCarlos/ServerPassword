package es.mrdino.serverpassword;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

public class ServerPassPlugin extends JavaPlugin {

    private Lang lang;
    private AuthManager authManager;
    private LockdownManager lockdownManager;
    private LoginUI codeUI;
    private StealthManager stealth;
    private FileConfiguration acceptedConfig;
    private UpdateChecker updateChecker;
    private SetupManager setup;
    private volatile boolean protectionReady;
    private PasswordSetupUI passwordSetup;
    private PasswordLogProtection passwordLogs;
    private boolean passwordChangePending;

    @Override public FileConfiguration getConfig() {
        return acceptedConfig == null ? super.getConfig() : acceptedConfig;
    }

    private YamlConfiguration readConfiguration() throws Exception {
        YamlConfiguration candidate = new YamlConfiguration();
        candidate.options().parseComments(true);
        candidate.load(new File(getDataFolder(), "config.yml"));
        return candidate;
    }

    private void writeConfiguration(FileConfiguration candidate) throws Exception {
        ConfigFiles.save(new File(getDataFolder(), "config.yml").toPath(), candidate);
    }

    List<String> reloadSettings() {
        try {
            YamlConfiguration candidate = readConfiguration();
            boolean migrated = PasswordCredential.migrate(candidate);
            boolean updated = ConfigFiles.mergeMissing(candidate, ConfigFiles.bundled(this, "config.yml"));
            List<String> errors = LoginSettings.validate(candidate);
            if (!errors.isEmpty()) return errors;
            if (migrated || updated) writeConfiguration(candidate);
            applyConfiguration(candidate);
            return List.of();
        } catch (Exception failure) {
            return List.of("Configuration could not be loaded: " + failure.getClass().getSimpleName());
        }
    }

    private void applyConfiguration(FileConfiguration candidate) {
        acceptedConfig = candidate;
        if (authManager == null) return;
        boolean changed = authManager.updateCredential(PasswordCredential.read(candidate));
        if (lang != null) lang.reload();
        if (changed) {
            if (passwordSetup != null) passwordSetup.closeAll();
            for (Player player : getServer().getOnlinePlayers()) {
                if (authManager.isPasswordConfigured()) lockdownManager.lock(player);
                else lockdownManager.clear(player);
            }
        }
        codeUI.reload();
        if (setup != null) setup.onConfigurationChanged();
    }

    void changePassword(String plaintext, Consumer<List<String>> completed) {
        if (passwordChangePending) { completed.accept(List.of("A password change is already in progress.")); return; }
        String invalid = LoginSettings.passwordError(getConfig(), plaintext);
        if (invalid != null) { completed.accept(List.of(invalid)); return; }
        String alphabet = getConfig().getString("code-gui.charset", PasswordCredential.LEGACY_ALPHABET);
        passwordChangePending = true;
        try {
            authManager.createCredentialAsync(plaintext, alphabet, credential -> {
                passwordChangePending = false;
                try {
                    YamlConfiguration candidate = new YamlConfiguration();
                    candidate.options().parseComments(true);
                    candidate.loadFromString(getConfig().saveToString());
                    credential.write(candidate);
                    List<String> errors = LoginSettings.validate(candidate);
                    if (!errors.isEmpty()) { completed.accept(errors); return; }
                    writeConfiguration(candidate);
                    applyConfiguration(candidate);
                    completed.accept(List.of());
                } catch (Exception failure) {
                    completed.accept(List.of("Password could not be saved: " + failure.getClass().getSimpleName()));
                }
            });
        } catch (RejectedExecutionException busy) {
            passwordChangePending = false;
            completed.accept(List.of("Password verification is busy. Try again shortly."));
        }
    }

    List<String> changeSettings(Map<String, Object> changes) {
        try {
            YamlConfiguration candidate = new YamlConfiguration();
            candidate.options().parseComments(true);
            candidate.loadFromString(getConfig().saveToString());
            changes.forEach(candidate::set);
            List<String> errors = LoginSettings.validate(candidate);
            if (!errors.isEmpty()) return errors;
            writeConfiguration(candidate);
            applyConfiguration(candidate);
            return List.of();
        } catch (Exception failure) {
            return List.of("Settings could not be saved: " + failure.getClass().getSimpleName());
        }
    }

    @Override
    public void onEnable() {
        protectionReady = false;
        try {
            getServer().getPluginManager().registerEvents(new StartupGuard(() -> protectionReady), this);
            passwordLogs = PasswordLogProtection.install();
            startProtection();
        } catch (RuntimeException | LinkageError failure) {
            stopForProtectionFailure(List.of("Protection initialization failed: " + failure.getClass().getSimpleName()));
        }
    }

    private void startProtection() {
        saveDefaultConfig();
        List<String> errors = reloadSettings();
        if (!errors.isEmpty()) {
            stopForProtectionFailure(errors);
            return;
        }

        lang = new Lang(this);
        authManager = new AuthManager(this, lang);
        setup = new SetupManager(this, authManager, lang);
        stealth = new StealthManager(this);

        lockdownManager = new LockdownManager(this, authManager, lang, stealth);
        codeUI = new LoginUI(this, authManager, lockdownManager, lang);
        lockdownManager.setUi(codeUI);
        passwordSetup = new PasswordSetupUI(this, lang);

        getServer().getPluginManager().registerEvents(new LockdownListener(lockdownManager), this);
        getServer().getPluginManager().registerEvents(PlatformCompat.chatListener(lockdownManager), this);
        JoinListener joins = new JoinListener(authManager, lockdownManager, stealth, setup);
        getServer().getPluginManager().registerEvents(joins, this);


        if (getCommand("serverpass") != null) {
            ServerPassCommand login = new ServerPassCommand(this, lang);
            getCommand("serverpass").setExecutor(login);
            getCommand("serverpass").setTabCompleter(login);
        }
        if (getCommand("spass") != null) {
            AdminCommand admin = new AdminCommand(this, lang);
            getCommand("spass").setExecutor(admin);
            getCommand("spass").setTabCompleter(admin);
        }
        updateChecker = new UpdateChecker(this, authManager, lockdownManager, lang);
        setup.warnConsole();
        if (!getServer().getOnlineMode() && getConfig().getInt("remember-session-minutes", 0) > 0)
            getLogger().warning("Remembered sessions are disabled on offline/proxy backends: every connection must enter the password.");
        for (Player player : getServer().getOnlinePlayers()) joins.initialize(player, null);
        for (Player player : getServer().getOnlinePlayers())
            stealth.hideLockedFrom(player, lockdownManager.getLockedPlayersOnline());
        protectionReady = true;
    }

    private void stopForProtectionFailure(List<String> errors) {
        protectionReady = false;
        errors.forEach(getLogger()::severe);
        getLogger().severe("ServerPassword cannot protect access. Stopping the server; fix the configuration before restarting.");
        // Keeping the guard registered until shutdown avoids an unprotected live server.
        getServer().shutdown();
    }

    @Override
    public void onDisable() {
        protectionReady = false;
        if (passwordSetup != null) passwordSetup.shutdown();
        if (updateChecker != null) updateChecker.shutdown();
        if (codeUI != null) codeUI.shutdown();
        if (lockdownManager != null) lockdownManager.shutdown();
        if (authManager != null) authManager.shutdown();
        if (passwordLogs != null) passwordLogs.close();
    }

    void openLogin(Player player) {
        if (!protectionReady) return;
        if (!authManager.isPasswordConfigured()) { setup.onJoin(player); return; }
        if (authManager.isAuthed(player)) { player.sendMessage(lang.msg(player, "success")); return; }
        lockdownManager.lock(player);
        codeUI.open(player);
    }

    boolean canSetPassword(Player player) {
        return protectionReady && player.hasPermission("serverpassword.admin")
                && (authManager.isPasswordConfigured() ? authManager.isAuthed(player) : getServer().getOnlineMode());
    }

    void openPasswordSetup(org.bukkit.command.CommandSender sender) {
        if (sender instanceof Player player && canSetPassword(player)) passwordSetup.open(player);
        else sender.sendMessage(lang.msg(sender, "secure-setup-console"));
    }

    List<String> importPassword() {
        if (passwordChangePending) return List.of("A password change is already in progress.");
        File source = new File(getDataFolder(), "password-import.yml");
        try {
            YamlConfiguration imported = new YamlConfiguration();
            imported.load(source);
            PasswordCredential credential = PasswordCredential.read(imported);
            if (!credential.configured()) return List.of("The import must contain a hashed credential.");
            YamlConfiguration candidate = new YamlConfiguration();
            candidate.options().parseComments(true);
            candidate.loadFromString(getConfig().saveToString());
            credential.write(candidate);
            List<String> errors = LoginSettings.validate(candidate);
            if (!errors.isEmpty()) return errors;
            writeConfiguration(candidate);
            applyConfiguration(candidate);
            try { java.nio.file.Files.deleteIfExists(source.toPath()); }
            catch (java.io.IOException cleanupFailure) {
                getLogger().warning("Password imported successfully; the hashed import file could not be removed.");
            }
            return List.of();
        } catch (Exception failure) {
            return List.of("Credential import failed: " + failure.getClass().getSimpleName());
        }
    }

    boolean repairProtection(org.bukkit.entity.Player player) {
        if (lockdownManager.isLocked(player) || !authManager.isAuthed(player)) return false;
        player.setInvulnerable(false);
        return true;
    }
}
