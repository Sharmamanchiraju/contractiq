package com.contractiq.audit;

import com.contractiq.common.api.PageResponse;
import com.contractiq.common.security.AuthenticatedUser;
import com.contractiq.common.security.RequestInfo;
import com.contractiq.common.security.RequestInfoHolder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records and reads audit entries.
 *
 * <p>Two ways to record, chosen by how important the entry is:
 * <ul>
 *   <li>{@link #record}: for changes of state. Written in the SAME transaction as the change, so there
 *       is never a change without its entry. If the entry cannot be written, the change is rolled back.</li>
 *   <li>{@link #recordBestEffort}: for logins and refusals. Written in its own transaction, and a
 *       failure to write is logged (type only) but never breaks the request.</li>
 * </ul>
 * Nothing here accepts free text. Entries hold only ids, fixed action names and the request's id and
 * network address, so passwords, tokens, contract text and comments cannot end up in the log.
 */
@Service
public class AuditService {

    static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final Pattern CONTRACT_PATH = Pattern.compile("^/api/v1/contracts/(\\d{1,18})(?:/.*)?$");

    private final AuditWriter writer;
    private final AuditLogRepository repository;

    public AuditService(AuditWriter writer, AuditLogRepository repository) {
        this.writer = writer;
        this.repository = repository;
    }

    /** Records a change of state in the caller's transaction (fail closed). */
    public void record(AuditAction action, AuditOutcome outcome, Long organizationId, Long userId,
                       AuditEntityType entityType, Long entityId) {
        writer.writeInCurrentTransaction(entry(action, outcome, organizationId, userId, entityType, entityId));
    }

    /** Records an event on its own; never throws. */
    public void recordBestEffort(AuditAction action, AuditOutcome outcome, Long organizationId, Long userId,
                                 AuditEntityType entityType, Long entityId) {
        try {
            writer.writeInNewTransaction(entry(action, outcome, organizationId, userId, entityType, entityId));
        } catch (RuntimeException e) {
            // Only the type: the message of a database error could contain data.
            log.error("Could not write audit entry action={} type={}", action, e.getClass().getName());
        }
    }

    /**
     * Records a refusal (403) or a miss (404) for the request path, attributing it to the logged-in
     * user when there is one. A contract id in the path is recorded as the entity; nothing else from
     * the path is kept.
     */
    public void recordForRequest(AuditAction action, AuditOutcome outcome, String path) {
        AuthenticatedUser actor = currentActor();
        Matcher contract = path == null ? null : CONTRACT_PATH.matcher(path);
        boolean hasContract = contract != null && contract.matches();
        recordBestEffort(action, outcome,
                actor == null ? null : actor.organizationId(),
                actor == null ? null : actor.userId(),
                hasContract ? AuditEntityType.CONTRACT : null,
                hasContract ? Long.valueOf(contract.group(1)) : null);
    }

    /** One page of the caller's own organization's entries, newest first. Page size is capped at 100. */
    @Transactional(readOnly = true)
    public PageResponse<AuditEntryResponse> page(AuthenticatedUser caller, int page, int size) {
        int cappedSize = Math.min(size, MAX_PAGE_SIZE);
        Page<AuditLog> entries = repository.findByOrganizationId(caller.organizationId(),
                PageRequest.of(page, cappedSize, Sort.by(Sort.Order.desc("id"))));
        return PageResponse.from(entries, AuditEntryResponse::from);
    }

    private static AuditLog entry(AuditAction action, AuditOutcome outcome, Long organizationId, Long userId,
                                  AuditEntityType entityType, Long entityId) {
        RequestInfo info = RequestInfoHolder.current();
        return new AuditLog(organizationId, userId, action, entityType, entityId, outcome,
                info == null ? null : info.requestId(), info == null ? null : info.ipAddress());
    }

    private static AuthenticatedUser currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user ? user : null;
    }
}
