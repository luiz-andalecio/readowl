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
        return ChapterDocuments.render(JSON.readTree(json));
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
}
