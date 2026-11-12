package com.contractiq.auth;

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
    /** A hash of a random value, made with the same encoder (same cost) as real password hashes. */
    private final String dummyHash;

    public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode("dummy-" + UUID.randomUUID());
    }

    /**
     * Returns a token for valid credentials; otherwise throws the same {@link BadCredentialsException}
     * whether the username is unknown or the password is wrong.
     */
    public LoginResponse login(LoginRequest request) {
        Optional<AppUser> user = users.findByUsername(request.username());
        String hashToCheck = user.map(AppUser::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);
        if (user.isEmpty() || !passwordMatches) {
            throw new BadCredentialsException("Invalid username or password");
        }
        AppUser found = user.get();
        String token = jwtService.issue(found.getId(), found.getOrganizationId(), found.getRole());
        return new LoginResponse(token, "Bearer", jwtService.expirySeconds());
    }
}
