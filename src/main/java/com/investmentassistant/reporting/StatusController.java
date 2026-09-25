package com.investmentassistant.reporting;

import com.investmentassistant.persistence.DatabaseStatusProbe;
import com.investmentassistant.telegram.TelegramStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/status")
public class StatusController {

    private static final String APPLICATION_NAME = "investment-assistant";

    private final DatabaseStatusProbe databaseStatusProbe;
    private final TelegramStatus telegramStatus;

    public StatusController(DatabaseStatusProbe databaseStatusProbe, TelegramStatus telegramStatus) {
        this.databaseStatusProbe = databaseStatusProbe;
        this.telegramStatus = telegramStatus;
    }

    @GetMapping
    public ResponseEntity<ApplicationStatus> status() {
        boolean databaseAvailable = databaseStatusProbe.isAvailable();
        String status = databaseAvailable ? "UP" : "DOWN";
        HttpStatus httpStatus = databaseAvailable ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus)
                .body(new ApplicationStatus(status, status, telegramStatus.get().name(), APPLICATION_NAME));
    }

    public record ApplicationStatus(String status, String database, String telegram, String application) {
    }
}
