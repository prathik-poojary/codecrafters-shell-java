import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        HashSet<String> commands = new HashSet<>(Arrays.asList("exit", "echo", "type"));
        while (true) {
            System.out.print("$ ");
            String input = sc.nextLine();
            String cmd = input.indexOf(" ") == -1 ? input : input.substring(0, input.indexOf(" "));
            String rem = input.indexOf(" ") == -1 ? "" : input.substring(input.indexOf(" ") + 1);
            if (cmd.equals("exit")) {
                break;
            } else if (cmd.equals("type")) {
                System.out.println(type(rem));
            } else if (cmd.equals("echo")) {
                System.out.println(rem);
            } else if (getExecutable(cmd) != null) {
                Process process = Runtime.getRuntime().exec(input.split(" "));
                process.getInputStream().transferTo(System.out);
            } else {
                System.out.println(cmd + ": command not found");
            }
        }
    }

    public static String type(String cmd) {
        HashSet<String> commands = new HashSet<>(Arrays.asList("exit", "echo", "type"));
        String path = System.getenv("PATH");
        String[] pathDir = path.split(":");
        if (commands.contains(cmd))
            return cmd + " is a shell builtin";
        for (String dir : pathDir) {
            File file = new File(dir, cmd);
            if (file.exists() && file.canExecute())
                return cmd + " is " + file.getAbsolutePath();
        }
        return cmd + ": not found";
    }

    public static String getExecutable(String cmd) {
        String path = System.getenv("PATH");
        String[] pathDir = path.split(":");
        for (String dir : pathDir) {
            File file = new File(dir, cmd);
            if (file.exists() && file.canExecute())
                return file.getAbsolutePath();
        }
        return null;
    }
}