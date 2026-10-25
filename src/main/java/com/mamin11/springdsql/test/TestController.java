package com.mamin11.springdsql.test;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tests")
public class TestController {

    private final TestService testService;

    public TestController(TestService testService) {
        this.testService = testService;
    }

    @GetMapping
    public Page<TestEntity> getTests(Pageable pageable) {
        return testService.getTests(pageable);
    }

    @PatchMapping("/bulk-update")
    public BulkUpdateResponse updateBulkData(@RequestBody BulkUpdateRequest request) {
        int count = request.count() == null ? 3_001 : request.count();
        return new BulkUpdateResponse(testService.updateInOneTransaction(count, request.name()));
    }

    public record BulkUpdateRequest(Integer count, String name) {
    }

    public record BulkUpdateResponse(long updated) {
    }

    @PatchMapping("/bulk-update-batched")
    public BulkUpdateResponse updateInBatches(@RequestBody BatchedUpdateRequest request) {
        return new BulkUpdateResponse(testService.updateUntilEmpty(request.name(),
                request.batchSize() == null ? 1_000 : request.batchSize()));
    }

    public record BatchedUpdateRequest(String name, Integer batchSize) {
    }

    @PostMapping("/bulk-generate")
    public ResponseEntity<String> generateBulkData() {
        testService.generateDataAsync();
        return ResponseEntity.accepted().body("Bulk generation started.");
    }
}
