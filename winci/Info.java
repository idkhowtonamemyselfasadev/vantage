public class Info {
    public static void main(String[] a) {
        var i = ProcessHandle.current().info();
        System.out.println("INFO os.name=" + System.getProperty("os.name"));
        System.out.println("INFO command() = " + i.command());
        System.out.println("INFO arguments() present = " + i.arguments().isPresent());
        System.out.println("INFO commandLine() present = " + i.commandLine().isPresent());
    }
}
