package br.com.readowl.readowl.shared.text;

import java.text.Normalizer;
import java.util.Locale;

public final class Slugs {

    private Slugs() {
    }

    /**
     * Minúsculas, sem acentos, com hifens no lugar de pontuação. Letras latinas sem decomposição são trocadas pelas
     * equivalentes. Outros alfabetos, como japonês ou cirílico, não têm equivalente e são descartados. Por isso,
     * pode devolver texto vazio, e quem chama define a alternativa.
     */
    public static String slugify(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace("æ", "ae").replace("œ", "oe").replace("ø", "o").replace("ß", "ss")
                .replace("ð", "d").replace("þ", "th").replace("ł", "l").replace("đ", "d")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }
}
