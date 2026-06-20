import java.io.File;
import java.util.Scanner;

public class Main {
    static String findInPath(String cmd) {
        String path = System.getenv("PATH");
        if (path == null)
            return null;
        for (String dir : path.split(File.pathSeparator)) {
            File f = new File(dir, cmd);
            if (f.isFile() && f.canExecute())
                return f.getAbsolutePath();
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        File cwd = new File(System.getProperty("user.dir"));
        while (true) {
            System.out.print("$ ");
            String input = scanner.nextLine().trim();
            if (input.equals("pwd")) {
                System.out.println(cwd.getAbsolutePath());
            } else if (input.startsWith("cd ")) {
                String dir = input.substring(3).trim();
                if (dir.equals("~"))
                    dir = System.getenv("HOME");
                File target = new File(dir).isAbsolute() ? new File(dir) : new File(cwd, dir);
                if (target.isDirectory())
                    cwd = target.getCanonicalFile();
                else
                    System.out.println("cd: " + dir + ": No such file or directory");
            } else if (input.startsWith("type ")) {
                String cmd = input.substring(5).trim();
                if (cmd.equals("echo")
                        || cmd.equals("exit")
                        || cmd.equals("type")
                        || cmd.equals("pwd")
                        || cmd.equals("cd")) {
                    System.out.println(cmd + " is a shell builtin");
                } else {
                    String path = findInPath(cmd);
                    if (path != null)
                        System.out.println(cmd + " is " + path);
                    else
                        System.out.println(cmd + ": not found");
                }
            } else if (input.startsWith("echo ")) {
                System.out.println(input.substring(5));
            } else if (input.equals("exit") || input.startsWith("exit ")) {
                int code = input.equals("exit") ? 0 : Integer.parseInt(input.substring(5).trim());
                System.exit(code);
            } else {
                String[] parts = input.split("\\s+");
                String execPath = findInPath(parts[0]);
                if (execPath != null) {
                    Process p = new ProcessBuilder(parts).inheritIO().start();
                    p.waitFor();
                } else {
                    System.out.println(parts[0] + ": command not found");
                }
            }
        }
    }
}