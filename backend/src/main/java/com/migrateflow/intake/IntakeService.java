package com.migrateflow.intake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.migrateflow.audit.AuditService;
import com.migrateflow.common.ApiException;
import com.migrateflow.common.CanonicalHash;
import com.migrateflow.identity.AppUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class IntakeService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path uploadDir;
    private final long maxUploadBytes;
    private final int maxRows;
    private final int maxColumns;

    public IntakeService(JdbcTemplate jdbc, AuditService audit,
                         @Value("${migrateflow.upload-dir}") String uploadDir,
                         @Value("${migrateflow.max-upload-bytes}") long maxUploadBytes,
                         @Value("${migrateflow.max-rows}") int maxRows,
                         @Value("${migrateflow.max-columns}") int maxColumns) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.uploadDir = Path.of(uploadDir);
        this.maxUploadBytes = maxUploadBytes;
        this.maxRows = maxRows;
        this.maxColumns = maxColumns;
    }

    public record StoredFile(UUID fileId, String kind, String filename, String sha256, long bytes, int rowCount) {}
    public record BundleResult(UUID bundleId, StoredFile customers, StoredFile orders) {}

    @Transactional
    public BundleResult uploadBundle(AppUser actor, UUID projectId, MultipartFile customersFile,
                                     MultipartFile ordersFile, Instant exportedAt) {
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE", "Upload storage unavailable");
        }
        List<Path> writtenFiles = new ArrayList<>();
        try {
            UUID bundleId = UUID.randomUUID();
            jdbc.update("INSERT INTO control.dataset_bundles (id, project_id, exported_at, status, uploaded_by) VALUES (?,?,?,'UPLOADED',?)",
                    bundleId, projectId, exportedAt == null ? null : java.sql.Timestamp.from(exportedAt), actor.id());
            StoredFile customers = storeOne(actor, bundleId, "CUSTOMERS", customersFile, writtenFiles);
            StoredFile orders = storeOne(actor, bundleId, "ORDERS", ordersFile, writtenFiles);
            jdbc.update("UPDATE control.dataset_bundles SET status = 'PROFILED' WHERE id = ?", bundleId);
            audit.record(projectId, actor.id(), "BUNDLE_UPLOADED", "dataset_bundle", bundleId,
                    Map.of("customersSha256", customers.sha256(), "ordersSha256", orders.sha256(),
                            "customersRows", customers.rowCount(), "ordersRows", orders.rowCount()));
            return new BundleResult(bundleId, customers, orders);
        } catch (Exception e) {
            for (Path p : writtenFiles) {
                try { Files.deleteIfExists(p); } catch (IOException ignore) {}
            }
            throw e;
        }
    }

    private StoredFile storeOne(AppUser actor, UUID bundleId, String kind, MultipartFile file, List<Path> writtenFiles) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("FILE_REQUIRED", "The " + kind.toLowerCase() + " CSV file is required");
        }
        if (file.getSize() > maxUploadBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE",
                    "File exceeds the " + maxUploadBytes + " byte limit");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("FILE_UNREADABLE", "Could not read the uploaded file");
        }
        // Parse first: a malformed file fails the upload and nothing is staged.
        CsvParser.ParsedCsv parsed = CsvParser.parse(bytes, maxRows, maxColumns);
        String sha = CanonicalHash.sha256Hex(bytes);
        String storageKey = UUID.randomUUID() + ".csv";
        Path filePath = uploadDir.resolve(storageKey);
        try {
            Files.write(filePath, bytes);
            writtenFiles.add(filePath);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE", "Could not store the uploaded file");
        }
        UUID fileId = UUID.randomUUID();
        String filename = file.getOriginalFilename() == null ? kind.toLowerCase() + ".csv" : file.getOriginalFilename();
        jdbc.update("INSERT INTO control.source_files (id, bundle_id, kind, filename, storage_key, bytes, sha256, row_count, uploaded_by) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)",
                fileId, bundleId, kind, filename, storageKey, bytes.length, sha, parsed.rows().size(), actor.id());
        String table = "CUSTOMERS".equals(kind) ? "stage.customer_input_rows" : "stage.order_input_rows";
        for (CsvParser.CsvRow row : parsed.rows()) {
            Map<String, String> ordered = new LinkedHashMap<>(row.values());
            String rawJson;
            try {
                rawJson = mapper.writeValueAsString(ordered);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            jdbc.update("INSERT INTO " + table + " (id, file_id, data_row_number, physical_line_start, raw_json) VALUES (?,?,?,?,?::jsonb)",
                    UUID.randomUUID(), fileId, row.dataRowNumber(), row.physicalLineStart(), rawJson);
        }
        return new StoredFile(fileId, kind, filename, sha, bytes.length, parsed.rows().size());
    }

    public Path storedPath(String storageKey) {
        return uploadDir.resolve(storageKey);
    }
}
