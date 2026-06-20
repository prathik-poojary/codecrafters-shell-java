import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class Main {
    @SuppressWarnings("resource")
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        String currentDirectory = System.getProperty("user.dir");
        Set<String> builtins = Set.of("echo", "exit", "type", "pwd", "cd");
        while (true) {
            System.out.print("$ ");
            String input = sc.nextLine();
            if (input.equals("exit")) {
                break;
            } else if (input.equals("pwd")) {
                System.out.println(currentDirectory);
            } else if (input.startsWith("cd ")) {
                String directory = input.substring(3);
                if (directory.equals("~")) {
                    currentDirectory = System.getenv("HOME");
                } else {
                    File dir;
                    if (directory.startsWith("/")) {
                        dir = new File(directory);
                    } else {
                        dir = new File(currentDirectory, directory);
                    }
                    if (dir.exists() && dir.isDirectory()) {
                        currentDirectory = dir.getCanonicalPath();
                    } else {
                        System.out.println("cd: " + directory + ": No such file or directory");
                    }
                }
            } else if (input.startsWith("type")) {
                String cmd = input.substring(5);
                if (builtins.contains(cmd)) {
                    System.out.println(cmd + " is a shell builtin");
                } else {
                    String pathEnv = System.getenv("PATH");
                    String directories[] = pathEnv.split(File.pathSeparator);
                    boolean found = false;
                    for (String dir : directories) {
                        File file = new File(dir, cmd);
                        if (file.exists() && file.canExecute()) {
                            System.out.println(cmd + " is " + file.getAbsolutePath());
                            found = true;
                            break;
                        }
                    }
                    if (!found)
                        System.out.println(cmd + ": not found ");
                }
            } else {
                List<String> parts = new ArrayList<>();
                StringBuilder current = new StringBuilder();
                boolean inSingleQuote = false;
                boolean inDoubleQuote = false;
                for (int i = 0; i < input.length(); i++) {
                    char c = input.charAt(i);
                    if (c == '\\' && !inSingleQuote && !inDoubleQuote) {
                        if (i + 1 < input.length()) {
                            current.append(input.charAt(i + 1));
                            i++;
                        }
                    } else if (inDoubleQuote && c == '\\') {
                        if (i + 1 < input.length()) {
                            char next = input.charAt(i + 1);
                            if (next == '"' || next == '\\') {
                                current.append(next);
                                i++;
                                continue;
                            } else {
                                current.append('\\');
                                continue;
                            }
                        }
                    } else if (c == '\'' && !inDoubleQuote) {
                        inSingleQuote = !inSingleQuote;
                    } else if (c == '"' && !inSingleQuote) {
                        inDoubleQuote = !inDoubleQuote;
                    } else if (c == ' ' && !inSingleQuote && !inDoubleQuote) {
                        if (current.length() > 0) {
                            parts.add(current.toString());
                            current.setLength(0);
                        }

                    } else {
                        current.append(c);
                    }
                }
                if (current.length() > 0) {
                    parts.add(current.toString());
                }
                String redirectFile = null;
                for (int i = 0; i < parts.size(); i++) {
                    String token = parts.get(i);
                    if (token.equals(">") || token.equals("1>")) {
                        redirectFile = parts.get(i + 1);
                        parts.remove(i + 1);
                        parts.remove(i);
                        break;
                    }
                }
                String command = parts.get(0);
                String pathEnv = System.getenv("PATH");
                String directories[] = pathEnv.split(File.pathSeparator);
                File executable = null;
                for (String dir : directories) {
                    File file = new File(dir, command);
                    if (file.exists() && file.canExecute()) {
                        executable = file;
                        break;
                    }
                }
                if (executable != null) {
                    ProcessBuilder pb = new ProcessBuilder(parts);
                    if (redirectFile == null) {
                        pb.inheritIO();
                    } else {
                        pb.redirectOutput(new File(redirectFile));
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }
                    Process p = pb.start();
                    p.waitFor();
                } else {
                    System.out.println(input + ": command not found");
                }
            }
        }
    }
}