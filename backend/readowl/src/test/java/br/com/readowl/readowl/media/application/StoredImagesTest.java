package br.com.readowl.readowl.media.application;

import br.com.readowl.readowl.shared.exception.ApiException;
import br.com.readowl.readowl.support.TestImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sem o armazenamento configurado, o que os testes de integração não exercitam, porque sempre têm um.
 */
class StoredImagesTest {

    private static final String LINK = "https://i.imgur.com/a.png";

    private RemoteImages remote;
    private StoredImages images;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<ObjectStorage> noStorage = mock(ObjectProvider.class);
        remote = mock(RemoteImages.class);
        ExternalImages external = mock(ExternalImages.class);
        when(external.isAllowed(LINK)).thenReturn(true);
        images = new StoredImages(noStorage, remote, external, null, mock(ApplicationEventPublisher.class));
    }

    @Test
    void aFileIsRefusedWithoutStorage() {
        assertCode(() -> images.fromFile(StoredImageType.COVER, UUID.randomUUID(), TestImages.png(600, 800)), "IMAGES_UNAVAILABLE");
    }

    @Test
    void aLinkIsNotDownloadedWithoutStorage() {
        assertCode(() -> images.fromLink(StoredImageType.COVER, UUID.randomUUID(), LINK), "IMAGES_UNAVAILABLE");

        verify(remote, never()).download(anyString());
    }

    @Test
    void aBadLinkIsReportedAsSuchEvenWithoutStorage() {
        assertCode(() -> images.fromLink(StoredImageType.COVER, UUID.randomUUID(), "https://fora.example.com/a.png"), "INVALID_COVER_URL");
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(code);
    }
}
