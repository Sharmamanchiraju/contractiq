package com.contractiq.support;

import com.contractiq.contract.Contract;
import com.contractiq.contract.ContractRepository;
import com.contractiq.contract.ContractStatus;
import com.contractiq.organization.Organization;
import com.contractiq.organization.OrganizationRepository;
import com.contractiq.user.AppUser;
import com.contractiq.user.AppUserRepository;
import com.contractiq.user.Role;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Creates organizations, users and contracts directly in the database for tests. Every call uses
 * unique names, so tests that share one MySQL container never see each other's data.
 */
@TestComponent
public class TestData {

    /** The password of every test user. Fake, for tests only. */
    public static final String PASSWORD = "Fake-test-password-123";

    private final OrganizationRepository organizations;
    private final AppUserRepository users;
    private final ContractRepository contracts;
    private final PasswordEncoder passwordEncoder;

    public TestData(OrganizationRepository organizations, AppUserRepository users, ContractRepository contracts,
                    PasswordEncoder passwordEncoder) {
        this.organizations = organizations;
        this.users = users;
        this.contracts = contracts;
        this.passwordEncoder = passwordEncoder;
    }

    public Organization organization() {
        return organizations.save(new Organization("org-" + UUID.randomUUID()));
    }

    public AppUser user(Organization organization, Role role) {
        String username = role.name().toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        return users.save(new AppUser(organization.getId(), username, username + "@example.test",
                passwordEncoder.encode(PASSWORD), role));
    }

    /** Saves a contract and walks it through the real transitions until it reaches {@code status}. */
    public Contract contract(Organization organization, AppUser creator, ContractStatus status) {
        Contract contract = new Contract(organization.getId(), "Title " + UUID.randomUUID(), "Vendor",
                "Contract text for tests.", creator.getId());
        for (ContractStatus step : pathTo(status)) {
            contract.moveTo(step);
        }
        return contracts.save(contract);
    }

    private static List<ContractStatus> pathTo(ContractStatus status) {
        return switch (status) {
            case UPLOADED -> List.of();
            case ANALYZING -> List.of(ContractStatus.ANALYZING);
            case ANALYZED -> List.of(ContractStatus.ANALYZING, ContractStatus.ANALYZED);
            case NEEDS_MANUAL_REVIEW -> List.of(ContractStatus.ANALYZING, ContractStatus.NEEDS_MANUAL_REVIEW);
            case APPROVED -> List.of(ContractStatus.ANALYZING, ContractStatus.ANALYZED, ContractStatus.APPROVED);
            case REJECTED -> List.of(ContractStatus.ANALYZING, ContractStatus.ANALYZED, ContractStatus.REJECTED);
        };
    }
}
