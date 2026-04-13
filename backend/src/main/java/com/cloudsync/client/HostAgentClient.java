package com.cloudsync.client;

import com.cloudsync.client.hostmodel.*;
import com.cloudsync.exception.HostAgentException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for the CloudSync Host Agent Unix Domain Socket daemon.
 *
 * <p>Each method serialises a JSON request, sends it over the socket, reads
 * one newline-delimited JSON response, and deserialises the {@code data} field
 * into the expected record type.</p>
 */
@Singleton
public class HostAgentClient {

    private static final Logger LOG = LoggerFactory.getLogger(HostAgentClient.class);

    private final String socketPath;
    private final ObjectMapper json;

    public HostAgentClient(
            @Value("${app.host-agent-socket:/run/cloudsync-host-agent/agent.sock}") String socketPath) {
        this.socketPath = socketPath;
        this.json = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    // -----------------------------------------------------------------------
    // Drive
    // -----------------------------------------------------------------------

    public DriveStatus checkDrive(@Nullable String mountPath) {
        Map<String, Object> params = new HashMap<>();
        if (mountPath != null) params.put("mount_path", mountPath);
        return call("check_drive", params, DriveStatus.class);
    }

    public List<DiskInfo> listDisks() {
        return callList("list_disks", Map.of(), new TypeReference<List<DiskInfo>>() {});
    }

    public MountDriveResult mountDrive(String device, @Nullable String mountPoint) {
        Map<String, Object> params = new HashMap<>();
        params.put("device", device);
        if (mountPoint != null) params.put("mount_point", mountPoint);
        return call("mount_drive", params, MountDriveResult.class);
    }

    public UnmountDriveResult unmountDrive(@Nullable String mountPoint) {
        Map<String, Object> params = new HashMap<>();
        if (mountPoint != null) params.put("mount_point", mountPoint);
        return call("unmount_drive", params, UnmountDriveResult.class);
    }

    public DeviceIdResult readDeviceId(String device) {
        return call("read_device_id", Map.of("device", device), DeviceIdResult.class);
    }

    // -----------------------------------------------------------------------
    // iPhone
    // -----------------------------------------------------------------------

    public IPhoneDetectResult detectIphone() {
        return call("detect_iphone", Map.of(), IPhoneDetectResult.class);
    }

    public IPhoneListDevicesResult iphoneListDevices() {
        return call("iphone_list_devices", Map.of(), IPhoneListDevicesResult.class);
    }

    public IPhoneTrustResult iphoneCheckTrust(@Nullable String udid) {
        Map<String, Object> params = new HashMap<>();
        if (udid != null) params.put("udid", udid);
        return call("iphone_check_trust", params, IPhoneTrustResult.class);
    }

    public IPhoneInfoResult iphoneGetInfo(@Nullable String udid) {
        Map<String, Object> params = new HashMap<>();
        if (udid != null) params.put("udid", udid);
        return call("iphone_get_info", params, IPhoneInfoResult.class);
    }

    public IPhoneMountResult iphoneMount(@Nullable String mountPath) {
        Map<String, Object> params = new HashMap<>();
        if (mountPath != null) params.put("mount_path", mountPath);
        return call("iphone_mount", params, IPhoneMountResult.class);
    }

    public IPhoneUnmountResult iphoneUnmount(@Nullable String mountPath) {
        Map<String, Object> params = new HashMap<>();
        if (mountPath != null) params.put("mount_path", mountPath);
        return call("iphone_unmount", params, IPhoneUnmountResult.class);
    }

    // -----------------------------------------------------------------------
    // Low-level transport
    // -----------------------------------------------------------------------

    private <T> T call(String action, Map<String, Object> params, Class<T> responseType) {
        LOG.debug("[{}] Calling agent. params={}", action, params);
        String dataJson = callRaw(action, params);
        LOG.debug("[{}] Agent response: {}",action, dataJson);
        try {
            return json.readValue(dataJson, responseType);
        } catch (IOException e) {
            throw new HostAgentException("Failed to deserialise data field: " + e.getMessage(), "DESERIALISE_ERROR");
        }
    }

    private <T> T callList(String action, Map<String, Object> params, TypeReference<T> typeRef) {
        String dataJson = callRaw(action, params);
        try {
            return json.readValue(dataJson, typeRef);
        } catch (IOException e) {
            throw new HostAgentException("Failed to deserialise data field: " + e.getMessage(), "DESERIALISE_ERROR");
        }
    }

    /**
     * Sends the request, reads the response envelope, validates {@code ok}, and
     * returns the raw JSON string of the {@code data} field.
     */
    private String callRaw(String action, Map<String, Object> params) {
        Map<String, Object> request = Map.of("action", action, "params", params);

        String requestJson;
        try {
            requestJson = json.writeValueAsString(request);
        } catch (IOException e) {
            throw new HostAgentException("Failed to serialise request: " + e.getMessage(), "SERIALISE_ERROR");
        }

        LOG.debug("→ host-agent  action={} params={}", action, params);

        String responseJson = sendReceive(requestJson);

        LOG.debug("← host-agent  {}", responseJson);

        Map<String, Object> envelope;
        try {
            //noinspection unchecked
            envelope = json.readValue(responseJson, Map.class);
        } catch (IOException e) {
            throw new HostAgentException("Failed to parse agent response: " + e.getMessage(), "PARSE_ERROR");
        }

        boolean ok = Boolean.TRUE.equals(envelope.get("ok"));
        if (!ok) {
            Object errObj = envelope.get("error");
            Object codeObj = envelope.get("code");
            String error = errObj != null ? String.valueOf(errObj) : "unknown error";
            String code = codeObj != null ? String.valueOf(codeObj) : "UNKNOWN";
            throw new HostAgentException(error, code);
        }

        Object data = envelope.get("data");
        try {
            return json.writeValueAsString(data);
        } catch (IOException e) {
            throw new HostAgentException("Failed to re-serialise data field: " + e.getMessage(), "SERIALISE_ERROR");
        }
    }

    private String sendReceive(String requestJson) {
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(Path.of(socketPath));
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(address);

            // Write request + newline
            byte[] payload = (requestJson + "\n").getBytes(StandardCharsets.UTF_8);
            channel.write(java.nio.ByteBuffer.wrap(payload));

            // Read response line
            InputStream in = Channels.newInputStream(channel);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) {
                throw new HostAgentException("Agent closed connection without response", "EMPTY_RESPONSE");
            }
            return line;
        } catch (HostAgentException e) {
            throw e;
        } catch (IOException e) {
            throw new HostAgentException(
                    "Cannot connect to host agent at " + socketPath + ": " + e.getMessage(),
                    "CONNECTION_FAILED");
        }
    }
}
