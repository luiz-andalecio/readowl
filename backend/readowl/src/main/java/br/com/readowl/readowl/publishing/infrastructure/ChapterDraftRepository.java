package br.com.readowl.readowl.publishing.infrastructure;

import br.com.readowl.readowl.publishing.domain.ChapterDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChapterDraftRepository extends JpaRepository<ChapterDraft, UUID> {
}
