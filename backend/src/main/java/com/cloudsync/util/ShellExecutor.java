package com.cloudsync.util;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Utility for executing shell commands and scripts from the /scripts volume.
 */
@Singleton
public class ShellExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ShellExecutor.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    public record ShellResult(int exitCode, String stdout, String stderr) {
        public boolean isSuccess() { return exitCode == 0; }
    }

    public ShellResult execute(String... command) {
        return execute(DEFAULT_TIMEOUT_SECONDS, command);
    }

    public ShellResult execute(int timeoutSeconds, String... command) {
        return executeList(timeoutSeconds, List.of(command));
    }

    public ShellResult executeList(int timeoutSeconds, List<String> command) {
        LOG.debug("Executing command: {}", command);
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            String stdout;
            String stderr;
            try (BufferedReader outReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                stdout = outReader.lines().collect(Collectors.joining("\n"));
                stderr = errReader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOG.warn("Command timed out after {}s: {}", timeoutSeconds, command);
                return new ShellResult(-1, stdout, "Command timed out");
            }

            int exitCode = process.exitValue();
            LOG.debug("Command exited with code {}: {}", exitCode, command);
            return new ShellResult(exitCode, stdout, stderr);
        } catch (IOException | InterruptedException e) {
            LOG.error("Failed to execute command: {}", command, e);
            Thread.currentThread().interrupt();
            return new ShellResult(-1, "", e.getMessage());
        }
    }

    public ShellResult executeScript(String scriptsDir, String scriptName) {
        return execute("bash", scriptsDir + "/" + scriptName);
    }
}
