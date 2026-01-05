package es.mrdino.serverpassword;

import org.bukkit.plugin.java.JavaPlugin;

public class ServerPassPlugin extends JavaPlugin {

    private Lang lang;
    private AuthManager authManager;
    private LockdownManager lockdownManager;
    private CodePasswordUI codeUI;
    private StealthManager stealth;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        lang = new Lang(this);
        authManager = new AuthManager(this, lang);
        stealth = new StealthManager(this);

        lockdownManager = new LockdownManager(this, authManager, lang, stealth);
        codeUI = new CodePasswordUI(this, authManager, lockdownManager, lang);
        lockdownManager.setUi(codeUI);

        getServer().getPluginManager().registerEvents(codeUI, this);
        getServer().getPluginManager().registerEvents(new LockdownListener(lockdownManager), this);
        getServer().getPluginManager().registerEvents(new JoinListener(this, authManager, lockdownManager, stealth), this);


        getCommand("spass").setExecutor(new AdminCommand(this, authManager, lang));


    }

    @Override
    public void onDisable() {
        authManager.shutdown();
    }
}
