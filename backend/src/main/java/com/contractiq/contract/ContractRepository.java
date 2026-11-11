package com.contractiq.contract;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link Contract}. Every lookup takes an organization id so a caller can
 * never reach another organization's contracts by mistake.
 */
public interface ContractRepository extends JpaRepository<Contract, Long> {

    Optional<Contract> findByIdAndOrganizationId(Long id, Long organizationId);

    Page<Contract> findByOrganizationId(Long organizationId, Pageable pageable);

    Page<Contract> findByOrganizationIdAndStatus(Long organizationId, ContractStatus status, Pageable pageable);
}
