package br.com.readowl.readowl.publishing.application;

import java.util.UUID;

/**
 * Publicado dentro da transação da publicação. Só vale para a primeira vez do capítulo em uma obra no ar:
 * corrigir ou republicar não é novidade para quem segue a obra.
 */
public record ChapterFirstPublished(UUID bookId, UUID chapterId) {
}
