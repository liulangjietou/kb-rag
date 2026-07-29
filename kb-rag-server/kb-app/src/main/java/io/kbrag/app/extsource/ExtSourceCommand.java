package io.kbrag.app.extsource;

/**
 * Application layer value carrying the writable fields of one external source, used by both
 * register and update so the two endpoints share one shape.
 *
 * @param sourceType connector type routing key, {@code s3} in this milestone; ignored on update
 * @param name       operator facing display name, unique per knowledge base
 * @param endpoint   service endpoint of the object store
 * @param region     optional region hint, {@code null} when the store does not need one
 * @param bucket     bucket the scan lists
 * @param prefix     optional key prefix narrowing the scan
 * @param accessKey  access key of the credentials
 * @param secretKey  secret key of the credentials; blank on update keeps the stored one
 * @param syncEnabled whether the scheduled pass includes this source, {@code null} keeps current
 *
 * @author owlzhangfq@gmail.com
 */
public record ExtSourceCommand(
        String sourceType,
        String name,
        String endpoint,
        String region,
        String bucket,
        String prefix,
        String accessKey,
        String secretKey,
        Boolean syncEnabled) {
}
