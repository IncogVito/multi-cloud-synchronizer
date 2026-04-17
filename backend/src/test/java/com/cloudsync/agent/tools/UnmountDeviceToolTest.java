package com.cloudsync.agent.tools;

import com.cloudsync.client.HostAgentClient;
import com.cloudsync.client.hostmodel.UnmountDriveResult;
import com.cloudsync.exception.HostAgentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnmountDeviceToolTest {

    @Mock HostAgentClient hostAgent;

    UnmountDeviceTool tool;

    @BeforeEach
    void setUp() {
        tool = new UnmountDeviceTool(hostAgent);
    }

    @Test
    void getName_shouldReturnUnmountDevice() {
        assertThat(tool.getName()).isEqualTo("unmount_device");
    }

    @Test
    void execute_returnsSuccessJson_whenHostAgentSucceeds() {
        when(hostAgent.unmountDrive("/mnt/external-drive"))
                .thenReturn(new UnmountDriveResult(true, "Unmounted /mnt/external-drive"));

        String output = tool.execute("/mnt/external-drive");

        assertThat(output).contains("\"success\":true");
        assertThat(output).contains("Unmounted");
    }

    @Test
    void execute_returnsFailureJson_whenHostAgentThrowsHostAgentException() {
        when(hostAgent.unmountDrive("/mnt/external-drive"))
                .thenThrow(new HostAgentException("not mounted", "NOT_MOUNTED"));

        String output = tool.execute("/mnt/external-drive");

        assertThat(output).contains("\"success\":false");
        assertThat(output).contains("not mounted");
    }

    @Test
    void execute_returnsErrorJson_whenArgumentIsBlank() {
        String output = tool.execute("  ");

        assertThat(output).contains("error");
    }

    @Test
    void execute_returnsErrorJson_whenArgumentIsNull() {
        String output = tool.execute(null);

        assertThat(output).contains("error");
    }

    @Test
    void execute_trimsWhitespaceFromArgument() {
        when(hostAgent.unmountDrive("/mnt/external-drive"))
                .thenReturn(new UnmountDriveResult(true, "ok"));

        String output = tool.execute("  /mnt/external-drive  ");

        assertThat(output).contains("\"success\":true");
    }
}
