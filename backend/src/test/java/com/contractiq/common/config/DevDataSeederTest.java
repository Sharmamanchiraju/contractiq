package com.contractiq.common.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.contractiq.organization.Organization;
import com.contractiq.organization.OrganizationRepository;
import com.contractiq.user.AppUser;
import com.contractiq.user.AppUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit tests for the seeder's skip rules, with mocks and no database: no password means no user,
 * and an existing user is never created twice.
 */
class DevDataSeederTest {

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

    private DevDataSeeder seeder(String uploader, String reviewer, String admin) {
        return new DevDataSeeder(organizations, users, encoder, new SeedProperties(uploader, reviewer, admin));
    }

    @Test
    void createsNothingWhenNoPasswordIsConfigured() {
        seeder("", "  ", null).run(null);

        verifyNoInteractions(organizations, users, encoder);
    }

    @Test
    void createsOnlyTheUsersWhosePasswordIsSet() {
        when(organizations.findFirstByName(DevDataSeeder.DEMO_ORGANIZATION)).thenReturn(Optional.empty());
        when(organizations.save(any(Organization.class))).thenAnswer(call -> call.getArgument(0));
        when(users.existsByUsername(any())).thenReturn(false);
        when(encoder.encode("only-the-reviewer-password")).thenReturn("$2a$10$hash");

        seeder("", "only-the-reviewer-password", "").run(null);

        verify(users).save(any(AppUser.class));
        verify(encoder).encode("only-the-reviewer-password");
    }

    @Test
    void doesNotCreateAUserThatAlreadyExists() {
        when(organizations.findFirstByName(DevDataSeeder.DEMO_ORGANIZATION))
                .thenReturn(Optional.of(new Organization(DevDataSeeder.DEMO_ORGANIZATION)));
        when(users.existsByUsername("admin")).thenReturn(true);

        seeder("", "", "some-admin-password").run(null);

        verify(users, never()).save(any(AppUser.class));
        verify(encoder, never()).encode(any());
    }
}
