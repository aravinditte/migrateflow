package com.migrateflow.intake;

import com.migrateflow.common.ApiException;
import com.migrateflow.identity.AccessControl;
import com.migrateflow.identity.AppUser;
import com.migrateflow.identity.SecurityConfig;
import com.migrateflow.identity.UserRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class IntakeController {
    private final IntakeService intake;
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AccessControl access;

    public IntakeController(IntakeService intake, JdbcTemplate jdbc, UserRepository users, AccessControl access) {
        this.intake = intake;
        this.jdbc = jdbc;
        this.users = users;
        this.access = access;
    }

    @PostMapping("/projects/{projectId}/bundles")
    public ResponseEntity<Map<String, Object>> upload(@PathVariable UUID projectId,
                                                      @RequestParam("customers") MultipartFile customers,
                                                      @RequestParam("orders") MultipartFile orders,
                                                      @RequestParam(value = "exportedAt", required = false) String exportedAt,
                                                      Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireProjectRole(actor, projectId, "ENGINEER");
        Instant exported = (exportedAt == null || exportedAt.isBlank()) ? null : Instant.parse(exportedAt);
        IntakeService.BundleResult result = intake.uploadBundle(actor, projectId, customers, orders, exported);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "bundleId", result.bundleId().toString(),
                "customers", fileView(result.customers()),
                "orders", fileView(result.orders())));
    }

    private Map<String, Object> fileView(IntakeService.StoredFile f) {
        return Map.of("fileId", f.fileId().toString(), "kind", f.kind(), "filename", f.filename(),
                "sha256", f.sha256(), "bytes", f.bytes(), "rowCount", f.rowCount());
    }

    @GetMapping("/bundles/{bundleId}/profile")
    public Map<String, Object> profile(@PathVariable UUID bundleId, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireMembership(actor, access.projectOfBundle(bundleId));
        List<Map<String, Object>> files = jdbc.query(
                "SELECT * FROM control.source_files WHERE bundle_id = ? ORDER BY kind",
                (rs, n) -> Map.<String, Object>of(
                        "fileId", rs.getObject("id", UUID.class).toString(),
                        "kind", rs.getString("kind"),
                        "filename", rs.getString("filename"),
                        "sha256", rs.getString("sha256"),
                        "bytes", rs.getLong("bytes"),
                        "rowCount", rs.getInt("row_count"),
                        "uploadedAt", String.valueOf(rs.getTimestamp("uploaded_at").toInstant())),
                bundleId);
        Map<String, Object> bundle = jdbc.query(
                "SELECT exported_at, status FROM control.dataset_bundles WHERE id = ?",
                (rs, n) -> Map.<String, Object>of(
                        "exportedAt", rs.getTimestamp("exported_at") == null ? "" : String.valueOf(rs.getTimestamp("exported_at").toInstant()),
                        "status", rs.getString("status")),
                bundleId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Bundle"));
        return Map.of("bundleId", bundleId.toString(), "exportedAt", bundle.get("exportedAt"),
                "status", bundle.get("status"), "files", files);
    }

    @GetMapping("/files/{fileId}/rows")
    public Map<String, Object> rows(@PathVariable UUID fileId,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireMembership(actor, access.projectOfFile(fileId));
        String kind = jdbc.query("SELECT kind FROM control.source_files WHERE id = ?",
                (rs, n) -> rs.getString("kind"), fileId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("File"));
        String table = "CUSTOMERS".equals(kind) ? "stage.customer_input_rows" : "stage.order_input_rows";
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id, data_row_number, physical_line_start, raw_json FROM " + table
                        + " WHERE file_id = ? ORDER BY data_row_number LIMIT ? OFFSET ?",
                (rs, n) -> Map.<String, Object>of(
                        "rowId", rs.getObject("id", UUID.class).toString(),
                        "dataRowNumber", rs.getInt("data_row_number"),
                        "physicalLineStart", rs.getInt("physical_line_start"),
                        "values", rs.getString("raw_json")),
                fileId, size, page * size);
        return Map.of("content", rows, "page", page, "size", size);
    }

    @GetMapping("/files/{fileId}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable UUID fileId, Authentication auth) {
        AppUser actor = SecurityConfig.requireUser(users, auth);
        access.requireMembership(actor, access.projectOfFile(fileId));
        Map<String, String> file = jdbc.query(
                "SELECT filename, storage_key FROM control.source_files WHERE id = ?",
                (rs, n) -> Map.of("filename", rs.getString("filename"), "storageKey", rs.getString("storage_key")),
                fileId).stream().findFirst().orElseThrow(() -> ApiException.notFound("File"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.get("filename") + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(intake.storedPath(file.get("storageKey"))));
    }
}
