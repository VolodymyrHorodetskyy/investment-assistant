package com.investmentassistant.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseStatusProbe {

    private static final Logger log = LoggerFactory.getLogger(DatabaseStatusProbe.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseStatusProbe(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isAvailable() {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return Integer.valueOf(1).equals(result);
        } catch (RuntimeException exception) {
            log.warn("Database availability check failed: {}", exception.getMessage());
            return false;
        }
    }
}
