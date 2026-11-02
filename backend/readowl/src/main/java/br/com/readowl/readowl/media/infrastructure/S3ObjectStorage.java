package br.com.readowl.readowl.media.infrastructure;

import br.com.readowl.readowl.media.application.ObjectStorage;
import br.com.readowl.readowl.shared.config.ReadowlProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.time.Duration;

/**
 * Qualquer serviço compatível com S3. O endereço por caminho, o corpo em uma só parte e as somas de verificação só quando
 * exigidas são o que os compatíveis aceitam: o padrão do SDK envia um formato que nem todos entendem.
 */
class S3ObjectStorage implements ObjectStorage, AutoCloseable {

    private static final String IMMUTABLE = "public, max-age=31536000, immutable";

    private final S3Client client;
    private final String bucket;

    S3ObjectStorage(ReadowlProperties.Storage storage) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(storage.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.accessKey(), storage.secretKey())))
                .forcePathStyle(true)
                .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(10))
                        .build())
                .overrideConfiguration(configuration -> configuration.apiCallTimeout(Duration.ofSeconds(20)));
        if (!storage.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(storage.endpoint()));
        }
        this.client = builder.build();
        this.bucket = storage.bucket();
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        client.putObject(request -> request.bucket(bucket).key(key).contentType(contentType).cacheControl(IMMUTABLE),
                RequestBody.fromBytes(content));
    }

    @Override
    public void delete(String key) {
        client.deleteObject(request -> request.bucket(bucket).key(key));
    }

    @Override
    public void close() {
        client.close();
    }
}
