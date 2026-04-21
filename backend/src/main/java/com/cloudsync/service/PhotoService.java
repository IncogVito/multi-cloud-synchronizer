package com.cloudsync.service;

import com.cloudsync.model.dto.MonthSummaryResponse;
import com.cloudsync.model.dto.PhotoListResponse;
import com.cloudsync.model.dto.PhotoResponse;
import com.cloudsync.model.entity.Photo;
import com.cloudsync.repository.PhotoRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Singleton
public class PhotoService {

    private final PhotoRepository photoRepository;
    private final AppContextService appContextService;

    public PhotoService(PhotoRepository photoRepository, AppContextService appContextService) {
        this.photoRepository = photoRepository;
        this.appContextService = appContextService;
    }

    /**
     * Groups synced photos by calendar month and returns a summary for each.
     * Results are sorted newest-month-first.
     *
     * @param storageDeviceId required — only photos on this device are included
     * @param accountId       optional — when non-null only photos from this account are included
     */
    public List<MonthSummaryResponse> getMonthsSummary(String storageDeviceId, String accountId) {
        appContextService.requireActive();

        List<Photo> syncedPhotos = photoRepository.findByStorageDeviceIdAndSyncedToDisk(storageDeviceId, true);

        List<Photo> filteredPhotos = accountId != null
                ? syncedPhotos.stream().filter(p -> accountId.equals(p.getAccountId())).toList()
                : syncedPhotos;

        Map<String, List<Photo>> photosByYearMonth = filteredPhotos.stream()
                .filter(p -> p.getCreatedDate() != null)
                .collect(Collectors.groupingBy(p -> formatYearMonth(p.getCreatedDate())));

        DateTimeFormatter labelFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

        return photosByYearMonth.entrySet().stream()
                .map(entry -> buildMonthSummary(entry.getKey(), entry.getValue(), labelFormatter))
                .sorted(Comparator.comparing(MonthSummaryResponse::yearMonth).reversed())
                .toList();
    }

    private MonthSummaryResponse buildMonthSummary(
            String yearMonth,
            List<Photo> monthPhotos,
            DateTimeFormatter labelFormatter) {

        long totalSizeBytes = monthPhotos.stream()
                .mapToLong(p -> p.getFileSize() != null ? p.getFileSize() : 0L)
                .sum();
        Instant earliestDate = monthPhotos.stream()
                .map(Photo::getCreatedDate)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
        Instant latestDate = monthPhotos.stream()
                .map(Photo::getCreatedDate)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        long icloudOnlyCount = monthPhotos.stream()
                .filter(p -> p.isExistsOnIcloud() && !Boolean.TRUE.equals(p.getExistsOnIphone()))
                .count();
        long iphoneOnlyCount = monthPhotos.stream()
                .filter(p -> Boolean.TRUE.equals(p.getExistsOnIphone()) && !p.isExistsOnIcloud())
                .count();

        String label = YearMonth.parse(yearMonth)
                .atDay(1)
                .format(labelFormatter);

        return new MonthSummaryResponse(yearMonth, label, monthPhotos.size(), totalSizeBytes, earliestDate, latestDate, icloudOnlyCount, iphoneOnlyCount);
    }

    private String formatYearMonth(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    /**
     * Lists photos with optional filters.
     * When both storageDeviceId and a date range (from yearMonth or year) are provided,
     * the date range takes priority and is applied alongside the device filter.
     *
     * @param accountId       filter by account (optional)
     * @param synced          filter by disk-sync status (optional)
     * @param storageDeviceId filter by storage device (optional)
     * @param yearMonth       restrict to a single month, format "YYYY-MM" (optional)
     * @param year            restrict to a full year, format "YYYY" (optional, ignored when yearMonth is set)
     * @param page            zero-based page index
     * @param size            page size
     */
    public PhotoListResponse listPhotos(
            String accountId,
            Boolean synced,
            String storageDeviceId,
            String yearMonth,
            String year,
            int page,
            int size) {

        appContextService.requireActive();
        Pageable pageable = Pageable.from(page, size);

        DateRange dateRange = resolveDateRange(yearMonth, year);

        if (dateRange != null && storageDeviceId != null) {
            boolean syncedFlag = synced != null ? synced : true;
            Page<Photo> result = photoRepository.findBySyncedToDiskAndStorageDeviceIdAndCreatedDateBetween(
                    syncedFlag, storageDeviceId, dateRange.startInclusive(), dateRange.endExclusive(), pageable);
            return toPhotoListResponse(result, page, size);
        }

        Page<Photo> result;
        if (accountId != null && synced != null) {
            List<Photo> photos = photoRepository.findByAccountIdAndSyncedToDisk(accountId, synced);
            return new PhotoListResponse(photos.stream().map(this::toResponse).toList(), photos.size(), page, size);
        } else if (storageDeviceId != null && synced != null) {
            result = photoRepository.findBySyncedToDiskAndStorageDeviceId(synced, storageDeviceId, pageable);
        } else if (accountId != null) {
            result = photoRepository.findByAccountId(accountId, pageable);
        } else if (synced != null) {
            result = photoRepository.findBySyncedToDisk(synced, pageable);
        } else {
            result = photoRepository.findAll(pageable);
        }

        return toPhotoListResponse(result, page, size);
    }

    /** Computes date range from a yearMonth ("YYYY-MM") or year ("YYYY") filter string. Returns null when neither is set. */
    private DateRange resolveDateRange(String yearMonth, String year) {
        if (yearMonth != null && !yearMonth.isBlank()) {
            YearMonth ym = YearMonth.parse(yearMonth);
            Instant startOfMonth = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant startOfNextMonth = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            return new DateRange(startOfMonth, startOfNextMonth);
        }
        if (year != null && !year.isBlank()) {
            int yearInt = Integer.parseInt(year);
            Instant startOfYear = java.time.LocalDate.of(yearInt, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant startOfNextYear = java.time.LocalDate.of(yearInt + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
            return new DateRange(startOfYear, startOfNextYear);
        }
        return null;
    }

    private record DateRange(Instant startInclusive, Instant endExclusive) {}

    private PhotoListResponse toPhotoListResponse(Page<Photo> page, int pageIndex, int pageSize) {
        return new PhotoListResponse(
                page.getContent().stream().map(this::toResponse).toList(),
                page.getTotalSize(),
                pageIndex,
                pageSize
        );
    }

    public Optional<PhotoResponse> getPhoto(String id) {
        appContextService.requireActive();
        return photoRepository.findById(id).map(this::toResponse);
    }

    public Optional<byte[]> getThumbnailBytes(String id) throws IOException {
        Optional<Photo> photo = photoRepository.findById(id);
        if (photo.isEmpty() || photo.get().getThumbnailPath() == null) {
            return Optional.empty();
        }
        Path path = Path.of(photo.get().getThumbnailPath());
        if (!Files.exists(path)) return Optional.empty();
        return Optional.of(Files.readAllBytes(path));
    }

    public record FullPhotoData(byte[] bytes, String mimeType) {}

    private static final Set<String> HEIC_EXTENSIONS = Set.of("heic", "heif");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "m4v", "mov", "avi", "mkv");

    public Optional<byte[]> getFullPhotoBytes(String id) throws IOException {
        return getFullPhotoData(id).map(FullPhotoData::bytes);
    }

    public Optional<FullPhotoData> getFullPhotoData(String id) throws IOException {
        Optional<Photo> photo = photoRepository.findById(id);
        if (photo.isEmpty() || !photo.get().isSyncedToDisk()) {
            return Optional.empty();
        }
        Path path = Path.of(photo.get().getFilePath());
        if (!Files.exists(path)) return Optional.empty();

        String ext = extension(path);

        if (HEIC_EXTENSIONS.contains(ext)) {
            byte[] jpeg = convertHeicToJpeg(path);
            return Optional.of(new FullPhotoData(jpeg, "image/jpeg"));
        }

        String mimeType = switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "tiff", "tif" -> "image/tiff";
            case "dng" -> "image/x-adobe-dng";
            case "mp4", "m4v" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "avi" -> "video/x-msvideo";
            case "mkv" -> "video/x-matroska";
            default -> "application/octet-stream";
        };

        return Optional.of(new FullPhotoData(Files.readAllBytes(path), mimeType));
    }

    private byte[] convertHeicToJpeg(Path source) throws IOException {
        Path temp = Files.createTempFile("heic-full-", ".jpg");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "vips", "copy",
                    source.toAbsolutePath() + "[0]",
                    temp.toAbsolutePath() + "[Q=90]"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("HEIC conversion interrupted", e);
            }
            if (exitCode != 0) {
                String output = new String(process.getInputStream().readAllBytes());
                throw new IOException("vips copy failed (exit " + exitCode + "): " + output);
            }
            return Files.readAllBytes(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    public PhotoResponse toResponse(Photo photo) {
        return new PhotoResponse(
                photo.getId(),
                photo.getIcloudPhotoId(),
                photo.getAccountId(),
                photo.getFilename(),
                photo.getFilePath(),
                photo.getThumbnailPath(),
                photo.getFileSize(),
                photo.getWidth(),
                photo.getHeight(),
                photo.getCreatedDate(),
                photo.getImportedDate(),
                photo.getChecksum(),
                photo.isSyncedToDisk(),
                photo.isExistsOnIcloud(),
                Boolean.TRUE.equals(photo.getExistsOnIphone()),
                photo.getMediaType()
        );
    }
}
