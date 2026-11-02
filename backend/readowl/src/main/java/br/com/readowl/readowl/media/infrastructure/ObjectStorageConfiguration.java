package br.com.readowl.readowl.media.infrastructure;

import br.com.readowl.readowl.shared.config.ReadowlProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ObjectStorageConfiguration {

    /**
     * Com o armazenamento pela metade, a aplicação não sobe: fotos falhariam só quando alguém tentasse enviar.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("!'${readowl.storage.bucket:}'.isBlank()")
    S3ObjectStorage objectStorage(ReadowlProperties properties) {
        ReadowlProperties.Storage storage = properties.storage();
        if (storage.region().isBlank() || storage.accessKey().isBlank() || storage.secretKey().isBlank()
                || storage.publicUrl().isBlank()) {
            throw new IllegalStateException("Com S3_BUCKET, defina também S3_REGION, S3_ACCESS_KEY, S3_SECRET_KEY e S3_PUBLIC_URL");
        }
        return new S3ObjectStorage(storage);
    }
}
