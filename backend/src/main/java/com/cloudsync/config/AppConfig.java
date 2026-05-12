package com.cloudsync.config;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;

@Factory
public class AppConfig {

    @Bean
    @jakarta.inject.Named("appUsername")
    public String appUsername(@Value("${app.username:admin}") String username) {
        return username;
    }

    @Bean
    @jakarta.inject.Named("appPassword")
    public String appPassword(@Value("${app.password:changeme}") String password) {
        return password;
    }

    @Bean
    @jakarta.inject.Named("externalDrivePath")
    public String externalDrivePath(@Value("${app.external-drive-path}") String path) {
        return path;
    }

    @Bean
    @jakarta.inject.Named("thumbnailDir")
    public String thumbnailDir(@Value("${app.external-drive-path}") String externalDrivePath) {
        return externalDrivePath + "/thumbnails";
    }

    @Bean
    @jakarta.inject.Named("scriptsDir")
    public String scriptsDir(@Value("${app.scripts-dir:/scripts}") String dir) {
        return dir;
    }

    @Bean
    @jakarta.inject.Named("iphoneHostMountPath")
    public String iphoneHostMountPath(@Value("${app.iphone-host-mount-path}") String path) {
        return path;
    }

    @Bean
    @jakarta.inject.Named("iphoneContainerPath")
    public String iphoneContainerPath(@Value("${app.iphone-container-path}") String path) {
        return path;
    }

    @Bean
    @jakarta.inject.Named("syncVirtualThreadExecutor")
    public java.util.concurrent.ExecutorService syncVirtualThreadExecutor() {
        return java.util.concurrent.Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("sync-vt-", 0).factory()
        );
    }

    @Bean
    @jakarta.inject.Named("thumbnailExecutor")
    public java.util.concurrent.ExecutorService thumbnailExecutor() {
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        return java.util.concurrent.Executors.newFixedThreadPool(threads,
            Thread.ofPlatform().name("thumbnail-", 0).factory());
    }
}
