package com.cloudsync;

import com.cloudsync.repository.AccountRepository;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.data.connection.annotation.Connectable;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class StartupCheck {

    private static final Logger log = LoggerFactory.getLogger(StartupCheck.class);

    private final AccountRepository accountRepository;

    public StartupCheck(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @EventListener
    @Connectable
    public void onStartup(ServerStartupEvent event) {
        long count = accountRepository.count();
        log.info("Repository check OK — accounts in DB: {}", count);
    }
}
