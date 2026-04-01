package com.cloudsync.controller;

import com.cloudsync.model.dto.BatchPhotoRequest;
import com.cloudsync.model.dto.CreateFolderRequest;
import com.cloudsync.model.dto.FolderResponse;
import com.cloudsync.model.dto.PhotoResponse;
import com.cloudsync.model.dto.UpdateFolderRequest;
import com.cloudsync.service.FolderService;
import com.cloudsync.service.PhotoService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

@Tag(name = "Folders")
@Controller("/api/folders")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class FolderController {

    private final FolderService folderService;
    private final PhotoService photoService;

    public FolderController(FolderService folderService, PhotoService photoService) {
        this.folderService = folderService;
        this.photoService = photoService;
    }

    @Operation(summary = "Get folder tree")
    @ApiResponse(responseCode = "200", description = "Folder hierarchy")
    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public List<FolderResponse> getFolderTree() {
        return folderService.getFolderTree();
    }

    @Operation(summary = "Create folder")
    @ApiResponse(responseCode = "201", description = "Folder created")
    @Post
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<FolderResponse> createFolder(@Body CreateFolderRequest request) {
        return HttpResponse.created(folderService.createFolder(request));
    }

    @Operation(summary = "Update folder")
    @ApiResponse(responseCode = "200", description = "Updated")
    @ApiResponse(responseCode = "404", description = "Not found")
    @Put("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<FolderResponse> updateFolder(@PathVariable String id, @Body UpdateFolderRequest request) {
        return folderService.updateFolder(id, request)
                .map(HttpResponse::ok)
                .orElse(HttpResponse.notFound());
    }

    @Operation(summary = "Delete folder")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "Not found")
    @Delete("/{id}")
    public HttpResponse<Void> deleteFolder(@PathVariable String id) {
        return folderService.deleteFolder(id) ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    @Operation(summary = "List photos in folder")
    @ApiResponse(responseCode = "200", description = "Photos in folder")
    @Get("/{id}/photos")
    @Produces(MediaType.APPLICATION_JSON)
    public List<PhotoResponse> getPhotosInFolder(@PathVariable String id) {
        return folderService.getPhotosInFolder(id).stream()
                .map(photoService::toResponse)
                .toList();
    }

    @Operation(summary = "Assign photos to folder")
    @ApiResponse(responseCode = "200", description = "Photos assigned")
    @Post("/{id}/photos")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public HttpResponse<Map<String, Object>> assignPhotos(@PathVariable String id, @Body BatchPhotoRequest request) {
        folderService.assignPhotosToFolder(id, request.photoIds());
        return HttpResponse.ok(Map.of("assigned", request.photoIds().size(), "folderId", id));
    }

    @Operation(summary = "Auto-organize photos into year/month folders")
    @ApiResponse(responseCode = "200", description = "Organization done")
    @Post("/auto-organize")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<Map<String, String>> autoOrganize(
            @QueryValue(defaultValue = "YEAR") String granularity) {
        folderService.autoOrganize(granularity);
        return HttpResponse.ok(Map.of("status", "done", "granularity", granularity));
    }
}
