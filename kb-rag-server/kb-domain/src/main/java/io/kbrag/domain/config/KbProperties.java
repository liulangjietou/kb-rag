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

    /** Qdrant connectivity, only used in full mode. */
    private Qdrant qdrant = new Qdrant();

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

    /** Multimodal embedding provider configuration, the M14 contract section 6.1. */
    private MultimodalEmbedding multimodalEmbedding = new MultimodalEmbedding();

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

    /** Document indexing throughput. */
    private Index index = new Index();

    /** Splitting defaults of the indexing pipeline. */
    private Split split = new Split();

    /** Retrieval defaults. */
    private Retrieval retrieval = new Retrieval();

    /** Annotation workbench policy, requirement section 4.5. */
    private Annotation annotation = new Annotation();

    /** Knowledge graph connectivity and traversal defaults, requirement section 4.9. */
    private Graph graph = new Graph();

    /** Evaluation execution policy. */
    private Eval eval = new Eval();

    /** Release gate policy of the application version state machine. */
    private Gate gate = new Gate();

    /** Open API policy: rate limit default, audit retention and archiving. */
    private OpenApi openApi = new OpenApi();

    /** Search insight policy: recording switch and retention, the M10 contract section 2.3. */
    private Insight insight = new Insight();

    /** Operation audit policy: retention of the "who did what" trail, the M16 contract section 7. */
    private Audit audit = new Audit();

    /** Application release policy: index snapshot timeout and retention. */
    private App app = new App();

    /** Content governance policy: recycle bin retention, the M11 contract section 2.3. */
    private Governance governance = new Governance();

    /** URL import policy: fetch limits and the scheduled sync pass, the M12 contract section 3.5. */
    private WebImport webImport = new WebImport();

    /** External data source policy: scan limits and the scheduled sync pass, the M14 contract section 2.4. */
    private ExtSource extSource = new ExtSource();

    /**
     * Content governance policy, the M11 contract section 2.3.
     */
    @Getter
    @Setter
    @ToString
    public static class Governance {

        /** Days a trashed document stays restorable before the purge pass removes it for good. */
        private int trashRetentionDays = 30;

        /**
         * Documents one purge batch removes. A purge clears chunks and both engine copies per document,
         * so the bound is a document count, much smaller than the row-count bounds of the log cleanups.
         */
        private int trashPurgeBatchSize = 100;

        /** Cron expression of the daily purge pass. */
        private String trashPurgeCron = "0 10 4 * * *";

        /** {@code false} disables the purge pass; trashed documents then stay restorable forever. */
        private boolean trashPurgeEnabled = true;
    }

    /**
     * URL import policy, the M12 contract section 3.5.
     */
    @Getter
    @Setter
    @ToString
    public static class WebImport {

        /** Connect and read timeout of one page fetch. */
        private int fetchTimeoutMs = 10000;

        /** Largest page body accepted, counted while streaming - Content-Length is not trusted. */
        private int maxPageSizeMb = 10;

        /** Redirect hops the fetcher follows manually, re-validating each target against SSRF. */
        private int maxRedirects = 3;

        /** Cron expression of the nightly incremental sync pass. */
        private String syncCron = "0 30 2 * * *";

        /** {@code false} disables the scheduled pass; manual sync stays available. */
        private boolean syncEnabled = true;

        /** Sources one scheduled pass re-fetches; fetching is slow outbound I/O, hence a count bound. */
        private int syncBatchSize = 50;
    }

    /**
     * External data source policy, the M14 contract section 2.4.
     */
    @Getter
    @Setter
    @ToString
    public static class ExtSource {

        /** Cron expression of the nightly incremental sync pass. */
        private String syncCron = "0 0 3 * * *";

        /** {@code false} disables the scheduled pass; manual sync stays available. */
        private boolean syncEnabled = true;

        /** Sources one scheduled pass scans; a scan lists and fetches many objects, hence a small bound. */
        private int syncBatchSize = 10;

        /** Objects one scan considers per source; beyond it the pass records PARTIAL and truncates. */
        private int maxObjectsPerSource = 500;

        /** Connect and read budget of one connector call. */
        private int fetchTimeoutMs = 30000;
    }

    /**
     * Application release policy, requirement section 4.7 "index snapshot".
     */
    @Getter
    @Setter
    @ToString
    public static class App {

        /** Smallest retention window that still keeps a rollback target besides the current release. */
        private static final int MIN_SNAPSHOT_RETAIN_COUNT = 1;

        /**
         * Retired application versions per application whose index snapshots are kept.
         *
         * <p>Storage cost is the whole subject: every snapshot is a full copy of the corpus of every linked
         * knowledge base, so keeping them forever multiplies disk by the release count. The released version
         * is never counted here and never cleaned - only retired ones age out.
         */
        private int snapshotRetainCount = 3;

        /**
         * Budget of one snapshot copy in milliseconds.
         *
         * <p>Bounds the Qdrant implementation, which copies points rather than hard linking segments, so a
         * release cannot park a request thread on a collection that stopped answering. The Elasticsearch
         * clone is a segment level hard link and finishes in milliseconds.
         */
        private long snapshotTimeoutMs = 300000L;

        /** {@code false} disables the snapshot retention pass, used by tests and read only replicas. */
        private boolean snapshotCleanupEnabled = true;

        /** Cron expression of the snapshot retention pass. */
        private String snapshotCleanupCron = "0 15 4 * * *";

        /**
         * Retention window actually applied by the cleanup.
         *
         * <p>Clamped rather than validated at startup, the same reasoning as the document version window: a
         * typo in a deployment knob must not keep the service from booting.
         *
         * @return retention window, at least one
         */
        public int resolvedSnapshotRetainCount() {
            return Math.max(MIN_SNAPSHOT_RETAIN_COUNT, snapshotRetainCount);
        }
    }

    /**
     * Vector engine selection.
     */
    @Getter
    @Setter
    @ToString
    public static class Vector {

        /** Engine literal, {@code es} for lite mode and {@code qdrant} for full mode. */
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
     * Qdrant connectivity.
     */
    @Getter
    @Setter
    @ToString(exclude = "apiKey")
    public static class Qdrant {

        /** Service URI, blank means Qdrant is not deployed. */
        private String uri = "";

        /** API key, blank for an unauthenticated local deployment. */
        private String apiKey = "";
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

        /**
         * Provider requests in flight while one document is embedded.
         *
         * <p>一个批次就是一次网络往返，串行跑完全部批次是文档索引里最长的一段等待：500 个分片按
         * 批大小 10 算就是 50 次往返，每次半秒到两秒，光嵌入就要半分钟到两分钟。批次之间没有任何
         * 依赖，所以这个值直接摊薄总等待。它是**全局**上限（共享线程池），几个文档同时索引也不会
         * 把嵌入服务的限流打穿；调到 1 就是改造前的串行行为。
         */
        private int concurrency = 4;

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

        /** Request timeout in milliseconds; the budget of the fast control-plane calls (routing, rewrite). */
        private int timeoutMs = 3000;

        /**
         * HTTP read timeout of answer generation in milliseconds.
         *
         * <p>Kept apart from {@link #timeoutMs} on purpose: routing and rewrite bound themselves with
         * that short budget at the future level, but a full answer routinely needs tens of seconds -
         * sharing one value either strangles generation (a real answer died at 3s in production use)
         * or balloons the routing budget. This value only stretches the transport ceiling of the chat
         * client; the fast paths keep their own future-level budgets.
         */
        private int generateTimeoutMs = 60000;

        /** Sampling temperature, zero keeps the rewrite reproducible. */
        private double temperature = 0.0d;

        /** Upper bound of the generated rewrite. */
        private int maxTokens = 128;

        /**
         * Upper bound of a generated answer of the open chat endpoint.
         *
         * <p>Separate from {@link #maxTokens} on purpose: that one sizes a query rewrite, which is a single
         * line, and reusing it for generation would cut every answer off mid sentence.
         */
        private int answerMaxTokens = 1024;
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
     * Multimodal embedding provider configuration, the M14 contract section 6.1.
     *
     * <p>A blank API key switches the whole multimodal capability off: the index pipeline stops
     * producing image vectors and retrieval stops running the third route. The key defaults to the
     * same DashScope credential the rest of the model family shares, so a deployment that already set
     * {@code DASHSCOPE_API_KEY} gets multimodal indexing the moment a knowledge base turns the switch
     * on, with no second credential to provision.
     */
    @Getter
    @Setter
    @ToString(exclude = "apiKey")
    public static class MultimodalEmbedding {

        /** Provider implementation name. */
        private String provider = "dashscope";

        /** Model name. */
        private String model = "multimodal-embedding-v1";

        /** Vector dimension, frozen into the multimodal index at creation time. */
        private int dimension = 1024;

        /** Credential, blank switches the whole multimodal capability off. */
        private String apiKey = "";

        /**
         * Full URL of the native multimodal embedding endpoint. DashScope exposes it outside the
         * OpenAI compatible surface, so unlike text embedding this is a complete URL rather than a
         * base URL, the same shape the rerank endpoint takes.
         */
        private String url =
                "https://dashscope.aliyuncs.com/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding";

        /** Maximum items per provider request. */
        private int batchSize = 8;

        /** Request timeout in milliseconds. */
        private int timeoutMs = 30000;
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

        /**
         * Vision calls in flight while the images of one document are described.
         *
         * <p>One image is one round trip bounded by {@code kb.vision.timeout-ms}, so this is the wall
         * clock divisor of the image stage: a document at {@code maxPerDocument} took the ceiling times
         * that timeout when the calls were serial.
         *
         * <p>Raise it against the provider's rate limit rather than against the machine: the work is
         * entirely waiting on a remote model, and a throttled call is recorded as a failed proxy instead
         * of being retried, so overshooting trades latency for missing text.
         */
        private int describeConcurrency = 8;
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

        /** Corporate directory single sign on. */
        private Ldap ldap = new Ldap();

        /** OpenID Connect single sign on, the M16 contract section 3.3. */
        private Oidc oidc = new Oidc();

        /** SAML 2.0 single sign on, the M16 contract section 3.3. */
        private Saml saml = new Saml();

        /** CAS single sign on, the M16 contract section 3.3. */
        private Cas cas = new Cas();

        /** Settings shared by every browser redirect based protocol. */
        private Sso sso = new Sso();

        /**
         * Corporate directory single sign on.
         *
         * <p>Off by default, and the console hides the single sign on tab when it is: a deployment that
         * has no domain controller must still be able to log in with a local account, and offering a
         * button that can only time out is worse than not offering it.
         */
        @Getter
        @Setter
        @ToString
        public static class Ldap {

            /** Whether single sign on is offered at all. */
            private boolean enabled = false;

            /** Directory URL, {@code ldap://host:389} or {@code ldaps://host:636}. */
            private String url;

            /**
             * Suffix appended to the login name to form the bind principal, {@code @corp.example.com}.
             *
             * <p>Users type the bare login name; the suffix is deployment knowledge, not something to
             * make every person retype on every login.
             */
            private String domainSuffix;

            /** Connect timeout in milliseconds, bounds a login against an unreachable controller. */
            private int connectTimeoutMs = 3000;

            /** Read timeout in milliseconds. */
            private int readTimeoutMs = 5000;

            /**
             * Role code granted to a directory account on its first login.
             *
             * <p>Read only on purpose. The alternative, no role at all, means a person who authenticated
             * successfully lands on an empty console and files a ticket; the alternative in the other
             * direction hands write access to anyone with a domain account.
             *
             * <p>Ignored while group synchronisation is enabled: groups are then the source of truth,
             * and a default pushed into the manually granted set could never be revoked by the sync.
             */
            private String defaultRoleCode = "VIEWER";

            /** Directory group to console role synchronisation, the M16 contract section 6. */
            private GroupSync groupSync = new GroupSync();

            /**
             * Directory group to console role synchronisation.
             *
             * <p>Off by default. Switching it on changes who owns the role set of a single sign on
             * account: every login replaces the synchronised grants with what the mapping derives from
             * the directory groups, while manually granted roles stay untouched.
             */
            @Getter
            @Setter
            @ToString
            public static class GroupSync {

                /** Whether every single sign on login re-derives roles from directory groups. */
                private boolean enabled = false;

                /**
                 * Mapping entries {@code groupDn=ROLE_CODE} separated by semicolons.
                 *
                 * <p>A single string rather than a structured list so the whole mapping fits one
                 * environment variable; distinguished names are compared ignoring case and whitespace.
                 */
                private String roleMappings;
            }
        }

        /**
         * OpenID Connect relying party settings.
         *
         * <p>Only the issuer is asked for; every endpoint is taken from the discovery document at
         * {@code {issuer}/.well-known/openid-configuration} - hand-configured endpoints drift from
         * what the IdP actually serves, and the discovery document is the IdP's own statement.
         */
        @Getter
        @Setter
        @ToString
        public static class Oidc {

            /** Whether the OIDC login button is offered. */
            private boolean enabled = false;

            /** Issuer URL, the base of the discovery document. */
            private String issuer;

            /** Client identifier registered at the IdP. */
            private String clientId;

            /** Client secret registered at the IdP. */
            private String clientSecret;

            /** Requested scopes; {@code openid} is what makes the response an OIDC one. */
            private String scopes = "openid profile email";
        }

        /**
         * SAML 2.0 service provider settings.
         *
         * <p>The IdP certificate is pasted as PEM straight into configuration rather than pulled from
         * a metadata URL: the certificate is the single trust anchor of every assertion, and a
         * metadata fetcher is one more remote dependency whose availability and authenticity would
         * then need their own handling.
         */
        @Getter
        @Setter
        @ToString
        public static class Saml {

            /** Whether the SAML login button is offered. */
            private boolean enabled = false;

            /** Entity id of the identity provider. */
            private String idpEntityId;

            /** Single sign on URL of the identity provider, target of the AuthnRequest redirect. */
            private String idpSsoUrl;

            /** X.509 signing certificate of the identity provider, PEM. */
            private String idpCertificate;

            /** Entity id this deployment presents as service provider. */
            private String spEntityId = "kb-rag";
        }

        /**
         * CAS client settings.
         */
        @Getter
        @Setter
        @ToString
        public static class Cas {

            /** Whether the CAS login button is offered. */
            private boolean enabled = false;

            /** Base URL of the CAS server, for example {@code https://cas.corp.example.com/cas}. */
            private String serverUrl;
        }

        /**
         * Settings shared by the redirect based single sign on protocols.
         */
        @Getter
        @Setter
        @ToString
        public static class Sso {

            /**
             * Base URL of the console the browser is sent back to after a successful callback.
             *
             * <p>Left blank, every protocol counts as unconfigured even when enabled: a callback that
             * cannot say where to deliver the token has nowhere safe to send the browser.
             */
            private String webBaseUrl;
        }
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
                List.of("pdf", "docx", "txt", "md", "sql", "xlsx", "csv",
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
     * Document indexing throughput.
     */
    @Getter
    @Setter
    @ToString
    public static class Index {

        /**
         * Documents indexed at once.
         *
         * <p>索引线程一生都在等外部服务：解析等 kb-rag-parser、嵌入等模型、写入等 ES/Qdrant，本机 CPU 上
         * 只有切分那一小段。所以这个值不该照着机器核数定，而要看**下游能吃下多少**——解析服务的工作线程
         * 数通常是真正的天花板，把这个值调到远超它只会让请求堆在解析服务门口，看起来并发很高实际没变快。
         *
         * <p>与嵌入并发的关系：这个值决定几个文档同时在跑，每个文档内部的嵌入批次再按
         * {@code kb.embedding.concurrency} 并发，而后者是全局上限（共享线程池），所以同时索引的文档会
         * 分摊那几路嵌入而不是各占一份。
         */
        private int concurrency = 4;
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

        /** Default rerank ordering mode, {@code semantic} or {@code hybrid}, the M14 contract section 5. */
        private String rerankMode = "semantic";

        /** Default semantic weight of the {@code hybrid} rerank mode, within {@code [0,1]}. */
        private double rerankWSemantic = 0.7d;

        /**
         * Upper bound of knowledge bases one application version may link, requirement section 4.7.
         *
         * <p>Bounds the retrieval fan out rather than a screen: every linked base costs its own recall
         * round trip on every call, so this is the number the latency promise is computed against.
         */
        private int maxLinkedKb = 15;

        /** Time to live of a routing decision cache entry in minutes. */
        private int routingCacheTtlMinutes = 10;

        /** Maximum entries kept in the routing decision cache. */
        private int routingCacheMaxSize = 10000;

        /**
         * Time to live of a cached version visibility set in minutes.
         *
         * <p>The set is invalidated the moment an activation switches it, so the expiry is only a safety net
         * against a mutation path nobody wired to the invalidation; it is not what keeps the cache correct.
         */
        private int visibleVersionCacheTtlMinutes = 5;

        /** Maximum knowledge bases whose version visibility set is cached. */
        private int visibleVersionCacheMaxSize = 1000;

        /**
         * Multiplier applied to {@code top_n} when sizing the parent candidate target of the two
         * level pipeline; the floor below keeps a small {@code top_n} from starving the merge.
         */
        private int parentCandidateFactor = 3;

        /** Lower bound of the parent candidate target. */
        private int parentCandidateFloor = 20;

        /**
         * Images one call may attach to its query, requirement section 4.8.
         *
         * <p>Bounds the vision fan out rather than a screen: every image costs its own model round trip
         * before the search even starts, so this is the number the latency promise is computed against.
         */
        private int imageQueryMaxCount = 3;

        /** Largest decoded size of a single query image in bytes. */
        private int imageQueryMaxBytes = 5_242_880;

        /**
         * Largest decoded size of all query images of one call in bytes.
         *
         * <p>Not derived from the two above: three images at the per image ceiling would be fifteen
         * megabytes of request body, which is a memory budget rather than an image size question.
         */
        private int imageQueryMaxTotalBytes = 10_485_760;
    }

    /**
     * Annotation workbench policy, requirement section 4.5.
     */
    @Getter
    @Setter
    @ToString
    public static class Annotation {

        /**
         * Lowest Dice coefficient a cross version migration recommendation must reach.
         *
         * <p>Tuned as a recall/noise trade off rather than a correctness one: nothing is migrated
         * automatically, so a low threshold only costs the operator a longer list to read, while a high one
         * hides the moderately edited chunk that is usually the right answer.
         */
        private double migrationMinScore = 0.35d;
    }

    /**
     * Knowledge graph connectivity and traversal defaults, requirement section 4.9.
     *
     * <p>A blank {@link #uri} is the whole switch of the capability: no driver is created, the graph route
     * never runs and the extraction task refuses to start, so a deployment without Neo4j behaves exactly
     * like one that never heard of the graph - the same philosophy the blank model credential follows.
     */
    @Getter
    @Setter
    @ToString(exclude = "password")
    public static class Graph {

        /** Bolt URI, blank disables every graph capability. */
        private String uri = "";

        /** Bolt user. */
        private String user = "neo4j";

        /** Bolt password, blank for an unauthenticated local deployment. */
        private String password = "";

        /** Relationship hops the graph route expands a matched entity by. */
        private int maxHops = 2;

        /** Entities the full text index match of one query keeps, highest scoring first. */
        private int entityMatchLimit = 10;

        /**
         * Model calls in flight inside one extraction task.
         *
         * <p>模型调用是抽取的全部成本，且没有共享状态，所以这个值直接决定一次抽取要跑多久：一万个分片
         * 按每次 3 秒算，并发 2 是四个多小时，并发 8 是一小时出头。图写入已经被收到单写入者线程上串行
         * 执行（见 {@code GraphExtractionService}），并发不再影响 MERGE 的正确性，唯一的约束是模型侧
         * 的限流 —— 同时进行的抽取任务数由 {@link #taskConcurrency} 决定，所以模型侧的峰值并发是
         * 两者之积。
         */
        private int extractConcurrency = 8;

        /**
         * Extraction tasks running at once.
         *
         * <p>抽取任务有自己的线程池（不再挤占索引池），这个值就是池大小。它与 {@link #extractConcurrency}
         * 相乘才是聊天模型看到的峰值并发，而聊天模型的限流通常比嵌入更紧——两个值一起调之前先确认额度。
         *
         * <p>一次抽取按分片规模能跑上小时级，所以这里的意义是"几个知识库能同时重建图谱"，而不是吞吐旋钮：
         * 单个任务的快慢看 {@link #extractConcurrency}。
         */
        private int taskConcurrency = 2;

        /**
         * Generation budget of one extraction call.
         *
         * <p>Separate from {@code kb.chat.max-tokens} because that one sizes a single line query rewrite:
         * an extraction answer is a JSON object holding every entity and relation of the passage, and a
         * budget that runs out mid object yields truncated JSON the parser can only reject - the chunk is
         * then silently counted as skipped. A table heavy passage routinely needs several hundred tokens.
         */
        private int extractMaxTokens = 2048;

        /**
         * Tells whether the deployment runs a graph at all.
         *
         * @return {@code true} when a Bolt URI is configured
         */
        public boolean configured() {
            return uri != null && !uri.isBlank();
        }
    }

    /**
     * Evaluation execution policy, requirement section 4.6 "offline execution profile".
     */
    @Getter
    @Setter
    @ToString
    public static class Eval {

        /**
         * LLM-as-judge model, independent from the generation and rewrite model so a run cannot grade
         * itself with the same model that produced the answer. Blank keeps the chat model configured
         * under {@code kb.chat}.
         */
        private String judgeModel = "";

        /**
         * Timeout applied to the rewrite and rerank stages while an evaluation run executes, replacing
         * their online budgets so a slow but successful call is not counted as a degradation the online
         * P95 promise never had to keep.
         */
        private long offlineTimeoutMs = 10000L;

        /** Cases judged concurrently by one evaluation run. */
        private int concurrency = 4;

        /** Aggregate coverage threshold a span must reach across the top K to count as a hit. */
        private double overlapThreshold = 0.5d;

        /** Automatic retries of a case that came back degraded. */
        private int degradedRetry = 2;
    }

    /**
     * Release gate policy, requirement section 4.7 "sample size and tolerance".
     */
    @Getter
    @Setter
    @ToString
    public static class Gate {

        /**
         * Effective cases the comparison needs before it is trusted; below this the gate records the run
         * instead of deciding on it.
         */
        private int minCases = 50;

        /**
         * Tolerance floor in percentage points. The effective tolerance is
         * {@code max(this / 100, 1 / effectiveCases)}, so a small data set automatically gets a wider band.
         */
        private double epsilonPp = 2.0d;

        /** Pending review ratio above which the data set counts as invalid for gating purposes. */
        private double staleRatio = 0.15d;

        /** Budget of one dual run before the gate gives up and records a retryable outcome. */
        private long runTimeoutMs = 1800000L;

        /** Delay between two polls of the dual run's completion state. */
        private long pollIntervalMs = 2000L;
    }

    /**
     * Open API policy, requirement section 4.8.
     */
    @Getter
    @Setter
    @ToString
    public static class OpenApi {

        /** Token bucket rate assigned to a freshly created API key, in requests per second. */
        private int rateLimitDefault = 10;

        /** Seconds advertised in the {@code Retry-After} header of a rate limited response. */
        private int retryAfterSeconds = 1;

        /** Days an audit row stays queryable in the database before it is archived. */
        private int auditRetentionDays = 180;

        /**
         * Rows one archive batch reads, writes and deletes. Bounded so a retention pass never holds a long
         * transaction over a table the request path keeps inserting into.
         */
        private int auditArchiveBatchSize = 5000;

        /** Cron expression of the daily archive pass. */
        private String auditArchiveCron = "0 30 3 * * *";

        /** {@code false} disables the archive pass entirely, used by tests and read only replicas. */
        private boolean auditArchiveEnabled = true;

        /** Object storage key prefix the archives are written under. */
        private String auditArchivePrefix = "audit/";

        /** Width of the {@code query_digest} column; the digest is truncated to it after masking. */
        private int queryDigestMaxLength = 200;
    }

    /**
     * Search insight policy, the M10 contract section 2.3.
     */
    @Getter
    @Setter
    @ToString
    public static class Insight {

        /**
         * {@code false} short circuits the recording points entirely; the report endpoints still
         * serve whatever history exists. Used by tests and deployments that do not want the table.
         */
        private boolean enabled = true;

        /** Days an insight row stays before the cleanup removes it. Never archived, unlike audit. */
        private int retentionDays = 90;

        /**
         * Rows one cleanup batch deletes. Bounded so a retention pass never holds a long transaction
         * over a table the request path keeps inserting into.
         */
        private int cleanupBatchSize = 5000;

        /** Cron expression of the daily cleanup pass. */
        private String cleanupCron = "0 45 3 * * *";
    }

    /**
     * Operation audit policy, the M16 contract section 7.
     */
    @Getter
    @Setter
    @ToString
    public static class Audit {

        /** Days an operation audit row stays before the cleanup removes it, aligned with the API audit. */
        private int operationRetentionDays = 180;

        /**
         * Rows one cleanup batch deletes. Bounded so a retention pass never holds a long transaction
         * over a table the request path keeps inserting into.
         */
        private int cleanupBatchSize = 5000;

        /** Cron expression of the daily cleanup pass. */
        private String cleanupCron = "0 50 3 * * *";
    }
}
