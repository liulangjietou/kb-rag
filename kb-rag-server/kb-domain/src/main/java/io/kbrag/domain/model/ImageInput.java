package io.kbrag.domain.model;

/**
 * One image handed to the multimodal embedding provider, the M14 contract section 6.1.
 *
 * <p>Carries the raw bytes rather than an object key so the provider layer stays free of any storage
 * dependency: the caller reads the bytes from private storage and the provider only ever sees a byte
 * array and its media type. That is what lets the same port serve a data URL inlining implementation
 * and, should a vendor reject inlined bytes, a pre signed URL one, without either detail leaking into
 * the index pipeline.
 *
 * @param content   raw image bytes, never empty
 * @param mediaType MIME type of the bytes, {@code image/png} assumed by the provider when blank
 *
 * @author owlzhangfq@gmail.com
 */
public record ImageInput(byte[] content, String mediaType) {
}
