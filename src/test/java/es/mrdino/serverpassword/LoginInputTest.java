package es.mrdino.serverpassword;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoginInputTest {
    @Test void numericInputPreservesLeadingZeroesAndMasksEveryCharacter() {
        LoginInput input = new LoginInput(true, 4, false);
        assertTrue(input.replace("0012"));
        assertEquals("0012", input.value());
        assertEquals("****", input.display());
        input.toggleRevealed();
        assertEquals("0012", input.display());
        input.toggleRevealed();
        assertEquals("****", input.display());
    }

    @Test void invalidInputNeverChangesTheExistingValue() {
        LoginInput input = new LoginInput(true, 4, false);
        input.replace("12");
        assertFalse(input.replace("12A"));
        assertFalse(input.replace("12345"));
        assertFalse(input.replace("12\n"));
        assertFalse(input.replace(null));
        assertEquals("12", input.value());
    }

    @Test void textInputPreservesCaseSpacesAndSymbols() {
        LoginInput input = new LoginInput(false, 32, false);
        assertTrue(input.replace("aA 09_!"));
        input.backspace();
        assertEquals("aA 09_", input.value());
        input.clear();
        assertEquals("", input.value());
    }

    @Test void unicodeBackspaceRemovesAnEntireCodePoint() {
        LoginInput input = new LoginInput(false, 32, false);
        input.replace("a\uD83E\uDD96");
        assertEquals("**", input.display());
        input.backspace();
        assertEquals("a", input.value());
    }

    @Test void oversizedNumericBufferIsCappedWithoutTruncatingExistingValues() {
        LoginInput input = new LoginInput(true, 128, false);
        assertTrue(input.replace("012345678"));
        assertFalse(input.replace("0123456789"));
        input.append('9');
        assertEquals("012345678", input.value());
    }
}
