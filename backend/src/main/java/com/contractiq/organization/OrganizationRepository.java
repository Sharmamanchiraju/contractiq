package com.contractiq.organization;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link Organization}.
 */
public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    Optional<Organization> findFirstByName(String name);
}
