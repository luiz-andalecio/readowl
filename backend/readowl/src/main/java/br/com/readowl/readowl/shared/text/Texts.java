package br.com.readowl.readowl.shared.text;

public final class Texts {

    private Texts() {
    }

    public static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /**
     * Para usar em LIKE com ESCAPE '\': o texto do usuário vale literalmente.
     */
    public static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
