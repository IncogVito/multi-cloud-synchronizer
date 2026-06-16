package com.cloudsync.service;

import com.cloudsync.client.ICloudServiceClient;
import com.cloudsync.model.entity.ICloudAccount;
import com.cloudsync.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AccountServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock ICloudServiceClient iCloudServiceClient;

    AccountService service() {
        return new AccountService(accountRepository, iCloudServiceClient);
    }

    private ICloudAccount account(String id, String sessionId) {
        ICloudAccount a = new ICloudAccount();
        a.setId(id);
        a.setAppleId(id + "@icloud.com");
        a.setSessionId(sessionId);
        return a;
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Test
    void logout_shouldInvalidateICloudSessionAndClearSessionIdButKeepAccount() {
        ICloudAccount acc = account("acc-1", "sess-123");
        when(accountRepository.findById("acc-1")).thenReturn(Optional.of(acc));

        service().logout("acc-1");

        verify(iCloudServiceClient).deleteSession("sess-123");
        ArgumentCaptor<ICloudAccount> captor = ArgumentCaptor.forClass(ICloudAccount.class);
        verify(accountRepository).update(captor.capture());
        assertThat(captor.getValue().getSessionId()).isNull();
        // record is preserved — never deleted
        verify(accountRepository, never()).deleteById("acc-1");
    }

    @Test
    void logout_shouldStillClearSessionIdWhenICloudServiceFails() {
        ICloudAccount acc = account("acc-1", "sess-123");
        when(accountRepository.findById("acc-1")).thenReturn(Optional.of(acc));
        when(iCloudServiceClient.deleteSession("sess-123"))
                .thenThrow(new RuntimeException("icloud down"));

        service().logout("acc-1");

        ArgumentCaptor<ICloudAccount> captor = ArgumentCaptor.forClass(ICloudAccount.class);
        verify(accountRepository).update(captor.capture());
        assertThat(captor.getValue().getSessionId()).isNull();
    }

    @Test
    void logout_shouldNotCallICloudServiceWhenNoActiveSession() {
        ICloudAccount acc = account("acc-1", null);
        when(accountRepository.findById("acc-1")).thenReturn(Optional.of(acc));

        service().logout("acc-1");

        verify(iCloudServiceClient, never()).deleteSession(org.mockito.ArgumentMatchers.anyString());
        verify(accountRepository).update(acc);
    }

    @Test
    void logout_shouldThrowWhenAccountNotFound() {
        when(accountRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().logout("missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── existing TODO stubs ─────────────────────────────────────────────────────


    @Test
    void login_shouldProxyToICloudServiceAndSaveAccount() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void login_shouldUpdateExistingAccountIfAppleIdAlreadyExists() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void twoFa_shouldDelegateToICloudServiceWithCorrectSessionId() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void twoFa_shouldThrowWhenAccountNotFound() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void listAccounts_shouldReturnAllAccountsFromRepository() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void getAccountStatus_shouldReturnEmptyWhenNotFound() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void deleteAccount_shouldDeleteSessionFromICloudServiceAndRemoveFromDb() {
        // TODO: Implement test – to be completed by secondary model
    }

    @Test
    void deleteAccount_shouldStillDeleteFromDbEvenIfICloudServiceFails() {
        // TODO: Implement test – to be completed by secondary model
    }
}
