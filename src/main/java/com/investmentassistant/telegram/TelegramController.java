package com.investmentassistant.telegram;

import java.util.List;

import com.investmentassistant.telegram.TelegramApiDtos.AddSourceRequest;
import com.investmentassistant.telegram.TelegramApiDtos.AddSourceResponse;
import com.investmentassistant.telegram.TelegramApiDtos.AvailableSourceResponse;
import com.investmentassistant.telegram.TelegramApiDtos.MessageResponse;
import com.investmentassistant.telegram.TelegramApiDtos.SourceDetailsResponse;
import com.investmentassistant.telegram.TelegramApiDtos.SourceResponse;
import com.investmentassistant.telegram.TelegramApiDtos.StatsResponse;
import com.investmentassistant.telegram.TelegramApiDtos.UpdateSourceRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/telegram")
public class TelegramController {

    private final TelegramSourceService service;

    public TelegramController(TelegramSourceService service) {
        this.service = service;
    }

    @GetMapping("/sources")
    public List<SourceResponse> sources(@RequestParam(required = false) Boolean enabled) {
        return service.listSources(enabled).stream().map(SourceResponse::from).toList();
    }

    @GetMapping("/sources/available")
    public List<AvailableSourceResponse> availableSources() {
        return service.listAvailableSources().stream().map(AvailableSourceResponse::from).toList();
    }

    @PostMapping("/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public AddSourceResponse addSource(
            @RequestBody AddSourceRequest request,
            @RequestParam(required = false) Integer historyLimit) {
        if (request.telegramId() == null) {
            throw new TelegramApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "telegramId is required");
        }
        return AddSourceResponse.from(service.monitor(request.telegramId(), historyLimit));
    }

    @PatchMapping("/sources/{id}")
    public SourceDetailsResponse updateSource(
            @PathVariable long id,
            @RequestBody UpdateSourceRequest request) {
        return SourceDetailsResponse.from(service.setEnabled(id, request.enabled()));
    }

    @GetMapping("/sources/{id}")
    public SourceDetailsResponse source(@PathVariable long id) {
        return SourceDetailsResponse.from(service.getSource(id));
    }

    @GetMapping("/sources/{id}/messages")
    public List<MessageResponse> messagesBySource(
            @PathVariable long id,
            @RequestParam(required = false) Integer limit) {
        return service.getMessagesBySource(id, limit).stream().map(MessageResponse::from).toList();
    }

    @GetMapping("/messages")
    public List<MessageResponse> messagesByTelegramId(
            @RequestParam long telegramId,
            @RequestParam(required = false) Integer limit) {
        return service.getMessagesByTelegramId(telegramId, limit).stream().map(MessageResponse::from).toList();
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        return StatsResponse.from(service.stats());
    }
}
