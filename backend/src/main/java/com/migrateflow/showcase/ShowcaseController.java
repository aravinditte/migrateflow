package com.migrateflow.showcase;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, anonymous, GET-only showcase API (spec 5.2). There are no
 * mutation handlers in this controller by design; SecurityConfig permits
 * anonymous GET on this namespace only, and pagination is hard-capped.
 * When no verified scenario exists the endpoints answer NOT_READY/503 -
 * never fabricated placeholder data.
 */
@RestController
@RequestMapping("/api/v1/showcase")
public class ShowcaseController {

    private static final int MAX_PAGE_SIZE = 50;

    private final ShowcaseQueryService query;

    public ShowcaseController(ShowcaseQueryService query) {
        this.query = query;
    }

    @GetMapping("/overview")
    public ShowcaseDto.Overview overview() {
        return query.overview();
    }

    @GetMapping("/workflow")
    public ShowcaseDto.Workflow workflow() {
        return query.workflow();
    }

    @GetMapping("/validation")
    public ShowcaseDto.IssuePage validation(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(required = false) String code) {
        return query.validationIssues(page(page), size(size), code);
    }

    @GetMapping("/mapping")
    public ShowcaseDto.MappingView mapping() {
        return query.mapping();
    }

    @GetMapping("/reconciliation")
    public ShowcaseDto.ReconciliationView reconciliation(@RequestParam(defaultValue = "rehearsal") String scope) {
        return query.reconciliation("cutover".equals(scope) ? "cutover" : "rehearsal");
    }

    @GetMapping("/uat")
    public ShowcaseDto.UatView uat() {
        return query.uat();
    }

    @GetMapping("/audit")
    public ShowcaseDto.AuditPage audit(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return query.audit(page(page), size(size));
    }

    @GetMapping("/records")
    public ShowcaseDto.RecordPage records(@RequestParam(defaultValue = "customers") String kind,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "10") int size) {
        return query.records("orders".equals(kind) ? "orders" : "customers", page(page), size(size));
    }

    @GetMapping("/evidence")
    public ResponseEntity<String> evidence() throws Exception {
        String json = new ObjectMapper().findAndRegisterModules()
                .writerWithDefaultPrettyPrinter().writeValueAsString(query.evidence());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"migrateflow-showcase-evidence.json\"")
                .body(json);
    }

    private static int page(int page) {
        return Math.max(0, page);
    }

    private static int size(int size) {
        return Math.min(MAX_PAGE_SIZE, Math.max(1, size));
    }
}
