package io.kbrag.app.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.kbrag.app.document.DocumentService;
import io.kbrag.app.kb.KnowledgeBaseService;
import io.kbrag.common.api.ErrorCode;
import io.kbrag.common.exception.BizException;
import io.kbrag.common.util.JsonUtil;
import io.kbrag.domain.config.KbProperties;
import io.kbrag.domain.entity.Document;
import io.kbrag.domain.entity.KnowledgeBase;
import io.kbrag.domain.mapper.DocumentMapper;
import io.kbrag.domain.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One click import of the bundled demo document set.
 *
 * <p><b>Idempotent by name.</b> The existence of the demo knowledge base is the whole state: clicking the
 * button twice returns the same identifier and imports nothing. A counter or a marker row would be a second
 * source of truth that could disagree with what the operator sees in the list.
 *
 * <p>The status endpoint separates "the material is not there" from "it was already imported", because the
 * console needs to grey the button out for the first case and hide it for the second. A deployment that
 * simply did not mount the demo directory is a supported configuration, not a failure.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoImportService {

    /** Manifest listing the files to import. */
    public static final String MANIFEST_FILE_NAME = "manifest.json";

    private static final String DEMO_DESCRIPTION =
            "Sample document set shipped with the deployment, imported from the demo directory.";

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentService documentService;
    private final KbProperties properties;

    /**
     * Imports the demo document set, or returns the knowledge base a previous run created.
     *
     * <p>The idempotency check comes first, so a second click never depends on the material still being
     * mounted: a demo that was already imported keeps working after the directory is unmounted.
     *
     * @return knowledge base business id
     */
    public String importDemo() {
        KnowledgeBase existing = findDemo();
        if (existing != null) {
            log.info("demo knowledge base already present, kbId={}", existing.getKbId());
            return existing.getKbId();
        }
        List<Path> files = manifestFiles();
        if (files.isEmpty()) {
            // A missing directory is a deployment detail the user can fix, so it is reported rather than
            // silently creating an empty knowledge base that looks like a broken demo.
            throw BizException.invalidParam("demo material not found under " + properties.getDemo().getDataDir()
                    + ", expected " + MANIFEST_FILE_NAME
                    + " listing readable documents; set DEMO_DATA_DIR to the demo directory");
        }
        KnowledgeBase created = knowledgeBaseService.create(demoName(), DEMO_DESCRIPTION);
        int imported = 0;
        for (Path file : files) {
            try {
                documentService.upload(created.getKbId(), file.getFileName().toString(),
                        Files.readAllBytes(file));
                imported++;
            } catch (Exception e) {
                // One unreadable sample must not abort the whole demo: a partially populated knowledge base
                // is still a working demonstration, and the log names the file that was skipped.
                log.error("demo document import failed, errorCode={}, file={}",
                        ErrorCode.INTERNAL_ERROR, file.getFileName(), e);
            }
        }
        log.info("demo import finished, kbId={}, files={}, imported={}",
                created.getKbId(), files.size(), imported);
        return created.getKbId();
    }

    /**
     * Reports whether the demo can be imported and whether it already was.
     *
     * @return demo state
     */
    public DemoStatus status() {
        KnowledgeBase existing = findDemo();
        long docCount = existing == null ? 0L : documentMapper.selectCount(
                new LambdaQueryWrapper<Document>().eq(Document::getKbId, existing.getKbId()));
        return new DemoStatus(available(), existing != null,
                existing == null ? null : existing.getKbId(), docCount);
    }

    /**
     * Tells whether the demo material is present on disk.
     *
     * @return {@code true} when the manifest lists at least one readable file
     */
    public boolean available() {
        return !manifestFiles().isEmpty();
    }

    /**
     * Resolves the files to import from the manifest.
     *
     * <p>Only the manifest decides what is imported. Listing the directory instead would pick up whatever an
     * operator happened to drop next to the samples, and a demo has to be reproducible.
     *
     * @return readable files listed by the manifest, in declaration order
     */
    private List<Path> manifestFiles() {
        Path root = Path.of(properties.getDemo().getDataDir());
        Path manifest = root.resolve(MANIFEST_FILE_NAME);
        if (!Files.isReadable(manifest)) {
            return List.of();
        }
        DemoManifest parsed;
        try {
            parsed = JsonUtil.parse(Files.readString(manifest), DemoManifest.class);
        } catch (Exception e) {
            log.error("demo manifest unreadable, errorCode={}, path={}",
                    ErrorCode.INTERNAL_ERROR, manifest, e);
            return List.of();
        }
        if (parsed == null || parsed.documents() == null) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        for (DemoManifest.DemoFile entry : parsed.documents()) {
            if (entry == null || entry.fileName() == null || entry.fileName().isBlank()) {
                continue;
            }
            // The manifest is deployment data, so a path is resolved and then checked to stay inside the
            // demo directory: a traversal entry would otherwise read an arbitrary file from the host.
            Path candidate = root.resolve(entry.fileName()).normalize();
            if (!candidate.startsWith(root.normalize())) {
                log.info("skip demo manifest entry outside the data directory, fileName={}",
                        entry.fileName());
                continue;
            }
            if (Files.isReadable(candidate) && Files.isRegularFile(candidate)) {
                files.add(candidate);
            } else {
                log.info("skip missing demo file, fileName={}", entry.fileName());
            }
        }
        return files;
    }

    private KnowledgeBase findDemo() {
        return knowledgeBaseMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getName, demoName())
                .last("limit 1"));
    }

    private String demoName() {
        return properties.getDemo().getKnowledgeBaseName();
    }

    /**
     * State of the demo data set.
     *
     * @param available {@code true} when the material is mounted and readable
     * @param imported  {@code true} when the demo knowledge base exists
     * @param kbId      knowledge base business id, {@code null} before the first import
     * @param docCount  documents currently in the demo knowledge base
     */
    public record DemoStatus(boolean available, boolean imported, String kbId, long docCount) {
    }

    /**
     * Manifest of the demo directory.
     *
     * <p>{@code files} is accepted as a read alias of {@code documents} so a manifest written against either
     * spelling of the deployment contract imports rather than silently yielding nothing.
     *
     * @param documents documents to import, in declaration order
     */
    public record DemoManifest(
            @JsonAlias("files") List<DemoFile> documents) {

        /**
         * One manifest entry.
         *
         * @param fileName    path relative to the demo directory, subdirectories allowed
         * @param title       display title, informational
         * @param description short explanation, informational
         */
        public record DemoFile(
                @JsonProperty("file_name") String fileName,
                String title,
                String description) {
        }
    }
}
