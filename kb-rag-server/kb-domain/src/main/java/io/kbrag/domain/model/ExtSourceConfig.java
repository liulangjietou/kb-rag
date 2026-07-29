package io.kbrag.domain.model;

/**
 * Connection details one connector call operates on, the M14 contract section 2.1.
 *
 * <p>A plain value instead of the {@code ExtSource} entity so a connector implementation never
 * sees row identity, sync bookkeeping or the credential update rules - it receives exactly what a
 * remote call needs and nothing it could accidentally persist.
 *
 * @param endpoint   service endpoint of the object store
 * @param region     optional region hint, {@code null} when the store does not need one
 * @param bucket     bucket to list and fetch from
 * @param prefix     optional key prefix narrowing a listing, {@code null} for the whole bucket
 * @param accessKey  access key of the credentials
 * @param secretKey  secret key of the credentials
 * @param maxObjects listing cap per scan; one object beyond it may be returned so the caller can
 *                   tell a full listing from a truncated one
 * @param timeoutMs  connect and read budget of one remote call
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
        int timeoutMs) {
}
