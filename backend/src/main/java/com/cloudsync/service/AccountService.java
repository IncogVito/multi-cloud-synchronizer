package com.cloudsync.service;

import com.cloudsync.client.ICloudServiceClient;
import com.cloudsync.model.dto.AccountResponse;
import com.cloudsync.model.dto.LoginRequest;
import com.cloudsync.model.dto.LoginResponse;
import com.cloudsync.model.dto.TwoFaRequest;
import com.cloudsync.model.dto.TwoFaResponse;
import com.cloudsync.model.entity.ICloudAccount;
import com.cloudsync.repository.AccountRepository;
import com.cloudsync.util.Messages;
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
        LOG.debug(Messages.LOG_LOGIN_START, request.appleId());
        
        try {
            // FIX: Use dedicated body
            Map<String, String> body = Map.of("apple_id", request.appleId(), "password", request.password());
            var response = iCloudServiceClient.login(body);
            Map<String, Object> responseBody = response.body();

            String sessionId = String.valueOf(responseBody.get("session_id"));
            boolean requires2fa = Boolean.parseBoolean(String.valueOf(responseBody.getOrDefault("requires_2fa", false)));
            LOG.debug(Messages.LOG_LOGIN_2FA_REQUIRED, requires2fa);

            Optional<ICloudAccount> existing = accountRepository.findByAppleId(request.appleId());
            ICloudAccount account = existing.orElseGet(() -> {
                ICloudAccount a = new ICloudAccount();
                a.setId(UUID.randomUUID().toString());
                a.setAppleId(request.appleId());
                a.setCreatedAt(Instant.now());
                return a;
            });
            
            account.setSessionId(sessionId);
            account.setDisplayName(String.valueOf(responseBody.getOrDefault("display_name", request.appleId())));

            if (existing.isPresent()) {
                accountRepository.update(account);
            } else {
                accountRepository.save(account);
            }
            
            LOG.debug(Messages.LOG_LOGIN_SUCCESS, request.appleId());
            return new LoginResponse(sessionId, requires2fa, account.getId());
            
        } catch (Exception e) {
            LOG.error(Messages.LOG_LOGIN_ERROR, request.appleId(), e);
            throw e;
        }
    }

    public TwoFaResponse twoFa(TwoFaRequest request) {
        LOG.debug(Messages.LOG_2FA_START, request.accountId());
        
        try {
            ICloudAccount account = accountRepository.findById(request.accountId())
                    .orElseThrow(() -> {
                        LOG.error(Messages.LOG_2FA_ACCOUNT_NOT_FOUND, request.accountId());
                        return new IllegalArgumentException(Messages.ERR_ACCOUNT_NOT_FOUND + request.accountId());
                    });

            Map<String, String> body = Map.of("session_id", account.getSessionId(), "code", request.code());
            var response = iCloudServiceClient.twoFa(body);
            Map<String, Object> responseBody = response.body();


            boolean success = Boolean.parseBoolean(String.valueOf(responseBody.getOrDefault("authenticated", false)));
            String message = success ? Messages.ERR_2FA_VERIFIED : Messages.ERR_2FA_FAILED;

            LOG.debug(Messages.LOG_2FA_RESULT, responseBody);
            return new TwoFaResponse(success, message);
            
        } catch (Exception e) {
            LOG.error(Messages.LOG_2FA_ERROR, request.accountId(), e);
            throw e;
        }
    }

    public List<AccountResponse> listAccounts() {
        LOG.debug(Messages.LOG_LIST_START);
        try {
            List<AccountResponse> accounts = accountRepository.findAll().stream()
                    .map(this::toResponse)
                    .toList();
            LOG.debug(Messages.LOG_LIST_SUCCESS, accounts.size());
            return accounts;
        } catch (Exception e) {
            LOG.error(Messages.LOG_LIST_ERROR, e);
            throw e;
        }
    }

    public Optional<AccountResponse> getAccountStatus(String id) {
        LOG.debug(Messages.LOG_STATUS_START, id);
        try {
            Optional<AccountResponse> response = accountRepository.findById(id).map(this::toResponse);
            if (response.isEmpty()) {
                LOG.debug(Messages.LOG_STATUS_NOT_FOUND, id);
            }
            return response;
        } catch (Exception e) {
            LOG.error(Messages.LOG_STATUS_ERROR, id, e);
            throw e;
        }
    }

    public void deleteAccount(String id) {
        LOG.debug(Messages.LOG_DELETE_START, id);
        try {
            ICloudAccount account = accountRepository.findById(id)
                    .orElseThrow(() -> {
                        LOG.error(Messages.LOG_DELETE_ACCOUNT_NOT_FOUND, id);
                        return new IllegalArgumentException(Messages.ERR_ACCOUNT_NOT_FOUND + id);
                    });

            if (account.getSessionId() != null) {
                try {
                    iCloudServiceClient.deleteSession(account.getSessionId());
                    LOG.debug(Messages.LOG_DELETE_SESSION_DELETED);
                } catch (Exception e) {
                    LOG.warn(Messages.LOG_DELETE_SESSION_FAILED, e.getMessage());
                }
            }
            
            accountRepository.deleteById(id);
            LOG.debug(Messages.LOG_DELETE_SUCCESS, id);
            
        } catch (Exception e) {
            LOG.error(Messages.LOG_DELETE_ERROR, id, e);
            throw e;
        }
    }

    private AccountResponse toResponse(ICloudAccount account) {
        LOG.debug(Messages.LOG_CONVERT_ACCOUNT, account.getAppleId());
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
