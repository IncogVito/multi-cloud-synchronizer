package com.cloudsync.model.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the DTOs used to deserialize icloud-service HTTP responses
 * are correctly annotated.  The icloud-service (Python/FastAPI) sends
 * snake_case fields and a nested "dimensions" object; these tests guard
 * against regressions in the field-mapping annotations.
 *
 * Tests use a plain Jackson ObjectMapper — no Micronaut context needed —
 * because @JsonProperty / @JsonIgnoreProperties are standard Jackson
 * annotations that work independently of @Serdeable.
 */
class ICloudPhotoDtoDeserializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        // findAndRegisterModules picks up JavaTimeModule (Instant support) and any
        // other Jackson modules present on the classpath without explicit imports.
        mapper = new ObjectMapper().findAndRegisterModules();
    }

    // ---------------------------------------------------------------
    // ICloudPhotoAsset
    // ---------------------------------------------------------------

    @Test
    void photoAsset_deserializesSnakeCaseAssetToken() throws Exception {
        String json = """
                {
                  "id": "abc123",
                  "filename": "IMG_0001.jpg",
                  "size": 4096000,
                  "created_date": 1686830400000,
                  "dimensions": {"width": 4032, "height": 3024},
                  "asset_token": "tok-xyz"
                }
                """;

        ICloudPhotoAsset asset = mapper.readValue(json, ICloudPhotoAsset.class);

        assertEquals("tok-xyz", asset.assetToken(),
                "asset_token (snake_case) should map to assetToken");
    }

    @Test
    void photoAsset_deserializesSnakeCaseCreatedDate() throws Exception {
        String json = """
                {
                  "id": "abc123",
                  "filename": "IMG_0001.jpg",
                  "size": 4096000,
                  "created_date": 1686830400000,
                  "dimensions": {"width": 4032, "height": 3024},
                  "asset_token": "tok-xyz"
                }
                """;

        ICloudPhotoAsset asset = mapper.readValue(json, ICloudPhotoAsset.class);

        assertNotNull(asset.createdDate(),
                "created_date (snake_case) should map to createdDate");
    }

    @Test
    void photoAsset_ignoresDimensionsNestedObject() {
        // icloud-service sends {"dimensions": {"width": N, "height": N}} but
        // ICloudPhotoAsset has flat width/height fields (not used by SyncService).
        // @JsonIgnoreProperties(ignoreUnknown = true) must prevent a hard failure.
        String json = """
                {
                  "id": "abc123",
                  "filename": "IMG_0001.jpg",
                  "size": 4096000,
                  "created_date": 1686830400000,
                  "dimensions": {"width": 4032, "height": 3024},
                  "asset_token": "tok-xyz"
                }
                """;

        assertDoesNotThrow(
                () -> mapper.readValue(json, ICloudPhotoAsset.class),
                "Unknown 'dimensions' field must not throw UnrecognizedPropertyException"
        );
    }

    @Test
    void photoAsset_withoutIgnoreUnknownWouldFail() throws Exception {
        // Demonstrates the problem that @JsonIgnoreProperties(ignoreUnknown=true) solves:
        // a strict mapper rejects the 'dimensions' field.
        ObjectMapper strict = new ObjectMapper()
                .findAndRegisterModules()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

        String json = """
                {
                  "id": "abc123",
                  "filename": "IMG_0001.jpg",
                  "size": 4096000,
                  "created_date": 1686830400000,
                  "dimensions": {"width": 4032, "height": 3024},
                  "asset_token": "tok-xyz"
                }
                """;

        // The annotation on the class overrides the mapper setting, so this
        // still succeeds — proving that @JsonIgnoreProperties is class-level.
        assertDoesNotThrow(
                () -> strict.readValue(json, ICloudPhotoAsset.class),
                "@JsonIgnoreProperties(ignoreUnknown=true) on the class beats the mapper setting"
        );
    }

    @Test
    void photoAsset_deserializesCoreFieldsCorrectly() throws Exception {
        String json = """
                {
                  "id": "photo-id-42",
                  "filename": "DSCN0042.jpg",
                  "size": 8388608,
                  "created_date": 1640995200000,
                  "dimensions": {"width": 3840, "height": 2160},
                  "asset_token": "token-42"
                }
                """;

        ICloudPhotoAsset asset = mapper.readValue(json, ICloudPhotoAsset.class);

        assertAll(
                () -> assertEquals("photo-id-42", asset.id()),
                () -> assertEquals("DSCN0042.jpg", asset.filename()),
                () -> assertEquals(8388608L, asset.size()),
                () -> assertEquals("token-42", asset.assetToken())
        );
    }

    @Test
    void photoAsset_hasSerdeableAnnotation() {
        assertTrue(
                ICloudPhotoAsset.class.isAnnotationPresent(Serdeable.class),
                "ICloudPhotoAsset must be @Serdeable for Micronaut HTTP client deserialization"
        );
    }

    // ---------------------------------------------------------------
    // ICloudPhotoListResponse
    // ---------------------------------------------------------------

    @Test
    void photoListResponse_deserializesWithoutTotalField() throws Exception {
        // icloud-service /photos endpoint does not include "total" in the response body.
        String json = """
                {
                  "photos": []
                }
                """;

        ICloudPhotoListResponse response = mapper.readValue(json, ICloudPhotoListResponse.class);

        assertNotNull(response);
        assertNull(response.total(),
                "Missing 'total' in JSON should deserialize to null (field is @Nullable Integer)");
    }

    @Test
    void photoListResponse_deserializesPhotosList() throws Exception {
        String json = """
                {
                  "photos": [
                    {
                      "id": "p1",
                      "filename": "IMG_0001.jpg",
                      "size": 1024,
                      "created_date": 1672531200000,
                      "dimensions": {"width": 1920, "height": 1080},
                      "asset_token": "t1"
                    },
                    {
                      "id": "p2",
                      "filename": "IMG_0002.jpg",
                      "size": 2048,
                      "created_date": 1672617600000,
                      "dimensions": {"width": 1280, "height": 720},
                      "asset_token": "t2"
                    }
                  ]
                }
                """;

        ICloudPhotoListResponse response = mapper.readValue(json, ICloudPhotoListResponse.class);

        assertNotNull(response.photos());
        assertEquals(2, response.photos().size());
        assertEquals("p1", response.photos().get(0).id());
        assertEquals("t2", response.photos().get(1).assetToken());
    }

    @Test
    void photoListResponse_hasSerdeableAnnotation() {
        assertTrue(
                ICloudPhotoListResponse.class.isAnnotationPresent(Serdeable.class),
                "ICloudPhotoListResponse must be @Serdeable for Micronaut HTTP client deserialization"
        );
    }

    @Test
    void photoListResponse_deserializesWithExplicitTotal() throws Exception {
        String json = """
                {
                  "photos": [],
                  "total": 42
                }
                """;

        ICloudPhotoListResponse response = mapper.readValue(json, ICloudPhotoListResponse.class);

        assertEquals(42, response.total());
    }
}
