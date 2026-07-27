package io.kbrag.api.dto;

/**
 * Optional payload of a mapping profile copy.
 *
 * @param name name of the copy, {@code null} or blank generating one from the source name
 *
 * @author owlzhangfq@gmail.com
 */
public record SourceMappingCopyRequest(String name) {
}
