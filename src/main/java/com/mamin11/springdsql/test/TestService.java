package com.mamin11.springdsql.test;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TestService {

    private static final Logger log = LoggerFactory.getLogger(TestService.class);
    private static final char[] NAME_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private final TestRepository testRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ExecutorService virtualThreadExecutor;

    public TestService(
            TestRepository testRepository,
            JdbcTemplate jdbcTemplate,
            ExecutorService virtualThreadExecutor) {
        this.testRepository = testRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    public Page<TestEntity> getTests(Pageable pageable) {
        return testRepository.findAll(pageable);
    }

    /** Deliberately exceeds Aurora DSQL's row limit to exercise transaction rollback. */
    @Transactional
    public int updateInOneTransaction(int count, String name) {
        if (count < 3_001 || count > 100_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "count must be between 3001 and 100000");
        }
        validateName(name);

        // Skip unchanged names so the request really attempts to mutate count distinct rows.
        List<TestEntity> records = testRepository.findByNameNotOrderByIdAsc(
                name, PageRequest.of(0, count));
        if (records.size() != count) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Need " + count + " records with a different name; found " + records.size());
        }

        records.forEach(record -> record.setName(name));
        // Flush executes the updates without committing the surrounding transaction.
        // Let failures escape so Spring rolls back every update before returning an HTTP error.
        testRepository.saveAllAndFlush(records);
        return records.size();
    }

    // Each repository call commits its own transaction; the loop needs no transaction.
    public long updateUntilEmpty(String name, int batchSize) {
        validateName(name);
        if (batchSize < 1 || batchSize > 1_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "batchSize must be between 1 and 1000");
        }
        long totalUpdated = 0;
        int updated;
        do {
            updated = testRepository.renameNextBatch(name, batchSize);
            totalUpdated += updated;
        } while (updated > 0);
        return totalUpdated;
    }

    private void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "name must contain between 1 and 255 characters and must not be blank");
        }
    }

    public void generateDataAsync() {
        virtualThreadExecutor.submit(() -> generateAndInsert(100_000));
    }

    private void generateAndInsert(int total) {
        log.info("Starting bulk insert for {} test rows.", total);
        String sql = "insert into test (id, name) values (?, ?)";
        int batchSize = 1_000;

        try {
            for (int offset = 0; offset < total; offset += batchSize) {
                int size = Math.min(batchSize, total - offset);
                UUID[] ids = new UUID[size];
                String[] names = new String[size];

                for (int i = 0; i < size; i++) {
                    ids[i] = UUID.randomUUID();
                    names[i] = randomName(12);
                }

                jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ps.setObject(1, ids[i]);
                        ps.setString(2, names[i]);
                    }

                    @Override
                    public int getBatchSize() {
                        return size;
                    }
                });
            }

            log.info("Inserted {} random test rows.", total);
        } catch (Exception ex) {
            log.error("Bulk insert failed after starting {} rows.", total, ex);
        }
    }

    private String randomName(int length) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        char[] buffer = new char[length];
        for (int i = 0; i < length; i++) {
            buffer[i] = NAME_CHARS[random.nextInt(NAME_CHARS.length)];
        }
        return new String(buffer);
    }
}
