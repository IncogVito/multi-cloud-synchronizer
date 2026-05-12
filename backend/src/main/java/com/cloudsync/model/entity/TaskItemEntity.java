package com.cloudsync.model.entity;

import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

@MappedEntity("task_items")
public class TaskItemEntity {

    @Id
    @GeneratedValue
    private Long id;
    private String taskId;
    private String itemStatus;
    private String photoId;
    private String photoName;
    private String errorMessage;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getItemStatus() { return itemStatus; }
    public void setItemStatus(String itemStatus) { this.itemStatus = itemStatus; }

    public String getPhotoId() { return photoId; }
    public void setPhotoId(String photoId) { this.photoId = photoId; }

    public String getPhotoName() { return photoName; }
    public void setPhotoName(String photoName) { this.photoName = photoName; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
