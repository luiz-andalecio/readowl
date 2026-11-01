package br.com.readowl.readowl.moderation.api;

import br.com.readowl.readowl.moderation.application.AccessRecordService;
import br.com.readowl.readowl.shared.api.PageRequests;
import br.com.readowl.readowl.shared.api.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/access-records")
class AccessRecordController {

    private final AccessRecordService records;

    AccessRecordController(AccessRecordService records) {
        this.records = records;
    }

    @GetMapping
    PageResponse<AccessRecordService.AccessRecordView> list(@RequestParam UUID userId,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return records.list(userId, PageRequests.of(page, size, "createdAt", Set.of("createdAt")));
    }
}
