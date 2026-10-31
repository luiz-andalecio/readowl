package br.com.readowl.readowl.shared.text;

import java.text.Normalizer;
import java.util.Locale;

public final class Slugs {

    private Slugs() {
    }

    /**
     * Minúsculas, sem acentos, com hifens no lugar de pontuação. Pode devolver texto vazio, por exemplo
     * para um título só com símbolos: quem chama define a alternativa.
     */
    public static String slugify(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }
}
