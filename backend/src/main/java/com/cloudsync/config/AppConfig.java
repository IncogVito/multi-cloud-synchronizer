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
}
