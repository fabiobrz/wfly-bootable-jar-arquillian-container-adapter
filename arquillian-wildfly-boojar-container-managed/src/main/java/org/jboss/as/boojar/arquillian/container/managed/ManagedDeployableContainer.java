package org.jboss.as.boojar.arquillian.container.managed;

import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.as.arquillian.container.CommonDeployableContainer;
import org.jboss.as.boojar.arquillian.container.common.ParameterUtils;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.wildfly.core.launcher.ProcessHelper;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;


/**
 * The managed deployable container.
 */
public final class ManagedDeployableContainer extends CommonDeployableContainer<ManagedContainerConfiguration> {

    static final String TEMP_CONTAINER_DIRECTORY = "arquillian-temp-container";

    static final String CONFIG_DIR = "configuration";
    static final String DATA_DIR = "data";

    private static final int PORT_RANGE_MIN = 1;
    private static final int PORT_RANGE_MAX = 65535;

    private final Logger log = Logger.getLogger(ManagedDeployableContainer.class.getName());
    private Thread shutdownThread;
    private Process process;
    private boolean timeoutSupported = false;

    @Override
    public Class<ManagedContainerConfiguration> getConfigurationClass() {
        return ManagedContainerConfiguration.class;
    }

    @Override
    protected void startInternal() throws LifecycleException {
        ManagedContainerConfiguration config = getContainerConfiguration();

        if (isServerRunning()) {
            failDueToRunning();
        }

        try {

            final String bootableJar = config.getBootableJar();
            final String installDir = config.getInstallDir();
            final String bindAddress = config.getBindAddress();
            final String managementBindAddress = config.getManagementBindAddress();

            List<String> cmd = new ArrayList<>();

            String javaHome = config.getJavaHome();

            cmd.add(javaHome == null ? "java": String.format("%s/bin/java", javaHome));
            cmd.add("-jar");
            cmd.add(bootableJar);

            final Path installDirPath = Paths.get(installDir);
            if (installDir != null && !installDir.isEmpty()) {
                if (!Files.exists(installDirPath)) {
                    Files.createDirectories(installDirPath);
                }
                cmd.add(String .format("--install-dir=%s", installDir));
            }

            if (bindAddress != null && !bindAddress.isEmpty()) {
                cmd.add(String .format("-b=%s", bindAddress));
            }

            if (managementBindAddress != null && !managementBindAddress.isEmpty()) {
                cmd.add(String .format("-bmanagement=%s", managementBindAddress));
            }

            if (installDir != null && !installDir.isEmpty()) {
                cmd.add(String .format("--install-dir=%s", installDir));
            }

            final String javaOpts = config.getJavaVmArguments();
//            final String jbossArguments = config.getJbossArguments();

            if (javaOpts != null && !javaOpts.trim().isEmpty()) {
                cmd.addAll(ParameterUtils.splitParams(javaOpts));
            }

//            if (config.isEnableAssertions()) {
//                commandBuilder.addJavaOption("-ea");
//            }
//
//            if (config.isAdminOnly())
//                commandBuilder.setAdminOnly();

//            if (jbossArguments != null && !jbossArguments.trim().isEmpty()) {
//                cmd.addAll(ParameterUtils.splitParams(jbossArguments));
//            }


            process = new ProcessBuilder()
                    .redirectErrorStream(true)
                    .command(cmd)
                    .start();

            // Wait on ports before launching; AS7-4070
            this.waitOnPorts();

            log.info("Starting container with: " + bootableJar);

            new Thread(new ConsoleConsumer()).start();
            shutdownThread = ProcessHelper.addShutdownHook(process);

            long startupTimeout = getContainerConfiguration().getStartupTimeoutInSeconds();
            long timeout = startupTimeout * 1000;
            boolean serverAvailable = false;
            long sleep = 1000;
            while (timeout > 0 && serverAvailable == false) {
                long before = System.currentTimeMillis();
                serverAvailable = getManagementClient().isServerInRunningState();
                timeout -= (System.currentTimeMillis() - before);
                if (!serverAvailable) {
                    if (ProcessHelper.processHasDied(process)) {
                        final String msg = String.format("The java process starting the managed server exited unexpectedly with code [%d]", process.exitValue());
                        throw new LifecycleException(msg);
                    }
                    Thread.sleep(sleep);
                    timeout -= sleep;
                    sleep = Math.max(sleep / 2, 100);
                }
            }
            if (!serverAvailable) {
                ProcessHelper.destroyProcess(process);
                throw new TimeoutException(String.format("Managed server was not started within [%d] s", getContainerConfiguration().getStartupTimeoutInSeconds()));
            }
            timeoutSupported = isOperationAttributeSupported("shutdown", "timeout");


        } catch (LifecycleException e) {
            throw e;
        } catch (Exception e) {
            throw new LifecycleException("Could not start container", e);
        }
    }

    /**
     * If specified in the configuration, waits on the specified ports to become
     * available for the specified time, else throws a {@link PortAcquisitionTimeoutException}
     *
     * @throws PortAcquisitionTimeoutException
     */
    private void waitOnPorts() throws PortAcquisitionTimeoutException {
        // Get the config
        final Integer[] ports = this.getContainerConfiguration().getWaitForPorts();
        final int timeoutInSeconds = this.getContainerConfiguration().getWaitForPortsTimeoutInSeconds();

        // For all ports we'll wait on
        if (ports != null && ports.length > 0) {
            for (int i = 0; i < ports.length; i++) {
                final int port = ports[i];
                final long start = System.currentTimeMillis();
                // If not available
                while (!this.isPortAvailable(port)) {

                    // Get time elapsed
                    final int elapsedSeconds = (int) ((System.currentTimeMillis() - start) / 1000);

                    // See that we haven't timed out
                    if (elapsedSeconds > timeoutInSeconds) {
                        throw new PortAcquisitionTimeoutException(port, timeoutInSeconds);
                    }
                    try {
                        // Wait a bit, then try again.
                        Thread.sleep(500);
                    } catch (final InterruptedException e) {
                        Thread.interrupted();
                    }

                    // Log that we're waiting
                    log.warning("Waiting on port " + port + " to become available for "
                            + (timeoutInSeconds - elapsedSeconds) + "s");
                }
            }
        }
    }

    private boolean isPortAvailable(final int port) {
        // Precondition checks
        if (port < PORT_RANGE_MIN || port > PORT_RANGE_MAX) {
            throw new IllegalArgumentException("Port specified is out of range: " + port);
        }

        ServerSocket ss = null;
        DatagramSocket ds = null;
        try {
            // Attempt both TCP and UDP
            ss = new ServerSocket(port);
            ds = new DatagramSocket(port);
            // So we don't block from using this port while it's in a TIMEOUT state after we release it
            ss.setReuseAddress(true);
            ds.setReuseAddress(true);
            // Could be acquired
            return true;
        } catch (final IOException e) {
            // Swallow
        } finally {
            if (ds != null) {
                ds.close();
            }
            if (ss != null) {
                try {
                    ss.close();
                } catch (final IOException e) {
                    // Swallow

                }
            }
        }

        // Couldn't be acquired
        return false;
    }


    @Override
    protected void stopInternal(final Integer timeout) throws LifecycleException {
        if (shutdownThread != null) {
            Runtime.getRuntime().removeShutdownHook(shutdownThread);
            shutdownThread = null;
        }
        try {
            if (process != null) {
                Thread shutdown = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(getContainerConfiguration().getStopTimeoutInSeconds() * 1000);
                        } catch (InterruptedException e) {
                            return;
                        }

                        // The process hasn't shutdown within 60 seconds. Terminate forcibly.
                        if (process != null) {
                            process.destroy();
                        }
                    }
                });
                shutdown.start();

                // AS7-6620: Create the shutdown operation and run it asynchronously and wait for process to terminate
                final ModelNode op = Operations.createOperation("shutdown");
                if (timeoutSupported) {
                    if (timeout != null) {
                        op.get("timeout").set(timeout);
                    }
                } else {
                    log.severe(String.format("Timeout is not supported for %s on the shutdown operation.", getContainerDescription()));
                }
                getManagementClient().getControllerClient().executeAsync(op, null);

                process.waitFor();
                process = null;

                shutdown.interrupt();
            }
        } catch (Exception e) {
            try {
                ProcessHelper.destroyProcess(process);
            }catch (Exception ignore) {}
            throw new LifecycleException("Could not stop container", e);
        }
    }

    private boolean isServerRunning() {
        Socket socket = null;
        try {
            socket = new Socket(
                    getContainerConfiguration().getManagementAddress(),
                    getContainerConfiguration().getManagementPort());
        } catch (Exception ignored) { // nothing is running on defined ports
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception e) {
                    throw new RuntimeException("Could not close isServerStarted socket", e);
                }
            }
        }
        return true;
    }

    private void failDueToRunning() throws LifecycleException {
        final int managementPort = getContainerConfiguration().getManagementPort();
        throw new LifecycleException(
                String.format("The port %1$d is already in use. It means that either the server might be already running " +
                                "or there is another process using port %1$d.%n" +
                                "Managed containers do not support connecting to running server instances due to the " +
                                "possible harmful effect of connecting to the wrong server.%n" +
                                "Please stop server (or another process) before running, " +
                                "change to another type of container (e.g. remote) or use jboss.socket.binding.port-offset variable " +
                                "to change the default port.%n" +
                                "To disable this check and allow Arquillian to connect to a running server, " +
                                "set allowConnectingToRunningServer to true in the container configuration",
                        managementPort));
    }

    /**
     * Runnable that consumes the output of the process. If nothing consumes the output the AS will hang on some platforms
     *
     * @author Stuart Douglas
     */
    private class ConsoleConsumer implements Runnable {

        @Override
        public void run() {
            final InputStream stream = process.getInputStream();
            final boolean writeOutput = getContainerConfiguration().isOutputToConsole();

            try {
                byte[] buf = new byte[32];
                int num;
                // Do not try reading a line cos it considers '\r' end of line
                while ((num = stream.read(buf)) != -1) {
                    if (writeOutput)
                        System.out.write(buf, 0, num);
                }
            } catch (IOException e) {
            }
        }

    }
}