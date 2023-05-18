package org.folio.service.export.storage;

import org.folio.s3.client.FolioS3Client;
import org.folio.s3.client.S3ClientFactory;
import org.folio.s3.client.S3ClientProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FolioS3ClientFactory {

  @Value("${minio.endpoint}")
  private String endpoint;

  @Value("${minio.accessKey}")
  private String accessKey;

  @Value("${minio.secretKey}")
  private String secretKey;

  @Value("${minio.bucket}")
  private String bucket;

  @Value("${minio.region}")
  private String region;

  @Value("#{ T(Boolean).parseBoolean('${minio.awsSdk}')}")
  private boolean awsSdk;

  private FolioS3Client folioS3Client; //NOSONAR

  public FolioS3Client getFolioS3Client() {
    if (folioS3Client != null) {
      return folioS3Client;
    }
    folioS3Client = createFolioS3Client();
    return folioS3Client;
  }

  private FolioS3Client createFolioS3Client() {
    return S3ClientFactory.getS3Client(S3ClientProperties.builder()
      .endpoint(endpoint)
      .secretKey(secretKey)
      .accessKey(accessKey)
      .bucket(bucket)
      .awsSdk(awsSdk)
      .region(region)
      .build());
  }
}
