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
    @jakarta.inject.Named("thumbnailDir")
    public String thumbnailDir(@Value("${app.thumbnail-dir:/mnt/external-drive/thumbnails}") String dir) {
        return dir;
    }

    @Bean
    @jakarta.inject.Named("scriptsDir")
    public String scriptsDir(@Value("${app.scripts-dir:/scripts}") String dir) {
        return dir;
    }

    @Bean
    @jakarta.inject.Named("iphoneMountPath")
    public String iphoneMountPath(@Value("${app.iphone-mount-path:/mnt/iphone}") String path) {
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
        int threads = Math.min(4, Runtime.getRuntime().availableProcessors());
        return java.util.concurrent.Executors.newFixedThreadPool(
            threads, Thread.ofVirtual().name("thumb-", 0).factory()
        );
    }
}
