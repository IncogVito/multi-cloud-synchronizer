package com.cloudsync.service;

import com.cloudsync.model.dto.*;
import com.cloudsync.model.entity.ICloudAccount;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.repository.AccountRepository;
import com.cloudsync.repository.PhotoRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Singleton
public class SetupService {

    private static final Logger LOG = LoggerFactory.getLogger(SetupService.class);
    private static final Set<String> IMAGE_EXTS = Set.of("jpg", "jpeg", "heic", "png", "mov", "mp4");

    private final DiskSetupService diskSetupService;
    private final AccountRepository accountRepository;
    private final PhotoRepository photoRepository;

    public SetupService(DiskSetupService diskSetupService,
                        AccountRepository accountRepository,
                        PhotoRepository photoRepository) {
        this.diskSetupService = diskSetupService;
        this.accountRepository = accountRepository;
        this.photoRepository = photoRepository;
    }

    public BrowseResponse browse(String path) throws IOException {
        Path root;
        if (path != null && !path.isBlank()) {
            root = Path.of(path);
        } else {
            DiskSetupService.DriveStatus status = diskSetupService.getDriveStatus();
            if (!status.mounted() || status.drivePath() == null) {
                throw new IllegalStateException("Disk not mounted");
            }
            root = Path.of(status.drivePath());
        }

        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + root);
        }

        List<BrowseEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, p -> Files.isDirectory(p))) {
            for (Path entry : stream) {
                int childCount = countSubdirs(entry);
                entries.add(new BrowseEntry(
                        entry.getFileName().toString(),
                        entry.toString(),
                        true,
                        childCount
                ));
            }
        }
        entries.sort(Comparator.comparing(BrowseEntry::name));
        return new BrowseResponse(root.toString(), entries);
    }

    public DiskScanResult deepScanFolder(String path) throws IOException {
        Path root = Path.of(path);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + path);
        }

        Map<String, Long> extCounts = new HashMap<>();
        AtomicInteger maxDepth = new AtomicInteger(0);

        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                String ext = getExtension(p).toLowerCase();
                if (IMAGE_EXTS.contains(ext)) {
                    extCounts.merge(ext, 1L, Long::sum);
                    int depth = root.relativize(p).getNameCount();
                    maxDepth.accumulateAndGet(depth, Math::max);
                }
            });
        }

        long total = extCounts.values().stream().mapToLong(x -> x).sum();
        return new DiskScanResult(total, extCounts, maxDepth.get());
    }

    public void saveSyncConfig(String accountId, SyncConfigRequest request) {
        if (request.syncFolderPath() == null || request.syncFolderPath().isBlank()) {
            throw new IllegalArgumentException("syncFolderPath is required");
        }

        Path folderPath = Path.of(request.syncFolderPath());
        if (!Files.isDirectory(folderPath)) {
            throw new IllegalArgumentException("Path is not a directory: " + request.syncFolderPath());
        }

        DiskSetupService.DriveStatus status = diskSetupService.getDriveStatus();
        if (status.mounted() && status.drivePath() != null) {
            if (!folderPath.startsWith(Path.of(status.drivePath()))) {
                throw new IllegalArgumentException("syncFolderPath must be under the mounted disk");
            }
        }

        ICloudAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        account.setSyncFolderPath(request.syncFolderPath());
        account.setStorageDeviceId(request.storageDeviceId());
        account.setOrganizeBy(request.organizeBy() != null ? request.organizeBy() : "MONTH");
        accountRepository.update(account);
        LOG.info("Saved sync config for account {}: folder={}, organizeBy={}", accountId,
                request.syncFolderPath(), account.getOrganizeBy());
    }

    public ReorganizeResult reorganize(String accountId, boolean dryRun) throws IOException {
        ICloudAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        if (account.getSyncFolderPath() == null) {
            throw new IllegalStateException("syncFolderPath not configured for account: " + accountId);
        }

        List<Photo> synced = photoRepository.findByAccountIdAndSyncedToDisk(accountId, true);
        String organizeBy = account.getOrganizeBy() != null ? account.getOrganizeBy() : "MONTH";

        int moved = 0, skipped = 0, errors = 0;
        List<MovePreview> sampleMoves = new ArrayList<>();

        for (Photo photo : synced) {
            if (photo.getFilePath() == null) { errors++; continue; }

            Path current = Path.of(photo.getFilePath());
            if (!Files.exists(current)) { errors++; continue; }

            if (photo.getCreatedDate() == null) { skipped++; continue; }

            LocalDate date = photo.getCreatedDate().atZone(ZoneId.systemDefault()).toLocalDate();
            Path targetDir = "MONTH".equals(organizeBy)
                    ? Path.of(account.getSyncFolderPath(),
                              String.valueOf(date.getYear()),
                              String.format("%02d", date.getMonthValue()))
                    : Path.of(account.getSyncFolderPath(), String.valueOf(date.getYear()));

            Path target = resolveWithoutCollision(targetDir, current.getFileName());

            if (current.equals(target)) { skipped++; continue; }

            if (sampleMoves.size() < 5) {
                sampleMoves.add(new MovePreview(current.toString(), target.toString()));
            }

            if (!dryRun) {
                try {
                    Files.createDirectories(targetDir);
                    Files.move(current, target, StandardCopyOption.ATOMIC_MOVE);
                    photo.setFilePath(target.toString());
                    photoRepository.save(photo);
                } catch (IOException e) {
                    LOG.warn("Failed to move {} → {}: {}", current, target, e.getMessage());
                    errors++;
                    continue;
                }
            }
            moved++;
        }

        return new ReorganizeResult(moved, skipped, errors, dryRun, sampleMoves);
    }

    private int countSubdirs(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return (int) s.filter(Files::isDirectory).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private String getExtension(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    private Path resolveWithoutCollision(Path targetDir, Path filename) {
        Path target = targetDir.resolve(filename);
        if (!Files.exists(target)) return target;

        String name = filename.toString();
        int dotIdx = name.lastIndexOf('.');
        String base = dotIdx >= 0 ? name.substring(0, dotIdx) : name;
        String ext = dotIdx >= 0 ? name.substring(dotIdx) : "";

        int i = 1;
        while (true) {
            Path candidate = targetDir.resolve(base + "_" + i + ext);
            if (!Files.exists(candidate)) return candidate;
            i++;
        }
    }
}
