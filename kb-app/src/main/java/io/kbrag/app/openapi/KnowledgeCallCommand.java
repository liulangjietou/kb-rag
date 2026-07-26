package io.kbrag.app.openapi;

import io.kbrag.domain.model.ChatMessage;
import io.kbrag.domain.model.MetadataFilter;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;
import java.util.Set;

/**
 * One open API call as the caller expressed it.
 *
 * <p>Only the four white listed override fields are modelled; every other retrieval parameter comes from the
 * application version snapshot and has no field here at all, which is the structural half of the white list
 * rule (requirement section 5). The textual half is {@link #presentedOverrideKeys}: the transport layer
 * collects the names of any other tuning field the payload carried, and the service rejects the call rather
 * than ignoring them - an agent that believes its tuning took effect would draw wrong conclusions from the
 * results.
 *
 * @author owlzhangfq@gmail.com
 */
@Getter
@Builder
@ToString(exclude = {"query", "messages"})
public class KnowledgeCallCommand {

    /** Application business id. */
    private final String appId;

    /** Requested application version literal, {@code null} routes to the released version. */
    private final String appVersion;

    /** User query, mandatory. */
    private final String query;

    /** Conversation history the caller carries, used by the multi turn rewrite stage. */
    private final List<ChatMessage> messages;

    /** White listed override: number of returned nodes. */
    private final Integer topN;

    /** White listed override: absolute score threshold. */
    private final Double scoreThreshold;

    /** White listed override: narrowing metadata predicate. */
    private final MetadataFilter metadataFilter;

    /** White listed override: character budget of the returned content. */
    private final Integer maxContentLength;

    /** {@code true} asks the chat endpoint for a server sent event stream. */
    private final boolean stream;

    /** Names of every tuning field the payload carried, white listed or not. */
    private final Set<String> presentedOverrideKeys;
}
