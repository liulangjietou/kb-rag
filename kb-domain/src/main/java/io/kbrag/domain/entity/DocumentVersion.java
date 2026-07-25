package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.DocumentVersionStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * One immutable build of a document: original object, parse artifact and the six element
 * reuse fingerprint.
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_document_version")
public class DocumentVersion extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API. */
    @TableField("version_id")
    private String versionId;

    /** Owning document business id. */
    @TableField("doc_id")
    private String docId;

    /** Version number in {@code major.minor} form, starts at 1.0. */
    @TableField("version")
    private String version;

    /** MinIO object key of the original file. */
    @TableField("minio_object")
    private String minioObject;

    /** MinIO object key of the parsed markdown, {@code null} until parsing succeeds. */
    @TableField("parsed_object")
    private String parsedObject;

    /** SHA-256 of the original byte stream. */
    @TableField("content_hash")
    private String contentHash;

    /** Fingerprint of the parse stage inputs. */
    @TableField("parse_fingerprint")
    private String parseFingerprint;

    /** Fingerprint of the split stage inputs. */
    @TableField("chunk_fingerprint")
    private String chunkFingerprint;

    /** Embedding model version this build was produced with, {@code none} in zero key mode. */
    @TableField("embedding_version")
    private String embeddingVersion;

    /** Build lifecycle state. */
    @TableField("status")
    private DocumentVersionStatus status;

    /** 1 for the single active version of a document, {@code null} otherwise. */
    @TableField("active_flag")
    private Integer activeFlag;

    /** Change note shown in the version list. */
    @TableField("changelog")
    private String changelog;
}
