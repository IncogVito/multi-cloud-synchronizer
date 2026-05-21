package com.cloudsync.model.dto;

/**
 * Provider-agnostic delete request: remote photo ID plus optional CloudKit asset record name
 * for O(1) direct delete (iCloud only; null for iPhone/other providers).
 */
public record PhotoDeleteItem(String photoId, String assetRecordName) {}
