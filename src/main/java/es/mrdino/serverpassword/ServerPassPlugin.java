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
            for (Player player : getServer().getOnlinePlayers()) {
                if (authManager.isPasswordConfigured()) lockdownManager.lock(player);
                else lockdownManager.clear(player);
            }
        }
        codeUI.reload();
        if (setup != null) setup.onConfigurationChanged();
    }

    void changePassword(String plaintext, Consumer<List<String>> completed) {
        String invalid = LoginSettings.passwordError(getConfig(), plaintext);
        if (invalid != null) { completed.accept(List.of(invalid)); return; }
        String alphabet = getConfig().getString("code-gui.charset", PasswordCredential.LEGACY_ALPHABET);
        try {
            authManager.createCredentialAsync(plaintext, alphabet, credential -> {
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
        } catch (RejectedExecutionException busy) { completed.accept(List.of("Password verification is busy. Try again shortly.")); }
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
        saveDefaultConfig();
        List<String> errors = reloadSettings();
        if (!errors.isEmpty()) {
            errors.forEach(getLogger()::severe);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        lang = new Lang(this);
        authManager = new AuthManager(this, lang);
        setup = new SetupManager(this, authManager, lang);
        stealth = new StealthManager(this);

        lockdownManager = new LockdownManager(this, authManager, lang, stealth);
        codeUI = new LoginUI(this, authManager, lockdownManager, lang);
        lockdownManager.setUi(codeUI);

        getServer().getPluginManager().registerEvents(new LockdownListener(lockdownManager), this);
        getServer().getPluginManager().registerEvents(PlatformCompat.chatListener(lockdownManager), this);
        getServer().getPluginManager().registerEvents(new JoinListener(authManager, lockdownManager, stealth, setup), this);


        if (getCommand("serverpass") != null) {
            ServerPassCommand login = new ServerPassCommand(this, authManager, lockdownManager, lang);
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
    }

    @Override
    public void onDisable() {
        if (updateChecker != null) updateChecker.shutdown();
        if (codeUI != null) codeUI.shutdown();
        if (lockdownManager != null) lockdownManager.shutdown();
        if (authManager != null) authManager.shutdown();
    }
}
