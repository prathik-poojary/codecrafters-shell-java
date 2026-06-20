import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.function.Consumer;

public class Main {

    private static final Map<String, Consumer<List<String>>> BUILTINS = new HashMap<>();

    static {
        BUILTINS.put("exit", args -> System.exit(0));
        BUILTINS.put("echo", args -> System.out.println(String.join(" ", args)));
        BUILTINS.put("type", args -> handleType(args.get(0), BUILTINS));
        BUILTINS.put("pwd", args -> System.out.println(System.getProperty("user.dir")));
    }

    private static String findInPath(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(":")) {
                Path candidate = Paths.get(dir, command);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath().toString();
                }
            }
        }
        return null;
    }

    private static void handleType(String command, Map<String, Consumer<List<String>>> builtIns) {
        if (builtIns.containsKey(command)) {
            System.out.println(command + " is a shell builtin");
        } else {
            String result = findInPath(command);
            if (result != null) {
                System.out.println(command + " is " + result);
            } else {
                System.out.println(command + ": not found");
            }
        }
    }

    public static void main(String[] args) throws Exception {

        System.out.print("$ ");

        try (Scanner scanner = new Scanner(System.in)) {

            while (true) {
                String input = scanner.nextLine();

                String[] parts = input.split("\\s+");
                String command = parts[0];

                if (BUILTINS.containsKey(command)) {
                    List<String> cmdArgs = Arrays.asList(Arrays.copyOfRange(parts, 1, parts.length));
                    BUILTINS.get(command).accept(cmdArgs);
                } else {
                    String executable = findInPath(parts[0]);
                    if (executable != null) {
                        ProcessBuilder pb = new ProcessBuilder(parts);
                        pb.inheritIO();
                        Process p = pb.start();
                        p.waitFor();
                    } else {
                        System.out.println(input + ": command not found");
                    }
                }

                System.out.print("$ ");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}