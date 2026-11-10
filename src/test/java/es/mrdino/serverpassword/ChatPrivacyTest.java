package es.mrdino.serverpassword;

import java.util.HashSet;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatPrivacyTest {
    @Test void lockedChatIsCancelledClearedAndNeverForwardedAsAPassword() throws Exception {
        var player = mock(Player.class);
        var lock = mock(LockdownManager.class);
        when(lock.isLocked(player)).thenReturn(true);
        var listener = new LegacyPlatformAdapter().chatListener(lock);
        var recipients = new HashSet<Player>(); recipients.add(mock(Player.class));
        var event = new AsyncPlayerChatEvent(false, player, "never-submit-this-secret", recipients);
        listener.getClass().getMethod("onChat", AsyncPlayerChatEvent.class).invoke(listener, event);
        assertTrue(event.isCancelled());
        assertEquals("", event.getMessage());
        assertTrue(event.getRecipients().isEmpty());
        verify(lock, never()).handleChat(any(), anyString());
    }

    @Test void immutableRecipientsCannotPreventCancellationOfLockedChat() throws Exception {
        var player = mock(Player.class);
        var lock = mock(LockdownManager.class);
        when(lock.isLocked(player)).thenReturn(true);
        var listener = new LegacyPlatformAdapter().chatListener(lock);
        var event = new AsyncPlayerChatEvent(false, player, "secret", Set.of(mock(Player.class)));
        listener.getClass().getMethod("onChat", AsyncPlayerChatEvent.class).invoke(listener, event);
        assertTrue(event.isCancelled());
        assertEquals("", event.getMessage());
        verify(lock, never()).handleChat(any(), anyString());
    }

    @Test void authenticatedChatIsUnaffected() throws Exception {
        var player = mock(Player.class);
        var lock = mock(LockdownManager.class);
        var listener = new LegacyPlatformAdapter().chatListener(lock);
        var event = new AsyncPlayerChatEvent(false, player, "ordinary public chat", new HashSet<>());
        listener.getClass().getMethod("onChat", AsyncPlayerChatEvent.class).invoke(listener, event);
        assertFalse(event.isCancelled());
        assertEquals("ordinary public chat", event.getMessage());
    }
}
