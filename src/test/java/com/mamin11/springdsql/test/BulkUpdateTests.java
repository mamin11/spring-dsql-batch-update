package com.mamin11.springdsql.test;

import java.util.UUID;
import java.util.stream.IntStream;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class BulkUpdateTests {

    @Autowired
    private TestService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TestController(service)).build();
        jdbc.execute("alter table test drop constraint if exists reject_last_update");
        jdbc.update("delete from test");
        jdbc.batchUpdate("insert into test (id, name) values (?, ?)",
                IntStream.rangeClosed(1, 3_002).mapToObj(i -> new UUID(0, i)).toList(),
                1_000, (ps, id) -> {
                    ps.setObject(1, id);
                    ps.setString(2, "original");
                });
    }

    @Test
    void endpointDefaultsTo3001AndCommitsExactlyThatManyRows() throws Exception {
        // H2 has no DSQL row limit: this verifies routing, JPA updates, and the success path only.
        mvc.perform(patch("/tests/bulk-update").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"updated"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(3_001));
        assertThat(countNamed("updated")).isEqualTo(3_001);
        assertThat(countNamed("original")).isEqualTo(1);
    }

    @Test
    void failureOnLastSelectedRowRollsBackAllEarlierUpdates() {
        jdbc.execute("""
                alter table test add constraint reject_last_update
                check (id <> '00000000-0000-0000-0000-000000000bb9' or name <> 'updated')
                """);

        assertThatThrownBy(() -> service.updateInOneTransaction(3_001, "updated"))
                .isInstanceOf(DataAccessException.class);

        assertThat(countNamed("updated")).isZero();
        assertThat(countNamed("original")).isEqualTo(3_002);
    }

    @Test
    void rejectsInsufficientEligibleRowsBeforeUpdatingAnything() throws Exception {
        mvc.perform(patch("/tests/bulk-update").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"count":3003,"name":"updated"}
                                """))
                .andExpect(status().isConflict());
        assertThat(countNamed("original")).isEqualTo(3_002);

        mvc.perform(patch("/tests/bulk-update").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"original"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        for (String body : new String[] {
                "{\"count\":3000,\"name\":\"updated\"}",
                "{\"count\":100001,\"name\":\"updated\"}",
                "{\"name\":\" \"}",
                "{}",
                "{\"name\":\"" + "x".repeat(256) + "\"}"}) {
            mvc.perform(patch("/tests/bulk-update").contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(countNamed("original")).isEqualTo(3_002);
    }

    @Test
    void batchedEndpointUpdatesAllRowsWithoutLoadingEntities() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            mvc.perform(patch("/tests/bulk-update-batched").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"updated"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updated").value(3_002));

            assertThat(countNamed("updated")).isEqualTo(3_002);
            assertThat(statistics.getEntityLoadCount()).isZero();
            assertThat(statistics.getEntityFetchCount()).isZero();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    void batchedFailurePreservesEarlierCommitsAndCanResume() {
        jdbc.execute("""
                alter table test add constraint reject_last_update
                check (id <> '00000000-0000-0000-0000-000000000bb9' or name <> 'updated')
                """);

        assertThatThrownBy(() -> service.updateUntilEmpty("updated", 1_000))
                .isInstanceOf(DataAccessException.class);

        assertThat(countNamed("updated")).isEqualTo(3_000);
        assertThat(countNamed("original")).isEqualTo(2);

        jdbc.execute("alter table test drop constraint reject_last_update");
        assertThat(service.updateUntilEmpty("updated", 1_000)).isEqualTo(2);
    }

    @Test
    void batchedOperationStopsAtZeroWithoutChangingMatchingRows() {
        assertThat(service.updateUntilEmpty("original", 1_000)).isZero();
        jdbc.update("delete from test");
        assertThat(service.updateUntilEmpty("updated", 1_000)).isZero();
    }

    @Test
    void repeatingCompletedUpdateMakesNoFurtherChanges() {
        assertThat(service.updateUntilEmpty("updated", 1_000)).isEqualTo(3_002);
        assertThat(service.updateUntilEmpty("updated", 1_000)).isZero();
    }

    @Test
    void batchedEndpointRejectsInvalidInput() throws Exception {
        for (String body : new String[] {
                "{\"name\":\"updated\",\"batchSize\":0}",
                "{\"name\":\"updated\",\"batchSize\":1001}",
                "{\"name\":\" \"}",
                "{}",
                "{\"name\":\"" + "x".repeat(256) + "\"}"}) {
            mvc.perform(patch("/tests/bulk-update-batched").contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(countNamed("original")).isEqualTo(3_002);
    }

    @Test
    void repositoryCommitsEachBatchIndependentlyOfAnOuterTransaction() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.executeWithoutResult(status -> {
            assertThat(service.updateUntilEmpty("updated", 1_000)).isEqualTo(3_002);
            status.setRollbackOnly();
        });
        assertThat(countNamed("updated")).isEqualTo(3_002);
    }

    private int countNamed(String name) {
        return jdbc.queryForObject("select count(*) from test where name = ?", Integer.class, name);
    }
}
