package es.mrdino.serverpassword;

/** The input buffer is independent of the screen; changing views never starts a new attempt. */
final class LoginInput {
    private final boolean numeric;
    private final int maxLength;
    private String value = "";
    private boolean revealed;

    LoginInput(boolean numeric, int maxLength, boolean revealed) {
        this.numeric = numeric;
        this.maxLength = numeric ? Math.min(maxLength, LoginSettings.MAX_PIN_LENGTH) : maxLength;
        this.revealed = revealed;
    }

    static boolean isNumeric(String value) {
        return !value.isEmpty() && value.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    boolean replace(String candidate) {
        if (candidate == null || candidate.length() > maxLength
                || candidate.chars().anyMatch(Character::isISOControl)
                || (numeric && !candidate.isEmpty() && !isNumeric(candidate))) return false;
        value = candidate;
        return true;
    }

    void append(char character) { replace(value + character); }
    void backspace() {
        if (!value.isEmpty()) value = value.substring(0, value.offsetByCodePoints(value.length(), -1));
    }
    void clear() { value = ""; }
    void toggleRevealed() { revealed = !revealed; }
    boolean numeric() { return numeric; }
    boolean revealed() { return revealed; }
    String value() { return value; }
    String display() { return revealed ? value : "*".repeat(value.codePointCount(0, value.length())); }
}
