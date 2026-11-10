package es.mrdino.serverpassword;

import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import io.papermc.paper.registry.data.dialog.type.MultiActionType;
import io.papermc.paper.registry.data.dialog.type.NoticeType;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the actual Paper implementation and service provider, without starting a server. */
class PaperDialogScreenTest {
    @Test void reproducesThePaperConstraintThatPreviouslyPreventedLogin() {
        assertThrows(IllegalArgumentException.class, () -> DialogBase.builder(Component.text("Login"))
                .afterAction(DialogBase.DialogAfterAction.NONE).build());
    }

    @Test void keypadScreenCanRemainOpenWithoutPausingOrAllowingEscape() {
        var view = new NativeLoginScreen.View("Login", List.of("PIN: ****", "Time: 30s"),
                List.of(new NativeLoginScreen.Button("DIGIT:0", "0")), new NativeLoginScreen.Button("SUBMIT", "Confirm"),
                NativeLoginScreen.Layout.KEYPAD, false, "", 4, 0);
        DialogBase base = assertDoesNotThrow(() -> PaperDialogScreen.createBase(view));
        assertFalse(base.pause());
        assertFalse(base.canCloseWithEscape());
        assertEquals(DialogBase.DialogAfterAction.NONE, base.afterAction());
        assertEquals(1, base.body().size());
        var body = assertInstanceOf(PlainMessageDialogBody.class, base.body().getFirst());
        assertEquals(248, body.width());
        assertEquals(Component.text("PIN: ****\nTime: 30s"), body.contents());
        assertTrue(base.inputs().isEmpty());
        var type = assertInstanceOf(MultiActionType.class, assertDoesNotThrow(() -> PaperDialogScreen.createType(view, "test-screen")));
        assertEquals(3, type.columns());
        assertEquals(32, type.actions().getFirst().width());
        assertNotNull(type.exitAction());
        assertEquals(100, type.exitAction().width());
    }

    @Test void unmaskedFieldsOrInitialPasswordsAreRejectedEvenIfACallerRequestsThem() {
        var view = new NativeLoginScreen.View("Login", List.of("Enter password", "Visible while editing"),
                List.of(), new NativeLoginScreen.Button("APPLY", "Use text"), NativeLoginScreen.Layout.PANEL,
                true, "A text password!", 32, 1);
        var failure = assertThrows(IllegalArgumentException.class, () -> PaperDialogScreen.createBase(view));
        assertFalse(failure.getMessage().contains("A text password!"));
        var hidden = new NativeLoginScreen.View("Login", List.of(), List.of(), null,
                NativeLoginScreen.Layout.PANEL, false, "should-not-travel-in-a-view", 32, 2);
        assertThrows(IllegalArgumentException.class, () -> PaperDialogScreen.createBase(hidden));
    }

    @Test void helpWithoutGridButtonsUsesANoticeInsteadOfAnInvalidEmptyMultiAction() {
        var view = new NativeLoginScreen.View("Help", List.of("Help text"), List.of(),
                new NativeLoginScreen.Button("BACK", "Back"), NativeLoginScreen.Layout.PANEL, false, "", 4, 7);
        assertDoesNotThrow(() -> PaperDialogScreen.createBase(view));
        var notice = assertInstanceOf(NoticeType.class, assertDoesNotThrow(() -> PaperDialogScreen.createType(view, "help")));
        assertEquals(Component.text("Back"), notice.action().label());
    }

    @Test void allExportedLoginViewsAreAcceptedByTheRealPaperImplementation() throws Exception {
        try (var files = java.nio.file.Files.list(java.nio.file.Path.of("build/reports/login-ui"))) {
            var views = files.filter(path -> path.toString().endsWith(".json")).toList();
            assertTrue(views.size() >= 30);
            for (var path : views) {
                var view = new com.google.gson.Gson().fromJson(java.nio.file.Files.readString(path), NativeLoginScreen.View.class);
                assertFalse(view.textEntry(), path.toString());
                assertEquals("", view.initialText(), path.toString());
                assertDoesNotThrow(() -> PaperDialogScreen.createBase(view), path.toString());
                assertDoesNotThrow(() -> PaperDialogScreen.createType(view, "exported"), path.toString());
            }
        }
    }
}
