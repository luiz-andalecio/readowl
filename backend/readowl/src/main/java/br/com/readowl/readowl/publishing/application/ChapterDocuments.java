package br.com.readowl.readowl.publishing.application;

import br.com.readowl.readowl.shared.exception.ApiException;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Valida o documento do editor (versão 1 do formato) e gera o HTML de leitura e o texto puro.
 * <p>
 * A validação é por lista permitida: tipo, marca, atributo ou campo desconhecido recusa o documento.
 * O HTML é escrito aqui, com todo texto escapado. Nenhum HTML do cliente é interpretado, então não há
 * o que sanitizar. Valores de atributo só entram no HTML depois de confirmados contra uma lista fixa. Imagens ficam em outros
 * sites: o documento guarda só o endereço, conferido contra a lista de sites permitidos, e o servidor nunca as baixa.
 */
final class ChapterDocuments {

    static final int FORMAT_VERSION = 1;
    static final String EMPTY_DOCUMENT = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]}";

    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TEXT_LENGTH = 500_000;
    private static final int MAX_DEPTH = 20;
    private static final int MAX_IMAGES = 20;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final int MAX_IMAGE_TEXT_LENGTH = 500;

    private static final Set<String> BLOCKS =
            Set.of("paragraph", "heading", "blockquote", "bulletList", "orderedList", "horizontalRule", "image");
    private static final Set<String> NODE_FIELDS = Set.of("type", "attrs", "content", "marks", "text");
    private static final Map<String, String> MARK_TAGS =
            Map.of("bold", "strong", "italic", "em", "underline", "u", "strike", "s");
    private static final Set<String> TEXT_ALIGNMENTS = Set.of("left", "center", "right", "justify");

    private ChapterDocuments() {
    }

    record Rendered(String json, String plainText, String html) {

        boolean isBlank() {
            return plainText.isBlank();
        }

        /**
         * Pontuação solta não conta como palavra.
         */
        int wordCount() {
            return (int) WHITESPACE.splitAsStream(plainText)
                    .filter(word -> word.codePoints().anyMatch(Character::isLetterOrDigit))
                    .count();
        }
    }

    static Rendered render(JsonNode document, Predicate<String> allowedImage) {
        if (document == null || !document.isObject()) {
            throw invalid("o documento deve ser um objeto");
        }
        String json = document.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw invalid("o documento excede o tamanho máximo");
        }
        Output output = new Output(allowedImage);
        renderRoot(document, output);
        return new Rendered(json, output.plainText.toString().strip(), output.html.toString());
    }

    private static void renderRoot(JsonNode node, Output output) {
        if (!"doc".equals(typeOf(node))) {
            throw invalid("o nó raiz deve ser doc");
        }
        requireFields(node, Set.of("type", "content"));
        for (JsonNode child : childrenOf(node)) {
            renderBlock(child, output, 1);
        }
    }

    private static void renderBlock(JsonNode node, Output output, int depth) {
        if (depth > MAX_DEPTH) {
            throw invalid("o documento excede a profundidade máxima");
        }
        String type = typeOf(node);
        if (!BLOCKS.contains(type)) {
            throw invalid("tipo de bloco não permitido: " + printable(type));
        }
        requireFields(node, NODE_FIELDS);
        if (node.has("text") || node.has("marks")) {
            throw invalid("o bloco " + type + " não aceita text nem marks");
        }
        switch (type) {
            case "paragraph" -> renderTextBlock("p", node, Set.of("textAlign"), output);
            case "heading" -> renderTextBlock("h" + headingLevel(node), node, Set.of("level", "textAlign"), output);
            case "horizontalRule" -> {
                requireAttrs(node, Set.of());
                if (!childrenOf(node).isEmpty()) {
                    throw invalid("horizontalRule não aceita conteúdo");
                }
                output.html.append("<hr>");
                output.plainText.append('\n');
            }
            case "image" -> renderImage(node, output);
            case "blockquote" -> {
                requireAttrs(node, Set.of());
                output.html.append("<blockquote>");
                requireChildren(node, type);
                for (JsonNode child : childrenOf(node)) {
                    renderBlock(child, output, depth + 1);
                }
                output.html.append("</blockquote>");
            }
            case "bulletList" -> {
                requireAttrs(node, Set.of());
                renderList("<ul>", "</ul>", node, output, depth);
            }
            default -> {
                requireAttrs(node, Set.of("start", "type"));
                renderList(orderedListOpening(node), "</ol>", node, output, depth);
            }
        }
    }

    private static void renderImage(JsonNode node, Output output) {
        requireAttrs(node, Set.of("src", "alt", "title"));
        if (!childrenOf(node).isEmpty()) {
            throw invalid("image não aceita conteúdo");
        }
        JsonNode src = node.path("attrs").get("src");
        if (src == null || !src.isString() || !output.allowedImage.test(src.stringValue())) {
            throw invalid("a imagem precisa estar em um dos sites permitidos");
        }
        if (++output.images > MAX_IMAGES) {
            throw invalid("o documento excede o limite de imagens");
        }
        output.html.append("<img src=\"").append(escaped(src.stringValue().trim())).append("\" alt=\"").append(imageText(node, "alt")).append('"');
        String title = imageText(node, "title");
        if (!title.isEmpty()) {
            output.html.append(" title=\"").append(title).append('"');
        }
        output.html.append(" loading=\"lazy\" referrerpolicy=\"no-referrer\">");
        output.plainText.append('\n');
    }

    private static String imageText(JsonNode node, String name) {
        JsonNode value = node.path("attrs").get(name);
        if (value == null || value.isNull()) {
            return "";
        }
        if (!value.isString() || value.stringValue().length() > MAX_IMAGE_TEXT_LENGTH) {
            throw invalid(name + " da imagem é inválido");
        }
        return escaped(value.stringValue());
    }

    private static String escaped(String text) {
        return HtmlUtils.htmlEscape(text, StandardCharsets.UTF_8.name());
    }

    private static void renderList(String opening, String closing, JsonNode list, Output output, int depth) {
        requireChildren(list, typeOf(list));
        output.html.append(opening);
        for (JsonNode item : childrenOf(list)) {
            if (!"listItem".equals(typeOf(item))) {
                throw invalid("uma lista só aceita listItem");
            }
            requireFields(item, Set.of("type", "content", "attrs"));
            requireAttrs(item, Set.of());
            requireChildren(item, "listItem");
            output.html.append("<li>");
            for (JsonNode child : childrenOf(item)) {
                renderBlock(child, output, depth + 2);
            }
            output.html.append("</li>");
        }
        output.html.append(closing);
    }

    private static void renderTextBlock(String tag, JsonNode node, Set<String> allowedAttrs, Output output) {
        requireAttrs(node, allowedAttrs);
        output.html.append('<').append(tag).append(alignmentClass(node)).append('>');
        for (JsonNode child : childrenOf(node)) {
            renderInline(child, output);
        }
        output.html.append("</").append(tag).append('>');
        output.plainText.append('\n');
    }

    private static void renderInline(JsonNode node, Output output) {
        String type = typeOf(node);
        switch (type) {
            case "hardBreak" -> {
                requireFields(node, Set.of("type", "marks"));
                markTags(node);
                output.html.append("<br>");
                output.plainText.append('\n');
            }
            case "text" -> {
                requireFields(node, Set.of("type", "text", "marks"));
                JsonNode text = node.get("text");
                if (text == null || !text.isString() || text.stringValue().isEmpty()) {
                    throw invalid("um nó text precisa de texto");
                }
                List<String> tags = markTags(node);
                tags.forEach(tag -> output.html.append('<').append(tag).append('>'));
                output.html.append(escaped(text.stringValue()));
                tags.reversed().forEach(tag -> output.html.append("</").append(tag).append('>'));
                output.plainText.append(text.stringValue());
                if (output.plainText.length() > MAX_TEXT_LENGTH) {
                    throw invalid("o texto excede o tamanho máximo");
                }
            }
            default -> throw invalid("tipo de conteúdo não permitido: " + printable(type));
        }
    }

    private static List<String> markTags(JsonNode node) {
        JsonNode marks = node.get("marks");
        if (marks == null) {
            return List.of();
        }
        if (!marks.isArray()) {
            throw invalid("marks deve ser uma lista");
        }
        return marks.values().stream().map(mark -> {
            requireFields(mark, Set.of("type"));
            String tag = MARK_TAGS.get(typeOf(mark));
            if (tag == null) {
                throw invalid("marca não permitida: " + printable(typeOf(mark)));
            }
            return tag;
        }).toList();
    }

    private static int headingLevel(JsonNode node) {
        JsonNode level = node.path("attrs").get("level");
        if (level == null || !level.isIntegralNumber() || (level.intValue() != 2 && level.intValue() != 3)) {
            throw invalid("heading deve ter nível 2 ou 3");
        }
        return level.intValue();
    }

    private static String alignmentClass(JsonNode node) {
        JsonNode alignment = node.path("attrs").get("textAlign");
        if (alignment == null || alignment.isNull()) {
            return "";
        }
        if (!alignment.isString() || !TEXT_ALIGNMENTS.contains(alignment.stringValue())) {
            throw invalid("alinhamento de texto não permitido");
        }
        return " class=\"text-" + alignment.stringValue() + "\"";
    }

    private static String orderedListOpening(JsonNode node) {
        JsonNode start = node.path("attrs").get("start");
        JsonNode type = node.path("attrs").get("type");
        if (type != null && !type.isNull()) {
            throw invalid("orderedList não aceita type");
        }
        if (start == null || start.isNull()) {
            return "<ol>";
        }
        if (!start.isIntegralNumber() || start.intValue() < 1) {
            throw invalid("orderedList precisa de start positivo");
        }
        return start.intValue() == 1 ? "<ol>" : "<ol start=\"" + start.intValue() + "\">";
    }

    private static void requireChildren(JsonNode node, String type) {
        if (childrenOf(node).isEmpty()) {
            throw invalid(type + " precisa de conteúdo");
        }
    }

    private static Collection<JsonNode> childrenOf(JsonNode node) {
        JsonNode content = node.get("content");
        if (content == null) {
            return List.of();
        }
        if (!content.isArray()) {
            throw invalid("content deve ser uma lista");
        }
        return content.values();
    }

    private static void requireAttrs(JsonNode node, Set<String> allowed) {
        JsonNode attrs = node.get("attrs");
        if (attrs == null) {
            return;
        }
        if (!attrs.isObject()) {
            throw invalid("attrs deve ser um objeto");
        }
        for (String name : attrs.propertyNames()) {
            if (!allowed.contains(name)) {
                throw invalid("atributo não permitido: " + printable(name));
            }
        }
    }

    private static void requireFields(JsonNode node, Set<String> allowed) {
        for (String name : node.propertyNames()) {
            if (!allowed.contains(name)) {
                throw invalid("campo não permitido: " + printable(name));
            }
        }
    }

    private static String typeOf(JsonNode node) {
        if (!node.isObject() || node.get("type") == null || !node.get("type").isString()) {
            throw invalid("todo nó precisa de um tipo");
        }
        return node.get("type").stringValue();
    }

    /**
     * Os nomes vêm do cliente e aparecem na mensagem de erro: limita o tamanho e os caracteres.
     */
    private static String printable(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9_-]", "?");
        return safe.substring(0, Math.min(safe.length(), 40));
    }

    private static ApiException invalid(String reason) {
        return ApiException.badRequest("INVALID_DOCUMENT", "Documento inválido: " + reason + ".");
    }

    private static final class Output {
        private final StringBuilder html = new StringBuilder();
        private final StringBuilder plainText = new StringBuilder();
        private final Predicate<String> allowedImage;
        private int images;

        private Output(Predicate<String> allowedImage) {
            this.allowedImage = allowedImage;
        }
    }
}
