package io.kbrag.parser;

import io.kbrag.parser.ocr.OcrEngineFactory;
import io.kbrag.parser.security.XmlHardening;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.ApplicationRunner;

/**
 * kb-rag-parse-java: the Java document and chat-log parsing microservice of kb-rag.
 *
 * <p>It does one job - turning an uploaded file into structured markdown, per-page content and image
 * bytes, and turning a chat export into structured sessions. It calls no model: VLM image understanding,
 * embedding and chunking all belong to kb-rag-server (requirement doc §4.2, M3-CONTRACTS.md §0). The
 * optional local OCR fallback for scanned pages is the single exception, and it is off by default.
 *
 * @author owlzhangfq@gmail.com
 */
@Slf4j
@SpringBootApplication
public class ParserApplication {

    public static void main(String[] args) {
        SpringApplication.run(ParserApplication.class, args);
    }

    /**
     * Applies startup-time hardening and the OCR gate before the first request can arrive.
     *
     * <p>An {@link ApplicationRunner} rather than a {@code @PostConstruct} so a failing OCR gate stops
     * the context after the rest of the wiring has proved sound - the resulting message then names the
     * one thing that is actually misconfigured.
     *
     * @param ocrEngineFactory the configured OCR gate
     * @return the startup runner
     */
    @Bean
    ApplicationRunner startupGuards(OcrEngineFactory ocrEngineFactory) {
        return args -> {
            XmlHardening.harden();
            ocrEngineFactory.ensureOcrEngineReady();
        };
    }
}
