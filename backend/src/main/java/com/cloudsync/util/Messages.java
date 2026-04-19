package com.cloudsync.util;

public final class Messages {

    private Messages() {}

    // AccountService — login
    public static final String LOG_LOGIN_START = "[LOGIN] Starting login for appleId: {}";
    public static final String LOG_LOGIN_2FA_REQUIRED = "[LOGIN] Session created - requires2fa: {}";
    public static final String LOG_LOGIN_SUCCESS = "[LOGIN] Login successful for appleId: {}";
    public static final String LOG_LOGIN_ERROR = "[LOGIN] Error during login for appleId: {}";

    // AccountService — 2FA
    public static final String LOG_2FA_START = "[2FA] Starting 2FA verification for accountId: {}";
    public static final String LOG_2FA_ACCOUNT_NOT_FOUND = "[2FA] Account not found: {}";
    public static final String LOG_2FA_RESULT = "[2FA] Verification result - responseBody: {}";
    public static final String LOG_2FA_ERROR = "[2FA] Error during 2FA verification for accountId: {}";

    // AccountService — list
    public static final String LOG_LIST_START = "[LIST] Fetching all accounts from database";
    public static final String LOG_LIST_SUCCESS = "[LIST] Retrieved {} accounts";
    public static final String LOG_LIST_ERROR = "[LIST] Error fetching accounts";

    // AccountService — status
    public static final String LOG_STATUS_START = "[STATUS] Fetching account status for id: {}";
    public static final String LOG_STATUS_NOT_FOUND = "[STATUS] Account not found: {}";
    public static final String LOG_STATUS_ERROR = "[STATUS] Error fetching account status for id: {}";

    // AccountService — delete
    public static final String LOG_DELETE_START = "[DELETE] Starting account deletion for id: {}";
    public static final String LOG_DELETE_ACCOUNT_NOT_FOUND = "[DELETE] Account not found: {}";
    public static final String LOG_DELETE_SESSION_DELETED = "[DELETE] Session deleted from iCloud Service";
    public static final String LOG_DELETE_SESSION_FAILED = "[DELETE] Failed to delete session from icloud-service: {}";
    public static final String LOG_DELETE_SUCCESS = "[DELETE] Account successfully deleted: {}";
    public static final String LOG_DELETE_ERROR = "[DELETE] Error during account deletion for id: {}";

    // AccountService — convert
    public static final String LOG_CONVERT_ACCOUNT = "[CONVERT] Converting account: appleId={}";

    // AccountService — exception messages
    public static final String ERR_ACCOUNT_NOT_FOUND = "Account not found: ";
    public static final String ERR_2FA_VERIFIED = "2FA verified";
    public static final String ERR_2FA_FAILED = "2FA verification failed";

    // SyncService — log messages
    public static final String LOG_SYNC_CANCELLED = "Sync cancelled for account {}";
    public static final String LOG_POLL_INTERRUPTED = "pollMetadataAndContinue interrupted for account {}";
    public static final String LOG_POLL_FAILED = "pollMetadataAndContinue failed for account {}: {}";
    public static final String LOG_COMPARE_FAILED = "compareAndPersist failed for {}: {}";
    public static final String LOG_PHOTOS_MARKED_DELETED = "Marked {} photos as deleted (no longer on iCloud) for account";
    public static final String LOG_BATCH_SAVED = "Saved batch of {} new photos";
    public static final String LOG_BATCH_UPDATED = "Updated batch of {} photos to PENDING";
    public static final String LOG_DOWNLOAD_FAILED = "Failed to move photo {}: {}";
    public static final String LOG_MOVE_FAILED = "Failed to download photo {}: {}";

    // SyncService — fetch status
    public static final String FETCH_STATUS_READY = "ready";
    public static final String FETCH_STATUS_ERROR = "error";
    public static final String MSG_FETCHING_LIST = "Pobieranie listy...";

    // SyncService — exception messages
    public static final String ERR_PHOTO_NOT_FOUND = "Photo not found: ";
    public static final String ERR_NO_ACTIVE_SESSION = "Account has no active session. Please log in first.";
    public static final String ERR_UNKNOWN_PROVIDER = "Unknown sync provider: ";
    public static final String ERR_IPHONE_MISSING_LOCATION = "iPhone photo missing location: ";
}
