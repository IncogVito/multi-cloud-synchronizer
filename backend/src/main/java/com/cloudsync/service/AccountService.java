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
        LOG.debug("[LOGIN] Starting login for appleId: {}", request.appleId());
        
        try {
            Map<String, String> body = Map.of("apple_id", request.appleId(), "password", request.password());
            var response = iCloudServiceClient.login(body);
            Map<String, Object> body2 = response.body();

            String sessionId = String.valueOf(body2.get("session_id"));
            boolean requires2fa = Boolean.parseBoolean(String.valueOf(body2.getOrDefault("requires_2fa", false)));
            LOG.debug("[LOGIN] Session created - requires2fa: {}", requires2fa);

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
            
            LOG.debug("[LOGIN] Login successful for appleId: {}", request.appleId());
            return new LoginResponse(sessionId, requires2fa, account.getId());
            
        } catch (Exception e) {
            LOG.error("[LOGIN] Error during login for appleId: {}", request.appleId(), e);
            throw e;
        }
    }

    public TwoFaResponse twoFa(TwoFaRequest request) {
        LOG.debug("[2FA] Starting 2FA verification for accountId: {}", request.accountId());
        
        try {
            ICloudAccount account = accountRepository.findById(request.accountId())
                    .orElseThrow(() -> {
                        LOG.error("[2FA] Account not found: {}", request.accountId());
                        return new IllegalArgumentException("Account not found: " + request.accountId());
                    });

            Map<String, String> body = Map.of("session_id", account.getSessionId(), "code", request.code());
            var response = iCloudServiceClient.twoFa(body);
            Map<String, Object> responseBody = response.body();

            boolean success = Boolean.parseBoolean(String.valueOf(responseBody.getOrDefault("success", false)));
            String message = String.valueOf(responseBody.getOrDefault("message", ""));
            
            LOG.debug("[2FA] Verification result - success: {}", success);
            return new TwoFaResponse(success, message);
            
        } catch (Exception e) {
            LOG.error("[2FA] Error during 2FA verification for accountId: {}", request.accountId(), e);
            throw e;
        }
    }

    public List<AccountResponse> listAccounts() {
        LOG.debug("[LIST] Fetching all accounts from database");
        try {
            List<AccountResponse> accounts = accountRepository.findAll().stream()
                    .map(this::toResponse)
                    .toList();
            LOG.debug("[LIST] Retrieved {} accounts", accounts.size());
            return accounts;
        } catch (Exception e) {
            LOG.error("[LIST] Error fetching accounts", e);
            throw e;
        }
    }

    public Optional<AccountResponse> getAccountStatus(String id) {
        LOG.debug("[STATUS] Fetching account status for id: {}", id);
        try {
            Optional<AccountResponse> response = accountRepository.findById(id).map(this::toResponse);
            if (response.isEmpty()) {
                LOG.debug("[STATUS] Account not found: {}", id);
            }
            return response;
        } catch (Exception e) {
            LOG.error("[STATUS] Error fetching account status for id: {}", id, e);
            throw e;
        }
    }

    public void deleteAccount(String id) {
        LOG.debug("[DELETE] Starting account deletion for id: {}", id);
        try {
            ICloudAccount account = accountRepository.findById(id)
                    .orElseThrow(() -> {
                        LOG.error("[DELETE] Account not found: {}", id);
                        return new IllegalArgumentException("Account not found: " + id);
                    });

            if (account.getSessionId() != null) {
                try {
                    iCloudServiceClient.deleteSession(account.getSessionId());
                    LOG.debug("[DELETE] Session deleted from iCloud Service");
                } catch (Exception e) {
                    LOG.warn("[DELETE] Failed to delete session from icloud-service: {}", e.getMessage());
                }
            }
            
            accountRepository.deleteById(id);
            LOG.debug("[DELETE] Account successfully deleted: {}", id);
            
        } catch (Exception e) {
            LOG.error("[DELETE] Error during account deletion for id: {}", id, e);
            throw e;
        }
    }

    private AccountResponse toResponse(ICloudAccount account) {
        LOG.debug("[CONVERT] Converting account: appleId={}", account.getAppleId());
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
