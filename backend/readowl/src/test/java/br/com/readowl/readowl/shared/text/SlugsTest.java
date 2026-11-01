package br.com.readowl.readowl.shared.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SlugsTest {

    @ParameterizedTest
    @CsvSource({
            "Shihais: Remanescente da Aura, shihais-remanescente-da-aura",
            "Coração da Ação,               coracao-da-acao",
            "Straße nach Ærø,               strasse-nach-aero",
            "Łódź e Œuvre,                  lodz-e-oeuvre",
            "'  Título!!!  ',               titulo"
    })
    void keepsLatinLettersAndRemovesEverythingElse(String title, String slug) {
        assertThat(Slugs.slugify(title)).isEqualTo(slug);
    }

    @Test
    void otherAlphabetsHaveNoEquivalent() {
        assertThat(Slugs.slugify("日本語")).isEmpty();
    }

    @Test
    void textIsEscapedForLike() {
        assertThat(Texts.escapeLike("50%_a\\b")).isEqualTo("50\\%\\_a\\\\b");
        assertThat(Texts.blankToNull("  ")).isNull();
        assertThat(Texts.blankToNull(" a ")).isEqualTo("a");
    }
}
