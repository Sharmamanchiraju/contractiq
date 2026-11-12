package com.contractiq.contract.ai;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for {@link AiUsageLog}. */
public interface AiUsageLogRepository extends JpaRepository<AiUsageLog, Long> {

    List<AiUsageLog> findByContractIdOrderByIdAsc(Long contractId);
}
