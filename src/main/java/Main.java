import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        String currentDir;
        try {
            currentDir = new File(".").getCanonicalPath();
        } catch (Exception e) {
            currentDir = new File(".").getAbsolutePath();
        }

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) {
                break;
            }

            String input = scanner.nextLine();
            String[] parts = splitArgs(input);
            if (parts.length == 0) {
                continue;
            }

            String cmd = parts[0];
            if (cmd.equals("exit")) {
                break;
            }

            if (cmd.equals("pwd")) {
                System.out.println(currentDir);
                continue;
            }

            if (cmd.equals("cd")) {
                if (parts.length == 1) {
                    // bare cd, no-op for now
                    continue;
                }
                String target = parts[1];
                File dir;
                if (target.startsWith("/")) {
                    dir = new File(target);
                } else if (target.startsWith("~")) {
                    String home = System.getenv("HOME");
                    if (home == null || home.isEmpty()) {
                        home = System.getProperty("user.home");
                    }
                    String rest = target.length() > 1 ? target.substring(1) : "";
                    dir = new File(home + rest);
                } else {
                    dir = new File(currentDir, target);
                }

                try {
                    if (dir.exists() && dir.isDirectory()) {
                        try {
                            currentDir = dir.getCanonicalPath();
                        } catch (Exception ex) {
                            currentDir = dir.getAbsolutePath();
                        }
                    } else {
                        System.out.println("cd: " + target + ": No such file or directory");
                    }
                } catch (Exception e) {
                    System.out.println("cd: " + target + ": No such file or directory");
                }
                continue;
            }

            if (cmd.equals("echo")) {
                if (parts.length == 1) {
                    System.out.println();
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i < parts.length; i++) {
                        if (i > 1)
                            sb.append(' ');
                        sb.append(parts[i]);
                    }
                    System.out.println(sb.toString());
                }
            } else if (cmd.equals("type")) {
                if (parts.length == 1) {
                    System.out.println();
                    continue;
                }
                String arg = parts[1];
                if (arg.equals("echo")
                        || arg.equals("exit")
                        || arg.equals("type")
                        || arg.equals("pwd")
                        || arg.equals("cd")) {
                    System.out.println(arg + " is a shell builtin");
                } else {
                    boolean found = false;
                    String pathEnv = System.getenv("PATH");
                    if (pathEnv != null && !pathEnv.isEmpty()) {
                        String[] dirs = pathEnv.split(File.pathSeparator);
                        for (String d : dirs) {
                            if (d == null || d.isEmpty())
                                continue;
                            File f = new File(d, arg);
                            if (f.exists() && f.isFile()) {
                                if (f.canExecute()) {
                                    System.out.println(arg + " is " + f.getAbsolutePath());
                                    found = true;
                                    break;
                                } else {
                                    // exists but not executable: skip
                                    continue;
                                }
                            }
                        }
                    }
                    if (!found) {
                        System.out.println(arg + ": not found");
                    }
                }
            } else {
                // Not a builtin; try to locate executable in PATH
                // reuse parsed parts/cmd
                String exePath = findInPath(cmd);
                if (exePath != null) {
                    // Build command with arguments
                    List<String> cmdWithArgs = new ArrayList<>();
                    // Pass the program name as argv[0], letting the OS search PATH for the
                    // executable
                    cmdWithArgs.add(cmd);
                    for (int i = 1; i < parts.length; i++) {
                        cmdWithArgs.add(parts[i]);
                    }

                    try {
                        ProcessBuilder pb = new ProcessBuilder(cmdWithArgs);
                        // Ensure child process runs in shell's current working directory
                        pb.directory(new File(currentDir));
                        pb.redirectErrorStream(true);
                        Process p = pb.start();

                        // Stream process output to our stdout
                        InputStream is = p.getInputStream();
                        byte[] buffer = new byte[1024];
                        int n;
                        OutputStream os = System.out;
                        while ((n = is.read(buffer)) != -1) {
                            os.write(buffer, 0, n);
                            os.flush();
                        }

                        p.waitFor();
                    } catch (Exception e) {
                        System.out.println(cmd + ": command not found");
                    }
                } else {
                    System.out.println(input + ": command not found");
                }
            }
        }
    }

    private static String findInPath(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty())
            return null;
        String[] dirs = pathEnv.split(File.pathSeparator);
        for (String d : dirs) {
            if (d == null || d.isEmpty())
                continue;
            File f = new File(d, name);
            if (f.exists() && f.isFile() && f.canExecute()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    private static String[] splitArgs(String input) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        int n = input.length();
        while (i < n) {
            // skip whitespace
            while (i < n && Character.isWhitespace(input.charAt(i)))
                i++;
            if (i >= n)
                break;
            StringBuilder sb = new StringBuilder();
            while (i < n) {
                char c = input.charAt(i);
                if (c == '\'') {
                    // single-quoted literal
                    i++; // skip opening quote
                    int start = i;
                    while (i < n && input.charAt(i) != '\'')
                        i++;
                    // append all chars inside quotes literally
                    sb.append(input, start, i);
                    if (i < n && input.charAt(i) == '\'')
                        i++; // skip closing quote
                    // continue: next char may be non-space and get concatenated
                    continue;
                }
                if (Character.isWhitespace(c)) {
                    break;
                }
                sb.append(c);
                i++;
            }
            parts.add(sb.toString());
        }
        return parts.toArray(new String[0]);
    }
}
