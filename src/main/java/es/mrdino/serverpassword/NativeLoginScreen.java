package es.mrdino.serverpassword;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.BiConsumer;

/** No modern Paper classes may escape this boundary into the 1.20 JAR. */
public interface NativeLoginScreen {
    record Button(String action, String label, String tooltip) {
        public Button(String action, String label) { this(action, label, null); }
    }
    record Layout(int columns, int buttonWidth, int bodyWidth) {
        public static final Layout KEYPAD = new Layout(3, 32, 248);
        public static final Layout PANEL = new Layout(2, 110, 248);
        public static final Layout KEYBOARD = new Layout(10, 22, 256);
    }
    record View(String title, List<String> lines, List<Button> buttons, Button primary, Layout layout,
                boolean textEntry, String initialText, int maxLength, long revision) { }

    boolean supports(Player player);
    void show(Player player, View view, BiConsumer<String, String> handler);
    void close(Player player);
}
