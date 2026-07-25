package io.kbrag.domain.config;

import io.kbrag.domain.enums.VectorEngine;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Single binding of the {@code kb.*} configuration tree.
 *
 * <p>Layer one of the five layer configuration model: everything here comes from environment
 * variables with a working local default, so the service starts without any {@code .env} file.
 * Later layers (global settings, knowledge base, application version, request) override these
 * values at runtime and are introduced by the milestones that need them.
 */
@Getter
@Setter
@ToString
@ConfigurationProperties(prefix = "kb")
public class KbProperties {

    /** Vector engine selection. */
    private Vector vector = new Vector();

    /** Elasticsearch connectivity. */
    private Elasticsearch es = new Elasticsearch();

    /** Milvus connectivity, only used in full mode. */
    private Milvus milvus = new Milvus();

    /** Object storage connectivity. */
    private Minio minio = new Minio();

    /** Embedding provider configuration. */
    private Embedding embedding = new Embedding();

    /** Parser service connectivity. */
    private Parser parser = new Parser();

    /** Console authentication policy. */
    private Auth auth = new Auth();

    /** Upload validation policy. */
    private Upload upload = new Upload();

    /** Splitting defaults of the indexing pipeline. */
    private Split split = new Split();

    /** Retrieval defaults. */
    private Retrieval retrieval = new Retrieval();

    /**
     * Vector engine selection.
     */
    @Getter
    @Setter
    @ToString
    public static class Vector {

        /** Engine literal, {@code es} for lite mode and {@code milvus} for full mode. */
        private String engine = VectorEngine.ES.code();

        /**
         * Resolves the configured engine.
         *
         * @return vector engine, defaults to Elasticsearch
         */
        public VectorEngine resolved() {
            return VectorEngine.from(engine);
        }
    }

    /**
     * Elasticsearch connectivity and index settings.
     */
    @Getter
    @Setter
    @ToString(exclude = "password")
    public static class Elasticsearch {

        /** Cluster URI. */
        private String uri = "http://127.0.0.1:9200";

        /** Basic auth user, blank disables authentication. */
        private String username = "";

        /** Basic auth password, blank disables authentication. */
        private String password = "";

        /**
         * Analyzer of the {@code content} field. The index creation falls back to
         * {@code standard} when the configured analyzer is not installed.
         */
        private String contentAnalyzer = "ik_max_word";

        /** Analyzer used when the configured one is unavailable. */
        private String fallbackAnalyzer = "standard";

        /** Number of shards of a freshly created index. */
        private int numberOfShards = 1;

        /** Number of replicas of a freshly created index, 0 fits a single node deployment. */
        private int numberOfReplicas = 0;
    }

    /**
     * Milvus connectivity.
     */
    @Getter
    @Setter
    @ToString(exclude = "token")
    public static class Milvus {

        /** Cluster URI, blank means Milvus is not deployed. */
        private String uri = "";

        /** Access token, blank for an unauthenticated local deployment. */
        private String token = "";
    }

    /**
     * Object storage connectivity.
     */
    @Getter
    @Setter
    @ToString(exclude = "secretKey")
    public static class Minio {

        /** Service endpoint. */
        private String endpoint = "http://127.0.0.1:9000";

        /** Access key. */
        private String accessKey = "";

        /** Secret key. */
        private String secretKey = "";

        /** Bucket holding original files and parse artifacts, kept private. */
        private String bucket = "kb-files";

        /** Validity of the pre signed download URLs in minutes. */
        private int presignedTtlMinutes = 30;
    }

    /**
     * Embedding provider configuration. A blank API key switches the deployment to zero key mode.
     */
    @Getter
    @Setter
    @ToString(exclude = "apiKey")
    public static class Embedding {

        /** Provider implementation name. */
        private String provider = "dashscope";

        /** Model name. */
        private String model = "text-embedding-v4";

        /** Vector dimension, frozen into the physical index at creation time. */
        private int dimension = 1024;

        /** Credential, blank means zero key mode. */
        private String apiKey = "";

        /** OpenAI compatible base URL of the provider. */
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

        /** Maximum texts per provider request. */
        private int batchSize = 10;

        /** Request timeout in milliseconds. */
        private int timeoutMs = 30000;
    }

    /**
     * Parser service connectivity.
     */
    @Getter
    @Setter
    @ToString
    public static class Parser {

        /** Base URL of the parser service. */
        private String baseUrl = "http://127.0.0.1:8001";

        /** Request timeout in milliseconds, aligned with the parser worker timeout. */
        private int timeoutMs = 300000;
    }

    /**
     * Console authentication policy.
     */
    @Getter
    @Setter
    @ToString
    public static class Auth {

        /** Token validity in hours. */
        private int tokenTtlHours = 24;

        /** Consecutive failures that trigger the lock. */
        private int maxFailedAttempts = 5;

        /** Lock duration in minutes. */
        private int lockMinutes = 15;

        /** Bootstrap administrator user name created on an empty database. */
        private String bootstrapUsername = "admin";
    }

    /**
     * Upload validation policy.
     */
    @Getter
    @Setter
    @ToString
    public static class Upload {

        /** Maximum accepted file size in megabytes. */
        private int maxFileSizeMb = 100;

        /** Accepted lower case extensions. */
        private List<String> allowedExtensions =
                List.of("pdf", "docx", "txt", "md", "xlsx", "csv");
    }

    /**
     * Splitting defaults of the indexing pipeline.
     */
    @Getter
    @Setter
    @ToString
    public static class Split {

        /** Maximum estimated tokens per chunk. */
        private int maxTokens = 600;

        /** Overlap in estimated tokens. */
        private int overlapTokens = 100;
    }

    /**
     * Retrieval defaults.
     */
    @Getter
    @Setter
    @ToString
    public static class Retrieval {

        /** Reciprocal rank fusion damping constant. */
        private int rrfK = 60;

        /** Default candidates recalled per route. */
        private int defaultRecallTopK = 50;

        /** Default number of returned nodes. */
        private int defaultTopN = 5;

        /** Upper bound accepted for {@code recall_top_k}. */
        private int maxRecallTopK = 100;

        /** Upper bound accepted for {@code top_n}. */
        private int maxTopN = 20;
    }
}
