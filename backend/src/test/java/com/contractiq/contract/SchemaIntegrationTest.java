package com.contractiq.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.contractiq.organization.Organization;
import com.contractiq.organization.OrganizationRepository;
import com.contractiq.support.AbstractIntegrationTest;
import com.contractiq.user.AppUser;
import com.contractiq.user.AppUserRepository;
import com.contractiq.user.Role;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the Liquibase changelogs build a schema that Hibernate accepts with ddl-auto=validate
 * (the application context would fail to start otherwise) and that the entities round-trip.
 */
class SchemaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private ContractRepository contracts;

    @Autowired
    private ReviewRepository reviews;

    @Test
    void liquibaseCreatesAllTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);
        assertThat(tables).containsAll(List.of("organizations", "app_users", "contracts", "reviews"));
    }

    @Test
    void entitiesRoundTrip() {
        Organization org = organizations.save(new Organization("Schema Test Org"));
        AppUser user = users.save(new AppUser(org.getId(), "schema-user", "schema@example.test", "hash", Role.UPLOADER));
        Contract contract = contracts.save(new Contract(org.getId(), "Title", "Vendor", "Some contract text", user.getId()));
        Review review = reviews.save(new Review(contract.getId(), user.getId(), ReviewDecision.APPROVED, null));

        Contract loaded = contracts.findById(contract.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ContractStatus.UPLOADED);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getVersion()).isNotNull();
        assertThat(reviews.findById(review.getId()).orElseThrow().getDecision()).isEqualTo(ReviewDecision.APPROVED);
    }
}
