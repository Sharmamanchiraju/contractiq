package com.contractiq.audit;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.CrudRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * Proves, by inspecting the code itself, that the audit log has no way to change or remove an entry.
 * If someone later adds an update or delete method, one of these tests fails. (The database user in
 * development can still run SQL by hand: see docs/SECURITY.md for the production grants that close that.)
 */
class AuditAppendOnlyTest {

    private static final Pattern CHANGES_DATA = Pattern.compile("(?i)(delete|remove|update|set|clear|truncate|purge|merge|replace|modify)");

    private static Set<String> names(Method[] methods) {
        return Arrays.stream(methods).map(Method::getName).collect(Collectors.toSet());
    }

    @Test
    void theRepositoryOffersOnlyAddAndReadMethods() {
        assertThat(names(AuditLogRepository.class.getDeclaredMethods()))
                .containsExactlyInAnyOrder("save", "findByOrganizationId", "countByOrganizationId");
    }

    @Test
    void theRepositoryDoesNotInheritDeleteOrUpdateMethods() {
        assertThat(CrudRepository.class.isAssignableFrom(AuditLogRepository.class)).isFalse();
        assertThat(JpaRepository.class.isAssignableFrom(AuditLogRepository.class)).isFalse();
        // Everything the interface exposes, inherited or not.
        assertThat(names(AuditLogRepository.class.getMethods()))
                .noneMatch(name -> CHANGES_DATA.matcher(name).find() && !name.equals("save"));
    }

    @Test
    void theEntityHasNoSettersAndEveryColumnIsNotUpdatable() {
        assertThat(names(AuditLog.class.getDeclaredMethods()).stream().filter(n -> n.startsWith("set"))).isEmpty();
        for (Field field : AuditLog.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getName().equals("id")) {
                continue;
            }
            Column column = field.getAnnotation(Column.class);
            assertThat(column).as("column annotation on " + field.getName()).isNotNull();
            assertThat(column.updatable()).as(field.getName() + " must not be updatable").isFalse();
        }
    }

    @Test
    void theServiceHasNoMethodThatChangesOrRemovesEntries() {
        assertThat(names(AuditService.class.getDeclaredMethods()))
                .noneMatch(name -> CHANGES_DATA.matcher(name).find());
        assertThat(names(AuditWriter.class.getDeclaredMethods()))
                .containsExactlyInAnyOrder("writeInCurrentTransaction", "writeInNewTransaction");
    }

    @Test
    void theHttpApiOnlyReadsEntries() {
        for (Method method : AuditController.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(PostMapping.class)).isFalse();
            assertThat(method.isAnnotationPresent(PutMapping.class)).isFalse();
            assertThat(method.isAnnotationPresent(PatchMapping.class)).isFalse();
            assertThat(method.isAnnotationPresent(DeleteMapping.class)).isFalse();
        }
    }
}
