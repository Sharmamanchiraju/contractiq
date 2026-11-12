package com.contractiq.common.config;

import com.contractiq.organization.Organization;
import com.contractiq.organization.OrganizationRepository;
import com.contractiq.user.AppUser;
import com.contractiq.user.AppUserRepository;
import com.contractiq.user.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates one demo organization and three demo users (uploader, reviewer, admin) on startup so a
 * developer can log in straight away. Runs only in the dev profile. Passwords come from the
 * SEED_*_PASSWORD environment variables, are stored as BCrypt hashes, and are never logged.
 * Running it again does not create duplicates.
 */
@Component
@Profile("dev")
@EnableConfigurationProperties(SeedProperties.class)
public class DevDataSeeder implements ApplicationRunner {

    static final String DEMO_ORGANIZATION = "Demo Organization";

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final OrganizationRepository organizations;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SeedProperties seed;

    public DevDataSeeder(OrganizationRepository organizations, AppUserRepository users,
                         PasswordEncoder passwordEncoder, SeedProperties seed) {
        this.organizations = organizations;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.seed = seed;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (isBlank(seed.uploaderPassword()) && isBlank(seed.reviewerPassword()) && isBlank(seed.adminPassword())) {
            log.warn("No SEED_*_PASSWORD variables set; no demo users were created");
            return;
        }
        Organization organization = organizations.findFirstByName(DEMO_ORGANIZATION)
                .orElseGet(() -> organizations.save(new Organization(DEMO_ORGANIZATION)));
        seedUser(organization, "uploader", Role.UPLOADER, seed.uploaderPassword());
        seedUser(organization, "reviewer", Role.REVIEWER, seed.reviewerPassword());
        seedUser(organization, "admin", Role.ADMIN, seed.adminPassword());
    }

    private void seedUser(Organization organization, String username, Role role, String password) {
        if (isBlank(password)) {
            log.warn("Password for demo user '{}' is not set; skipping", username);
            return;
        }
        if (users.existsByUsername(username)) {
            log.info("Demo user '{}' already exists", username);
            return;
        }
        users.save(new AppUser(organization.getId(), username, username + "@demo.example",
                passwordEncoder.encode(password), role));
        log.info("Created demo user '{}' with role {}", username, role);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
