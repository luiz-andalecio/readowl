package br.com.readowl.readowl.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class ApiTestConfiguration {

    @Bean
    RecordingObjectStorage recordingObjectStorage() {
        return new RecordingObjectStorage();
    }

    @Bean
    @Primary
    FakeRemoteImages fakeRemoteImages() {
        return new FakeRemoteImages();
    }

    @Bean
    RecordingMailSender recordingMailSender() {
        return new RecordingMailSender();
    }
}
