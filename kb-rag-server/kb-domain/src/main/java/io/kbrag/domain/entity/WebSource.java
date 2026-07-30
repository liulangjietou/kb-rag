package io.kbrag.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.kbrag.domain.enums.WebSourceFetchStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * A registered web page source, the M12 contract section 1: the binding between a URL and the
 * document its fetches produce, plus the sync switch and the outcome of the last fetch.
 *
 * <p>The binding is deliberately weak. Removing the registration leaves the document alone, and
 * trashing the document leaves the registration alone - the two lifecycles meet only inside a sync
 * pass, which reads both sides and decides what one fetch may do.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ToString(callSuper = true)
@TableName("t_kb_web_source")
public class WebSource extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** Business identifier exposed through the API. */
    @TableField("source_id")
    private String sourceId;

    /** Knowledge base the fetched pages land in. */
    @TableField("kb_id")
    private String kbId;

    /** Registered page address, http or https. */
    @TableField("url")
    private String url;

    /** SHA-256 of the URL; the equality key a VARCHAR(2048) column cannot be. */
    @TableField("url_hash")
    private String urlHash;

    /** Document the fetches feed, {@code null} until the first successful fetch. */
    @TableField("doc_id")
    private String docId;

    /** Derived stable file name the upload chain sees, {@code null} until the first fetch. */
    @TableField("file_name")
    private String fileName;

    /** {@code 1} includes the source in the scheduled sync pass. */
    @TableField("sync_enabled")
    private Integer syncEnabled;

    /** {@code 1} fetches this source through the headless browser and stores the rendered DOM, the M17 contract section 1. */
    @TableField("render_js")
    private Integer renderJs;

    /** SHA-256 of the last fetched body, the unchanged check of an incremental sync. */
    @TableField("last_content_hash")
    private String lastContentHash;

    /** When the last sync attempt ran, success or not. */
    @TableField("last_fetch_at")
    private LocalDateTime lastFetchAt;

    /** Outcome of the last sync attempt. */
    @TableField("last_fetch_status")
    private WebSourceFetchStatus lastFetchStatus;

    /** Why the last sync failed or was skipped, {@code null} on success. */
    @TableField("last_error")
    private String lastError;
}
