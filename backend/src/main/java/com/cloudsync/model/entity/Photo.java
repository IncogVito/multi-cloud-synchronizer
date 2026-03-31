package com.cloudsync.model.entity;

import com.cloudsync.model.enums.MediaType;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.Relation;

import java.time.Instant;

@MappedEntity("photos")
public class Photo {

    @Id
    private String id;

    private String icloudPhotoId;
    private String accountId;
    private String filename;
    private String filePath;
    private String thumbnailPath;
    private Long fileSize;
    private Integer width;
    private Integer height;
    private Instant createdDate;
    private Instant importedDate;
    private String checksum;
    private boolean syncedToDisk;
    private boolean existsOnIcloud;
    private Boolean existsOnIphone;
    private String mediaType;

    public Photo() {
        this.syncedToDisk = false;
        this.existsOnIcloud = true;
        this.mediaType = MediaType.PHOTO.name();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getIcloudPhotoId() { return icloudPhotoId; }
    public void setIcloudPhotoId(String icloudPhotoId) { this.icloudPhotoId = icloudPhotoId; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getThumbnailPath() { return thumbnailPath; }
    public void setThumbnailPath(String thumbnailPath) { this.thumbnailPath = thumbnailPath; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }

    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }

    public Instant getCreatedDate() { return createdDate; }
    public void setCreatedDate(Instant createdDate) { this.createdDate = createdDate; }

    public Instant getImportedDate() { return importedDate; }
    public void setImportedDate(Instant importedDate) { this.importedDate = importedDate; }

    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }

    public boolean isSyncedToDisk() { return syncedToDisk; }
    public void setSyncedToDisk(boolean syncedToDisk) { this.syncedToDisk = syncedToDisk; }

    public boolean isExistsOnIcloud() { return existsOnIcloud; }
    public void setExistsOnIcloud(boolean existsOnIcloud) { this.existsOnIcloud = existsOnIcloud; }

    public Boolean getExistsOnIphone() { return existsOnIphone; }
    public void setExistsOnIphone(Boolean existsOnIphone) { this.existsOnIphone = existsOnIphone; }

    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
}
