package br.com.readowl.readowl.shared.api;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Envelope próprio da paginação: o formato da API não depende da serialização de {@link Page}.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }
}
