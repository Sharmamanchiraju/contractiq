package com.contractiq.auth;

import com.contractiq.audit.AuditAction;
import com.contractiq.audit.AuditEntityType;
import com.contractiq.audit.AuditOutcome;
import com.contractiq.audit.AuditService;
import com.contractiq.common.security.JwtService;
import com.contractiq.user.AppUser;
import com.contractiq.user.AppUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Checks a username and password and issues a JWT. An unknown username is checked against a
 * dummy BCrypt hash, so the response takes about as long as for a wrong password and timing does
 * not reveal which usernames exist.
 */
@Service
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptLimiter limiter;
    private final AuditService audit;
    /** A hash of a random value, made with the same encoder (same cost) as real password hashes. */
    private final String dummyHash;

    public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
                       LoginAttemptLimiter limiter, AuditService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.limiter = limiter;
        this.audit = audit;
        this.dummyHash = passwordEncoder.encode("dummy-" + UUID.randomUUID());
    }

    /**
     * Returns a token for valid credentials; otherwise throws the same {@link BadCredentialsException}
     * whether the username is unknown or the password is wrong.
     */
    public LoginResponse login(LoginRequest request, String clientAddress) {
        // First, before any password work: is this username or address locked out? The answer is the same
        // for a real and a made-up username, and a locked name is refused even with the right password.
        LoginAttemptLimiter.Decision decision = limiter.check(request.username(), clientAddress);
        if (decision.blocked()) {
            throw new TooManyLoginAttemptsException(decision.retryAfterSeconds());
        }
        Optional<AppUser> user = users.findByUsername(request.username());
        String hashToCheck = user.map(AppUser::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);
        if (user.isEmpty() || !passwordMatches) {
            boolean lockedNow = limiter.recordFailure(request.username(), clientAddress);
            // A failure is attributed to the account when there is one. For a name that does not exist
            // there is no account to attribute it to, and the typed name is deliberately NOT stored
            // (people sometimes type a password into the username box).
            Long organizationId = user.map(AppUser::getOrganizationId).orElse(null);
            Long userId = user.map(AppUser::getId).orElse(null);
            AuditEntityType entityType = user.isPresent() ? AuditEntityType.USER : null;
            audit.recordBestEffort(AuditAction.LOGIN_FAILURE, AuditOutcome.FAILURE, organizationId, userId,
                    entityType, userId);
            if (lockedNow) {
                audit.recordBestEffort(AuditAction.LOGIN_BLOCKED, AuditOutcome.DENIED, organizationId, userId,
                        entityType, userId);
            }
            throw new BadCredentialsException("Invalid username or password");
        }
        limiter.recordSuccess(request.username());
        AppUser found = user.get();
        audit.recordBestEffort(AuditAction.LOGIN_SUCCESS, AuditOutcome.SUCCESS, found.getOrganizationId(),
                found.getId(), AuditEntityType.USER, found.getId());
        String token = jwtService.issue(found.getId(), found.getOrganizationId(), found.getRole());
        return new LoginResponse(token, "Bearer", jwtService.expirySeconds());
    }
}
