package com.contractiq.contract;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Data access for {@link Review}.
 */
public interface ReviewRepository extends JpaRepository<Review, Long> {

    List<Review> findByContractIdOrderByCreatedAtAsc(Long contractId);
}
