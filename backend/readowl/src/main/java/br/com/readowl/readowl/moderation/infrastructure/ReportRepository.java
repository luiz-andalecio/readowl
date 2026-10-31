package br.com.readowl.readowl.moderation.infrastructure;

import br.com.readowl.readowl.moderation.domain.Report;
import br.com.readowl.readowl.moderation.domain.ReportStatus;
import br.com.readowl.readowl.moderation.domain.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID>, JpaSpecificationExecutor<Report> {

    boolean existsByReporterIdAndTargetTypeAndTargetIdAndStatus(UUID reporterId, ReportTargetType targetType,
                                                                UUID targetId, ReportStatus status);

    @Override
    @EntityGraph(attributePaths = {"reporter", "targetOwner", "resolvedBy"})
    Page<Report> findAll(Specification<Report> specification, Pageable pageable);
}
