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
 *
 * @author owlzhangfq@gmail.com
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

    /** Rerank provider configuration. */
    private Rerank rerank = new Rerank();

    /** Chat provider configuration, currently only consumed by the query rewrite stage. */
    private Chat chat = new Chat();

    /** Vision provider configuration, consumed by the image asset stage. */
    private Vision vision = new Vision();

    /** Image asset pipeline policy. */
    private Image image = new Image();

    /** Chat log import policy. */
    private ChatImport chatImport = new ChatImport();

    /** Operations alert dispatcher policy. */
    private Alert alert = new Alert();

    /** Demo data set location. */
    private Demo demo = new Demo();

    /** Index synchronization compensation policy. */
    private Sync sync = new Sync();

    /** Parser service connectivity. */
    private Parser parser = new Parser();

    /** Console authentication policy. */
    private Auth auth = new Auth();

    /** Upload validation policy. */
    private Upload upload = new Upload();

    /** Document level policy, currently the version retention window. */
    private Doc doc = new Doc();

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
     * Rerank provider configuration. A blank API key switches the rerank stage off.
     */
    @Getter
    @Setter
    @ToString(exclude = "apiKey")
    public static class Rerank {

        /** Provider implementation name. */
        private String provider = "dashscope";

        /** Model name. */
        private String model = "gte-rerank";

        /** Credential, blank disables the rerank stage. */
        private String apiKey = "";

        /**
         * Full URL of the native rerank endpoint. DashScope exposes rerank outside the OpenAI
         * compatible surface, so unlike embedding this is a complete URL rather than a base URL.
         */
        private String url =
                "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

        /** Request timeout in milliseconds, the stage degrades once it elapses. */
        private int timeoutMs = 1500;

        /** Maximum candidates submitted to one rerank call. */
        private int candidateLimit = 50;
    }

    /**
     * Chat provider configuration. A blank API key switches the query rewrite stage off.
     */
    @Getter
    @Setter
    @ToString(exclude = "apiKey")
    public static class Chat {

        /** Provider implementation name. */
        private String provider = "dashscope";

        /** Model name. */
        private String model = "qwen-plus";

        /** Credential, blank disables the query rewrite stage. */
        private String apiKey = "";

        /** OpenAI compatible base URL of the provider. */
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

        /** Request timeout in milliseconds. */
        private int timeoutMs = 3000;

        /** Sampling temperature, zero keeps the rewrite reproducible. */
        private double temperature = 0.0d;

        /** Upper bound of the generated rewrite. */
        private int maxTokens = 128;
    }

    /**
     * Vision provider configuration. A blank API key makes the image stage skip every image.
     */
    @Getter
    @Setter
    @ToString(exclude = "apiKey")
    public static class Vision {

        /** Provider implementation name. */
        private String provider = "dashscope";

        /** Model name. */
        private String model = "qwen-vl-max";

        /** Credential, blank means images are stored but carry no text. */
        private String apiKey = "";

        /** OpenAI compatible base URL of the provider. */
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

        /** Request timeout in milliseconds; image understanding is slower than text completion. */
        private int timeoutMs = 20000;

        /** Sampling temperature, zero keeps the transcription reproducible. */
        private double temperature = 0.0d;

        /** Upper bound of the generated proxy. */
        private int maxTokens = 1024;
    }

    /**
     * Image asset pipeline policy.
     */
    @Getter
    @Setter
    @ToString
    public static class Image {

        /** Extensions treated as a standalone image upload rather than as a document. */
        private List<String> standaloneExtensions =
                List.of("png", "jpg", "jpeg", "webp", "bmp", "gif");

        /** Images processed per document; the rest are skipped and reported as a warning. */
        private int maxPerDocument = 100;

        /** Maximum size of one image in megabytes, larger ones are skipped. */
        private int maxImageSizeMb = 10;
    }

    /**
     * Chat log import policy.
     */
    @Getter
    @Setter
    @ToString
    public static class ChatImport {

        /** Column mapping profile requested from the parser when the caller names none. */
        private String defaultMappingProfile = "memotrace";

        /** Validity of an upload token issued by the match preview, in minutes. */
        private int uploadTokenTtlMinutes = 30;

        /**
         * Masking default of the chat path.
         *
         * <p>On, because a chat log is personal data by nature; the per category switches still come from
         * the knowledge base cleaning rules.
         */
        private boolean desensitizeDefault = true;
    }

    /**
     * Operations alert dispatcher policy.
     */
    @Getter
    @Setter
    @ToString
    public static class Alert {

        /** Length of the retrieval degradation observation window in minutes. */
        private int degradeWindowMinutes = 5;

        /** Minimum retrieval calls in the window before the degradation ratio is trusted. */
        private int degradeMinSamples = 20;

        /** Delay between two alert evaluation passes in milliseconds. */
        private long evaluationIntervalMs = 60000L;

        /** Request timeout of the webhook call in milliseconds. */
        private int webhookTimeoutMs = 3000;
    }

    /**
     * Demo data set location.
     */
    @Getter
    @Setter
    @ToString
    public static class Demo {

        /**
         * Directory holding the demo documents and their manifest.
         *
         * <p>The default points at the deployment repository so a local checkout works without any
         * environment variable; a container overrides it with the mounted path.
         */
        private String dataDir = "/Users/zhangfuqiang/AI/kb-rag/kb-rag-deploy/demo";

        /** Display name of the knowledge base the demo import creates. */
        private String knowledgeBaseName = "Demo 知识库";
    }

    /**
     * Index synchronization compensation policy.
     */
    @Getter
    @Setter
    @ToString
    public static class Sync {

        /** {@code false} disables the scan entirely, used by tests and by read only replicas. */
        private boolean compensationEnabled = true;

        /** Delay between two scans in milliseconds. */
        private long compensationIntervalMs = 30000L;

        /** A PENDING row older than this is treated as a lost write. */
        private int pendingTimeoutMinutes = 5;

        /** Retries after which a row is abandoned and reported through an error log. */
        private int maxRetry = 5;

        /** Maximum rows picked up by a single scan. */
        private int batchSize = 500;
    }

    /**
     * Parser service connectivity.
     */
    @Getter
    @Setter
    @ToString
    public static class Parser {

        /** Base URL of the parser service. */
        private String baseUrl = "http://127.0.0.1:20001";

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
                List.of("pdf", "docx", "txt", "md", "xlsx", "csv",
                        "png", "jpg", "jpeg", "webp", "bmp", "gif");
    }

    /**
     * Document level policy.
     */
    @Getter
    @Setter
    @ToString
    public static class Doc {

        /** Version retention window. */
        private Version version = new Version();

        /**
         * Document version retention window.
         */
        @Getter
        @Setter
        @ToString
        public static class Version {

            /** Non active READY versions kept before the oldest ones are archived. */
            private static final int DEFAULT_RETAIN_COUNT = 3;

            /** Smallest window the requirement allows. */
            private static final int MIN_RETAIN_COUNT = 1;

            /** Largest window the requirement allows. */
            private static final int MAX_RETAIN_COUNT = 20;

            /** Configured window, clamped when read. */
            private int retainCount = DEFAULT_RETAIN_COUNT;

            /**
             * Window actually applied by the cleanup.
             *
             * <p>Clamped here rather than validated at startup: the value is a deployment knob with no
             * request to fast-fail, and refusing to boot over a typo would take the whole service down
             * for a setting whose sane range is known.
             *
             * @return retention window within the allowed range
             */
            public int resolvedRetainCount() {
                return Math.min(MAX_RETAIN_COUNT, Math.max(MIN_RETAIN_COUNT, retainCount));
            }
        }
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

        /** Parent child splitting default of a freshly created knowledge base. */
        private boolean parentChildEnabled = false;

        /** Default maximum estimated tokens of a parent chunk. */
        private int parentMaxTokens = 1200;

        /** Default maximum estimated tokens of a child chunk. */
        private int childMaxTokens = 400;

        /** Default overlap in estimated tokens between two children. */
        private int childOverlap = 50;
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

        /** Default fusion strategy literal, {@code rrf} or {@code weighted}. */
        private String fusionMode = "rrf";

        /** Default vector weight of the weighted strategy. */
        private double wVec = 0.6d;

        /** Query rewrite default; off because it costs a model call on every search. */
        private boolean rewriteEnabled = false;

        /** Hard timeout of the query rewrite stage in milliseconds. */
        private long rewriteTimeoutMs = 800L;

        /** Time to live of a rewrite cache entry in minutes. */
        private int rewriteCacheTtlMinutes = 10;

        /** Maximum entries kept in the rewrite cache. */
        private int rewriteCacheMaxSize = 10000;

        /** Maximum characters accepted from a rewritten query before it is discarded. */
        private int rewriteMaxLength = 512;

        /** Maximum conversation turns forwarded to the rewrite model. */
        private int rewriteMaxHistoryTurns = 6;

        /** Rerank default when a rerank model is configured. */
        private boolean rerankEnabled = true;

        /**
         * Multiplier applied to {@code top_n} when sizing the parent candidate target of the two
         * level pipeline; the floor below keeps a small {@code top_n} from starving the merge.
         */
        private int parentCandidateFactor = 3;

        /** Lower bound of the parent candidate target. */
        private int parentCandidateFloor = 20;
    }
}
