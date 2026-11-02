package br.com.readowl.readowl.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class ApiTestConfiguration {

    @Bean
    RecordingObjectStorage recordingObjectStorage() {
        return new RecordingObjectStorage();
    }

    @Bean
    RecordingMailSender recordingMailSender() {
        return new RecordingMailSender();
    }
}
