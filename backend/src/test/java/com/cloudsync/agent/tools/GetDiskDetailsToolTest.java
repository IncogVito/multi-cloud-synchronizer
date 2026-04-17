package com.cloudsync.agent.tools;

import com.cloudsync.client.HostAgentClient;
import com.cloudsync.client.hostmodel.DiskDetailsResult;
import com.cloudsync.exception.HostAgentException;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetDiskDetailsToolTest {

    @Mock HostAgentClient hostAgent;
    @Mock ObjectMapper objectMapper;

    GetDiskDetailsTool tool;

    @BeforeEach
    void setUp() {
        // NOTE: constructor must accept io.micronaut.serde.ObjectMapper, not Jackson's.
        // If this line fails to compile, the fix has not been applied yet.
        tool = new GetDiskDetailsTool(hostAgent, objectMapper);
    }

    @Test
    void getName_shouldReturnGetDiskDetails() {
        assertThat(tool.getName()).isEqualTo("get_disk_details");
    }

    @Test
    void execute_returnsSerializedJson_whenHostAgentSucceeds() throws IOException {
        DiskDetailsResult result = new DiskDetailsResult(
                "/dev/sdb", "50G", "ext4", "uuid-123", "MY-DRIVE",
                "/mnt/external-drive", List.of());
        when(hostAgent.getDiskDetails("/dev/sdb")).thenReturn(result);
        when(objectMapper.writeValueAsString(result)).thenReturn("{\"device\":\"/dev/sdb\"}");

        String output = tool.execute("/dev/sdb");

        assertThat(output).isEqualTo("{\"device\":\"/dev/sdb\"}");
    }

    @Test
    void execute_returnsErrorJson_whenHostAgentThrowsHostAgentException() {
        when(hostAgent.getDiskDetails("/dev/sdb"))
                .thenThrow(new HostAgentException("lsblk failed", "LSBLK_FAILED"));

        String output = tool.execute("/dev/sdb");

        assertThat(output).contains("\"error\"").contains("lsblk failed");
    }

    @Test
    void execute_returnsErrorJson_whenArgumentIsBlank() {
        String output = tool.execute("   ");

        assertThat(output).contains("\"error\"");
    }

    @Test
    void execute_returnsErrorJson_whenArgumentIsNull() {
        String output = tool.execute(null);

        assertThat(output).contains("\"error\"");
    }

    @Test
    void execute_returnsErrorJson_whenSerializationFails() throws IOException {
        DiskDetailsResult result = new DiskDetailsResult(
                "/dev/sdb", "50G", null, null, null, null, List.of());
        when(hostAgent.getDiskDetails("/dev/sdb")).thenReturn(result);
        when(objectMapper.writeValueAsString(any())).thenThrow(new IOException("serialization failed"));

        String output = tool.execute("/dev/sdb");

        assertThat(output).contains("\"error\"");
    }
}
