package io.kbrag.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Optional body of a batch confirmation.
 *
 * @param docIds documents to confirm, absent or empty confirms every waiting document of the knowledge base
 *
 * @author owlzhangfq@gmail.com
 */
public record ConfirmDocumentsRequest(@JsonProperty("doc_ids") List<String> docIds) {
}
