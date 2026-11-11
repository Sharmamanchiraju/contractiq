package com.contractiq.organization;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link Organization}.
 */
public interface OrganizationRepository extends JpaRepository<Organization, Long> {
}
