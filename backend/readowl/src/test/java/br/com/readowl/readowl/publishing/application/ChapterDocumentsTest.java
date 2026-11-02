package br.com.readowl.readowl.publishing.application;

import br.com.readowl.readowl.shared.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChapterDocumentsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ChapterDocuments.Rendered render(String json) {
        return ChapterDocuments.render(JSON.readTree(json), url -> url.trim().startsWith("https://i.imgur.com/"));
    }

    private static String paragraph(String text) {
        return "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":"
                + JSON.writeValueAsString(text) + "}]}]}";
    }

    @Test
    void rendersEveryAllowedNodeAndMark() {
        ChapterDocuments.Rendered rendered = render("""
                {"type":"doc","content":[
                  {"type":"heading","attrs":{"level":2,"textAlign":"center"},"content":[{"type":"text","text":"Título"}]},
                  {"type":"paragraph","attrs":{"textAlign":null},"content":[
                    {"type":"text","marks":[{"type":"bold"},{"type":"italic"}],"text":"forte"},
                    {"type":"hardBreak"},
                    {"type":"text","marks":[{"type":"underline"}],"text":"sublinhado"},
                    {"type":"text","marks":[{"type":"strike"}],"text":"riscado"}]},
                  {"type":"blockquote","content":[{"type":"paragraph","content":[{"type":"text","text":"citação"}]}]},
                  {"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"item"}]}]}]},
                  {"type":"orderedList","attrs":{"start":3,"type":null},"content":[{"type":"listItem","content":[{"type":"paragraph"}]}]},
                  {"type":"horizontalRule"}]}
                """);

        assertThat(rendered.html()).isEqualTo("<h2 class=\"text-center\">Título</h2>"
                + "<p><strong><em>forte</em></strong><br><u>sublinhado</u><s>riscado</s></p>"
                + "<blockquote><p>citação</p></blockquote>"
                + "<ul><li><p>item</p></li></ul>"
                + "<ol start=\"3\"><li><p></p></li></ol>"
                + "<hr>");
        assertThat(rendered.plainText()).contains("forte").contains("citação").doesNotContain("<");
    }

    @Test
    void escapesHtmlInTextInsteadOfInterpretingIt() {
        ChapterDocuments.Rendered rendered = render(paragraph("<script>alert('x')</script> & <img src=x onerror=y>"));

        assertThat(rendered.html()).doesNotContain("<script").doesNotContain("<img").contains("&lt;script&gt;");
        assertThat(rendered.plainText()).contains("<script>");
    }

    private static String image(String attrs) {
        return "{\"type\":\"doc\",\"content\":[{\"type\":\"image\",\"attrs\":" + attrs + "}]}";
    }

    @Test
    void anImageBecomesATagWithEscapedAttributes() {
        ChapterDocuments.Rendered rendered = render(image("""
                {"src":"https://i.imgur.com/a.jpg?x=1&y=2","alt":"\\"><script>alert(1)</script>","title":"Arte"}"""));

        assertThat(rendered.html()).isEqualTo("<img src=\"https://i.imgur.com/a.jpg?x=1&amp;y=2\" alt=\"&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;\""
                + " title=\"Arte\" loading=\"lazy\" referrerpolicy=\"no-referrer\">");
    }

    @Test
    void theEditorMayLeaveTheOptionalImageFieldsEmpty() {
        assertThat(render(image("""
                {"src":"https://i.imgur.com/a.jpg","alt":null,"title":null}""")).html())
                .isEqualTo("<img src=\"https://i.imgur.com/a.jpg\" alt=\"\" loading=\"lazy\" referrerpolicy=\"no-referrer\">");
    }

    @Test
    void anImageAddressCannotCloseTheAttribute() {
        assertThat(render(image("""
                {"src":"https://i.imgur.com/a.jpg\\" onerror=\\"alert(1)"}""")).html()).doesNotContain("\" onerror");
    }

    @Test
    void aChapterWithOnlyImagesIsNotBlankButHasNoText() {
        ChapterDocuments.Rendered rendered = render(image("{\"src\":\"https://i.imgur.com/a.jpg\"}"));

        assertThat(rendered.isBlank()).isFalse();
        assertThat(rendered.plainText()).isEmpty();
    }

    @Test
    void imagesCanBeInsideQuotesAndLists() {
        assertThat(render("""
                {"type":"doc","content":[{"type":"blockquote","content":[{"type":"image","attrs":{"src":"https://i.imgur.com/a.jpg"}}]}]}""").html())
                .startsWith("<blockquote><img ");
    }

    @Test
    void thereIsALimitOfImages() {
        String one = "{\"type\":\"image\",\"attrs\":{\"src\":\"https://i.imgur.com/a.jpg\"}}";
        String allowed = "{\"type\":\"doc\",\"content\":[" + String.join(",", java.util.Collections.nCopies(200, one)) + "]}";
        String tooMany = "{\"type\":\"doc\",\"content\":[" + String.join(",", java.util.Collections.nCopies(201, one)) + "]}";

        assertThat(render(allowed).isBlank()).isFalse();
        assertThatThrownBy(() -> render(tooMany)).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"src\":1}",
            "{\"src\":null}",
            "{\"src\":\"https://fora.example.com/a.jpg\"}",
            "{\"src\":\"http://i.imgur.com/a.jpg\"}",
            "{\"src\":\"data:image/png;base64,AAAA\"}",
            "{\"src\":\"https://i.imgur.com/a.jpg\",\"onerror\":\"alert(1)\"}",
            "{\"src\":\"https://i.imgur.com/a.jpg\",\"style\":\"x\"}",
            "{\"src\":\"https://i.imgur.com/a.jpg\",\"alt\":1}",
            "{\"src\":\"https://i.imgur.com/a.jpg\",\"title\":[]}"
    })
    void imagesOutsideTheRulesAreRefused(String attrs) {
        assertThatThrownBy(() -> render(image(attrs)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("INVALID_DOCUMENT");
    }

    @Test
    void anImageCannotHaveContentOrALongDescriptionOrSitInsideAParagraph() {
        for (String json : java.util.List.of(
                "{\"type\":\"doc\",\"content\":[{\"type\":\"image\",\"attrs\":{\"src\":\"https://i.imgur.com/a.jpg\"},\"content\":[{\"type\":\"paragraph\"}]}]}",
                image("{\"src\":\"https://i.imgur.com/a.jpg\",\"alt\":\"" + "a".repeat(501) + "\"}"),
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"image\",\"attrs\":{\"src\":\"https://i.imgur.com/a.jpg\"}}]}]}")) {
            assertThatThrownBy(() -> render(json)).isInstanceOf(ApiException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "{\"type\":\"paragraph\"}",
            "{\"type\":\"doc\",\"attrs\":{}}",
            "{\"type\":\"doc\",\"content\":{}}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"script\"}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"image\",\"attrs\":{\"src\":\"javascript:alert(1)\"}}]}",
            "{\"type\":\"doc\",\"content\":[{\"content\":[]}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"text\",\"text\":\"solto\"}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"onclick\":\"x\"}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"attrs\":{\"style\":\"x\"}}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"attrs\":{\"textAlign\":\"\\\"><script>\"}}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"text\":\"x\"}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"paragraph\"}]}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\"}]}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":1}]}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"x\",\"marks\":[{\"type\":\"link\",\"attrs\":{\"href\":\"javascript:x\"}}]}]}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"x\",\"marks\":[{\"type\":\"bold\",\"attrs\":{}}]}]}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"heading\",\"attrs\":{\"level\":1}}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"heading\",\"attrs\":{\"level\":\"2\\\" onclick=\\\"x\"}}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"heading\"}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"orderedList\",\"attrs\":{\"start\":\"1\\\" onclick=\\\"x\"},\"content\":[{\"type\":\"listItem\",\"content\":[{\"type\":\"paragraph\"}]}]}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"bulletList\",\"content\":[{\"type\":\"paragraph\"}]}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"bulletList\"}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"blockquote\"}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"horizontalRule\",\"content\":[{\"type\":\"paragraph\"}]}]}"
    })
    void rejectsAnythingOutsideTheAllowList(String json) {
        assertThatThrownBy(() -> render(json))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("INVALID_DOCUMENT");
    }

    @Test
    void errorMessageDoesNotEchoClientMarkup() {
        assertThatThrownBy(() -> render("{\"type\":\"doc\",\"content\":[{\"type\":\"<script>alert(1)</script>\"}]}"))
                .isInstanceOf(ApiException.class)
                .hasMessageNotContaining("<script>");
    }

    @Test
    void rejectsExcessiveDepth() {
        String opening = "{\"type\":\"blockquote\",\"content\":[";
        String deep = opening.repeat(30) + "{\"type\":\"paragraph\"}" + "]}".repeat(30);

        assertThatThrownBy(() -> render("{\"type\":\"doc\",\"content\":[" + deep + "]}"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("profundidade");
    }

    @Test
    void rejectsTextOverTheLimit() {
        assertThatThrownBy(() -> render(paragraph("a".repeat(500_001))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("tamanho");
    }

    @Test
    void acceptsTextAtTheLimit() {
        assertThat(render(paragraph("a".repeat(500_000))).plainText()).hasSize(500_000);
    }

    @Test
    void reportsBlankDocumentsSoThatTheyAreNotPublished() {
        assertThat(render(ChapterDocuments.EMPTY_DOCUMENT).isBlank()).isTrue();
        assertThat(render(paragraph("   ")).isBlank()).isTrue();
        assertThat(render(paragraph("texto")).isBlank()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"x\",\"marks\":{}}]}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"x\",\"marks\":[{\"type\":\"link\"}]}]}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"orderedList\",\"attrs\":{\"type\":\"a\"},\"content\":[{\"type\":\"listItem\",\"content\":[{\"type\":\"paragraph\"}]}]}]}",
            "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"attrs\":[]}]}"
    })
    void refusesMarksAndAttributesOutsideTheList(String json) {
        assertThatThrownBy(() -> render(json)).isInstanceOf(ApiException.class);
    }

    @Test
    void refusesADocumentAboveTheSizeLimit() {
        String manyEmptyParagraphs = "{\"type\":\"paragraph\"},".repeat(110_000);

        assertThatThrownBy(() -> render("{\"type\":\"doc\",\"content\":[" + manyEmptyParagraphs + "{\"type\":\"paragraph\"}]}"))
                .isInstanceOf(ApiException.class).hasMessageContaining("tamanho máximo");
    }

    @Test
    void anOrderedListWithoutStartNumberBeginsAtOne() {
        ChapterDocuments.Rendered rendered = render("""
                {"type":"doc","content":[{"type":"orderedList","content":[
                  {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"um"}]}]}]}]}
                """);

        assertThat(rendered.html()).isEqualTo("<ol><li><p>um</p></li></ol>");
    }
}
