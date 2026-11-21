import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.List;

/** Level A: keeps the old jar open (locked) like a running game, tries a plain move over it, then lets the public Relaunch helper do the swap. */
public class Hold {
    public static void main(String[] a) throws Exception {
        Path jar = Path.of(a[0]), fresh = Path.of(a[1]);
        try (URLClassLoader cl = new URLClassLoader(new URL[]{jar.toUri().toURL()})) {
            cl.getResource("fabric.mod.json").openStream().close();
            // like the game: the jar stays open
            var zip = new java.util.zip.ZipFile(jar.toFile());
            System.out.println("HOLD jar open: " + jar);
            try {
                Files.move(fresh, jar, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("HOLD plain move over the open jar: WORKED (no lock)");
            } catch (Exception e) {
                System.out.println("HOLD plain move over the open jar: FAILED as expected on Windows: " + e);
            }
            String javaBin = ProcessHandle.current().info().command().get();
            ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", fresh.toString(), "dev.vantage.core.Relaunch",
                    String.valueOf(ProcessHandle.current().pid()), jar.toString(), fresh.toString(), a[2]);
            pb.inheritIO();
            pb.start().getOutputStream().close();
            System.out.println("HOLD helper started, exiting now (zip still open: " + zip.size() + " entries)");
            System.out.flush();
            Runtime.getRuntime().halt(0);
        }
    }
}
