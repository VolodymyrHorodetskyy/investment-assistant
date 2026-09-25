package com.investmentassistant.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import javax.sql.DataSource;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SQLiteDataSourceConfiguration {

    @Bean
    DataSource dataSource(AppProperties properties) throws IOException {
        String configuredPath = Objects.requireNonNull(
                properties.database().path(), "app.database.path must be configured");
        Path databasePath = Path.of(configuredPath).toAbsolutePath().normalize();
        Path parentDirectory = databasePath.getParent();
        if (parentDirectory != null) {
            Files.createDirectories(parentDirectory);
        }

        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.enforceForeignKeys(true);
        sqliteConfig.setBusyTimeout(5_000);

        SQLiteDataSource dataSource = new SQLiteDataSource(sqliteConfig);
        dataSource.setUrl("jdbc:sqlite:" + databasePath);
        return dataSource;
    }
}
