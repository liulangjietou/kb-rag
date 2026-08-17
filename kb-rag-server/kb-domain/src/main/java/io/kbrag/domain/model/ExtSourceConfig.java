package io.kbrag.domain.model;

/**
 * Connection details one connector call operates on, the M14 contract section 2.1.
 *
 * <p>A plain value instead of the {@code ExtSource} entity so a connector implementation never
 * sees row identity, sync bookkeeping or the credential update rules - it receives exactly what a
 * remote call needs and nothing it could accidentally persist.
 *
 * @param endpoint   remote service endpoint
 * @param region     optional region hint, {@code null} when the connector does not need one
 * @param bucket     connector-specific collection identifier: an object-store bucket or a
 *                   Confluence space key
 * @param prefix     optional connector-specific listing scope, {@code null} for the whole source
 * @param accessKey  public half of the credentials: an object-store access key or Atlassian email
 * @param secretKey  secret half of the credentials: a secret key or Atlassian API token
 * @param maxObjects listing cap per scan; one object beyond it may be returned so the caller can
 *                   tell a full listing from a truncated one
 * @param timeoutMs  connect and read budget of one remote call
 * @param maxContentBytes largest remote body the upload pipeline accepts
 *
 * @author owlzhangfq@gmail.com
 */
public record ExtSourceConfig(
        String endpoint,
        String region,
        String bucket,
        String prefix,
        String accessKey,
        String secretKey,
        int maxObjects,
        int timeoutMs,
        long maxContentBytes) {
}
