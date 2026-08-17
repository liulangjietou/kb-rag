package io.kbrag.app.extsource;

/**
 * Application layer value carrying the writable fields of one external source, used by both
 * register and update so the two endpoints share one shape.
 *
 * @param sourceType connector type routing key ({@code s3}/{@code confluence}); ignored on update
 * @param name       operator facing display name, unique per knowledge base
 * @param endpoint   remote service endpoint
 * @param region     optional connector-specific region hint
 * @param bucket     collection identifier: bucket name or Confluence space key
 * @param prefix     optional connector-specific listing prefix
 * @param accessKey  public credential half: access key or Atlassian account email
 * @param secretKey  secret credential half; blank on update keeps the stored one
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
