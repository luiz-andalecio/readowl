package br.com.readowl.readowl.shared.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface AuditEntryRepository extends JpaRepository<AuditEntry, UUID>, JpaSpecificationExecutor<AuditEntry> {
}
