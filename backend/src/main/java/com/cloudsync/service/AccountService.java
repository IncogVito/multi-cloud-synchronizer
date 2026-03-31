package com.cloudsync.service;

import com.cloudsync.client.ICloudServiceClient;
import com.cloudsync.model.dto.AccountResponse;
import com.cloudsync.model.dto.LoginRequest;
import com.cloudsync.model.dto.LoginResponse;
import com.cloudsync.model.dto.TwoFaRequest;
import com.cloudsync.model.dto.TwoFaResponse;
import com.cloudsync.model.entity.ICloudAccount;
import com.cloudsync.repository.AccountRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

@Singleton
public class AccountService {

    private static final Logger LOG = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final ICloudServiceClient iCloudServiceClient;

    public AccountService(AccountRepository accountRepository, ICloudServiceClient iCloudServiceClient) {
        this.accountRepository = accountRepository;
        this.iCloudServiceClient = iCloudServiceClient;
    }

    public LoginResponse login(LoginRequest request) {
        Map<String, String> body = Map.of("apple_id", request.appleId(), "password", request.password());
        var response = iCloudServiceClient.login(body);
        Map<String, Object> body2 = response.body();

        String sessionId = String.valueOf(body2.get("session_id"));
        boolean requires2fa = Boolean.parseBoolean(String.valueOf(body2.getOrDefault("requires_2fa", false)));

        // Upsert account in DB
        Optional<ICloudAccount> existing = accountRepository.findByAppleId(request.appleId());
        ICloudAccount account = existing.orElseGet(() -> {
            ICloudAccount a = new ICloudAccount();
            a.setId(UUID.randomUUID().toString());
            a.setAppleId(request.appleId());
            a.setCreatedAt(Instant.now());
            return a;
        });
        account.setSessionId(sessionId);
        account.setDisplayName(String.valueOf(body2.getOrDefault("display_name", request.appleId())));

        if (existing.isPresent()) {
            accountRepository.update(account);
        } else {
            accountRepository.save(account);
        }

        return new LoginResponse(sessionId, requires2fa, account.getId());
    }

    public TwoFaResponse twoFa(TwoFaRequest request) {
        ICloudAccount account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + request.accountId()));

        Map<String, String> body = Map.of("session_id", account.getSessionId(), "code", request.code());
        var response = iCloudServiceClient.twoFa(body);
        Map<String, Object> responseBody = response.body();

        boolean success = Boolean.parseBoolean(String.valueOf(responseBody.getOrDefault("success", false)));
        String message = String.valueOf(responseBody.getOrDefault("message", ""));

        return new TwoFaResponse(success, message);
    }

    public List<AccountResponse> listAccounts() {
        return StreamSupport.stream(accountRepository.findAll().spliterator(), false)
                .map(this::toResponse)
                .toList();
    }

    public Optional<AccountResponse> getAccountStatus(String id) {
        return accountRepository.findById(id).map(this::toResponse);
    }

    public void deleteAccount(String id) {
        ICloudAccount account = accountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + id));

        if (account.getSessionId() != null) {
            try {
                iCloudServiceClient.deleteSession(account.getSessionId());
            } catch (Exception e) {
                LOG.warn("Failed to delete session {} from icloud-service: {}", account.getSessionId(), e.getMessage());
            }
        }
        accountRepository.deleteById(id);
    }

    private AccountResponse toResponse(ICloudAccount account) {
        return new AccountResponse(
                account.getId(),
                account.getAppleId(),
                account.getDisplayName(),
                account.getSessionId() != null,
                account.getLastSyncAt(),
                account.getCreatedAt()
        );
    }
}
