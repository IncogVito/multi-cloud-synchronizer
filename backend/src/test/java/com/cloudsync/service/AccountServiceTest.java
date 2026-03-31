package com.cloudsync.service;

import org.junit.jupiter.api.Test;

public class AccountServiceTest {

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
