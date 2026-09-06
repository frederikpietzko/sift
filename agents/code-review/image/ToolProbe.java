import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ShellTools;

/** Image-only validation, never added to the published application. No model calls. */
public final class ToolProbe {
    public static void main(String[] args) throws Exception {
        String processStatus = Files.readString(Path.of("/proc/self/status"));
        contains(processStatus, "Uid:\t10001\t10001\t10001\t10001");
        contains(processStatus, "CapEff:\t0000000000000000");
        contains(processStatus, "NoNewPrivs:\t1");
        contains(processStatus, "Seccomp:\t2");
        if (Files.exists(Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token"))) {
            throw new IllegalStateException("Unexpected service account token");
        }
        try {
            Files.writeString(Path.of("/app/should-not-be-writable"), "probe");
            throw new IllegalStateException("Root filesystem is writable");
        } catch (java.nio.file.FileSystemException expected) {
            // The review container must have writable scratch only.
        }
        Path directory = Files.createTempDirectory("sift-image-tools-");
        Path fixture = directory.resolve("fixture.txt");
        Files.writeString(fixture, "SIFT_TOOL_SENTINEL\n");
        contains(FileSystemTools.builder().allowedDirectory(directory).build()
                .read(fixture.toString(), null, null), "SIFT_TOOL_SENTINEL");
        contains(GlobTool.builder().workingDirectory(directory).build().glob("*.txt", null), "fixture.txt");
        contains(GrepTool.builder().workingDirectory(directory).build().grep(
                "SIFT_TOOL_SENTINEL", directory.toString(), null, null, null, null, null,
                null, null, null, null, null, null), "fixture.txt");
        // The advisor may explicitly permit pwd; this tests the real shell callback's runtime needs.
        contains(ShellTools.builder().build().bash("pwd", 10000L, "runtime probe", false), "/scratch");
        Path checkout = directory.resolve("checkout");
        git(directory, "clone", "--depth=1", "https://github.com/frederikpietzko/ebfs-jpa.git", checkout.toString());
        git(checkout, "fetch", "origin", "HEAD");
        git(checkout, "checkout", "--detach", "HEAD");
        git(checkout, "diff", "HEAD...HEAD");
        System.out.println("PACKAGED_TOOLS_OK");
    }

    private static void contains(String actual, String expected) {
        if (!actual.contains(expected)) {
            throw new IllegalStateException("Tool probe expected " + expected + ", got " + actual);
        }
    }

    private static void git(Path directory, String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(java.util.List.of(arguments));
        Path output = Files.createTempFile("sift-git-probe-", ".log");
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Native git probe timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Native git probe failed: " + Files.readString(output));
        }
    }
}