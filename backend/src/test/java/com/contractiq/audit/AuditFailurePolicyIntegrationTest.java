package com.contractiq.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contractiq.organization.Organization;
import com.contractiq.support.AbstractMockMvcTest;
import com.contractiq.support.TestData;
import com.contractiq.user.AppUser;
import com.contractiq.user.Role;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The two failure policies. A change of state is audited in the same transaction, so if the entry
 * cannot be written the change does not happen (fail closed). Logins and refusals are audited on a
 * best-effort basis, so a broken audit table cannot lock everyone out or turn a 403 into a 500.
 */
class AuditFailurePolicyIntegrationTest extends AbstractMockMvcTest {

    @MockitoSpyBean
    private AuditWriter writer;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void ifTheAuditEntryForAChangeCannotBeWrittenTheChangeIsRolledBack() throws Exception {
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);
        String title = "Never-saved-" + System.nanoTime();
        doThrow(new IllegalStateException("audit table unavailable")).when(writer).writeInCurrentTransaction(any());

        mvc.perform(post("/api/v1/contracts").header(HttpHeaders.AUTHORIZATION, bearer(uploader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", title, "vendorName", "V", "contractText", "t"))))
                .andExpect(status().isInternalServerError());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contracts WHERE title = ?", Integer.class, title)).isZero();
    }

    @Test
    void ifLoginAndRefusalEntriesCannotBeWrittenTheRequestsStillWork() throws Exception {
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);
        doThrow(new IllegalStateException("audit table unavailable")).when(writer).writeInNewTransaction(any());

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", uploader.getUsername(), "password", TestData.PASSWORD))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", uploader.getUsername(), "password", "wrong"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, bearer(uploader)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/contracts/987654321").header(HttpHeaders.AUTHORIZATION, bearer(uploader)))
                .andExpect(status().isNotFound());
    }
}
