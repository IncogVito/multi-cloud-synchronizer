package com.cloudsync.repository;

import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Raw JDBC repository for photo_folder_assignments (composite PK – not supported by Micronaut Data JDBC).
 */
@Singleton
public class PhotoFolderAssignmentRepository {

    private final DataSource dataSource;

    public PhotoFolderAssignmentRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void assign(String photoId, String folderId) {
        String sql = "INSERT OR IGNORE INTO photo_folder_assignments (photo_id, folder_id) VALUES (?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, photoId);
            stmt.setString(2, folderId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to assign photo to folder", e);
        }
    }

    public void unassign(String photoId, String folderId) {
        String sql = "DELETE FROM photo_folder_assignments WHERE photo_id = ? AND folder_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, photoId);
            stmt.setString(2, folderId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to unassign photo from folder", e);
        }
    }

    public List<String> findPhotoIdsByFolderId(String folderId) {
        String sql = "SELECT photo_id FROM photo_folder_assignments WHERE folder_id = ?";
        List<String> ids = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, folderId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                ids.add(rs.getString("photo_id"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query photo-folder assignments", e);
        }
        return ids;
    }

    public List<String> findFolderIdsByPhotoId(String photoId) {
        String sql = "SELECT folder_id FROM photo_folder_assignments WHERE photo_id = ?";
        List<String> ids = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, photoId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                ids.add(rs.getString("folder_id"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query folder-photo assignments", e);
        }
        return ids;
    }

    public void deleteAllByPhotoId(String photoId) {
        String sql = "DELETE FROM photo_folder_assignments WHERE photo_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, photoId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete assignments for photo", e);
        }
    }

    public void deleteAllByFolderId(String folderId) {
        String sql = "DELETE FROM photo_folder_assignments WHERE folder_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, folderId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete assignments for folder", e);
        }
    }
}
