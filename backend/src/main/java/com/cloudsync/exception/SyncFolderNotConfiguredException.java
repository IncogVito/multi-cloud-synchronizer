package com.cloudsync.exception;

import com.cloudsync.util.Messages;

/**
 * Thrown when a reorganize operation is requested for an account that has no
 * {@code syncFolderPath} configured yet. Without a base folder there is nothing
 * to reorganize against, so this surfaces as a 400 rather than an NPE/500.
 */
public class SyncFolderNotConfiguredException extends RuntimeException {

    public SyncFolderNotConfiguredException(String accountId) {
        super(Messages.ERR_NO_SYNC_FOLDER + accountId);
    }
}
