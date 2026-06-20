import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {
    static class BackgroundJob {
        int id;
        Process process;
        String fullCommand;

        BackgroundJob(int id, Process process, String fullCommand) {
            this.id = id;
            this.process = process;
            this.fullCommand = fullCommand;
        }
    }

    private static final List<BackgroundJob> activeJobs = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        // NEW: If the shell is executed with "-c", run the command directly and exit.
        // This lets our pipeline engine route built-ins seamlessly through child shell
        // forks!
        if (args.length >= 2 && args[0].equals("-c")) {
            executeSingleCommandLine(args[1]);
            System.exit(0);
        }

        Scanner scanner = new Scanner(System.in);

        while (true) {
            List<BackgroundJob> toRemove = new ArrayList<>();
            for (int i = 0; i < activeJobs.size(); i++) {
                BackgroundJob job = activeJobs.get(i);
                if (!job.process.isAlive()) {
                    String paddedStatus = String.format("%-24s", "Done");
                    char statusChar = (i == activeJobs.size() - 1)
                            ? '+'
                            : ((i == activeJobs.size() - 2) ? '-' : ' ');
                    System.out.println(
                            "["
                                    + job.id
                                    + "]"
                                    + statusChar
                                    + "  "
                                    + paddedStatus
                                    + job.fullCommand);
                    toRemove.add(job);
                }
            }
            activeJobs.removeAll(toRemove);

            System.out.print("$ ");

            if (!scanner.hasNextLine()) {
                break;
            }
            String input = scanner.nextLine().trim();
            if (input.isEmpty())
                continue;

            executeSingleCommandLine(input);
        }
    }

    // Helper to process a line of execution input safely
    private static void executeSingleCommandLine(String input) throws Exception {
        List<String> parts = parseArguments(input);
        if (parts.isEmpty())
            return;

        boolean isBackgroundJob = false;
        if (parts.get(parts.size() - 1).equals("&")) {
            isBackgroundJob = true;
            parts.remove(parts.size() - 1);
        }

        if (parts.isEmpty())
            return;

        // --- PIPELINE DETECTION ---
        boolean hasPipe = false;
        for (String part : parts) {
            if (part.equals("|")) {
                hasPipe = true;
                break;
            }
        }

        if (hasPipe) {
            handlePipeline(parts);
            return;
        }

        // --- REGULAR SINGLE-COMMAND RUNNER ---
        String command = parts.get(0);

        String stdoutFile = null;
        String stderrFile = null;
        boolean appendStdout = false;
        boolean appendStderr = false;

        List<String> commandArgs = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            String token = parts.get(i);
            if (token.equals(">") || token.equals("1>")) {
                if (i + 1 < parts.size())
                    stdoutFile = parts.get(++i);
            } else if (token.equals(">>") || token.equals("1>>")) {
                if (i + 1 < parts.size()) {
                    stdoutFile = parts.get(++i);
                    appendStdout = true;
                }
            } else if (token.equals("2>")) {
                if (i + 1 < parts.size())
                    stderrFile = parts.get(++i);
            } else if (token.equals("2>>")) {
                if (i + 1 < parts.size()) {
                    stderrFile = parts.get(++i);
                    appendStderr = true;
                }
            } else {
                commandArgs.add(token);
            }
        }

        if (commandArgs.isEmpty())
            return;
        command = commandArgs.get(0);

        java.io.PrintStream originalOut = System.out;
        java.io.PrintStream originalErr = System.err;
        java.io.PrintStream customOutStream = null;
        java.io.PrintStream customErrStream = null;

        try {
            if (stdoutFile != null) {
                File f = new File(stdoutFile);
                if (f.getParentFile() != null)
                    f.getParentFile().mkdirs();
                customOutStream = new java.io.PrintStream(new java.io.FileOutputStream(f, appendStdout));
                System.setOut(customOutStream);
            }
            if (stderrFile != null) {
                File f = new File(stderrFile);
                if (f.getParentFile() != null)
                    f.getParentFile().mkdirs();
                customErrStream = new java.io.PrintStream(new java.io.FileOutputStream(f, appendStderr));
                System.setErr(customErrStream);
            }

            if (command.equals("exit")) {
                System.exit(0);
            } else if (command.equals("echo")) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < commandArgs.size(); i++) {
                    sb.append(commandArgs.get(i));
                    if (i < commandArgs.size() - 1)
                        sb.append(" ");
                }
                System.out.println(sb.toString());
            } else if (command.equals("type")) {
                if (commandArgs.size() > 1) {
                    String targetCommand = commandArgs.get(1);
                    if (targetCommand.equals("echo")
                            || targetCommand.equals("exit")
                            || targetCommand.equals("type")
                            || targetCommand.equals("pwd")
                            || targetCommand.equals("cd")
                            || targetCommand.equals("jobs")) {
                        System.out.println(targetCommand + " is a shell builtin");
                    } else {
                        String pathToFile = getPathToCommand(targetCommand);
                        if (pathToFile != null) {
                            System.out.println(targetCommand + " is " + pathToFile);
                        } else {
                            System.out.println(targetCommand + ": not found");
                        }
                    }
                }
            } else if (command.equals("pwd")) {
                System.out.println(System.getProperty("user.dir"));
            } else if (command.equals("cd")) {
                if (commandArgs.size() > 1) {
                    String targetPathStr = commandArgs.get(1);
                    if (targetPathStr.equals("~")) {
                        targetPathStr = System.getenv("HOME");
                        if (targetPathStr == null)
                            targetPathStr = System.getProperty("user.home");
                    }
                    Path currentPath = Paths.get(System.getProperty("user.dir"));
                    Path newPath = currentPath.resolve(targetPathStr).normalize();
                    if (Files.isDirectory(newPath)) {
                        System.setProperty("user.dir", newPath.toString());
                    } else {
                        System.out.println("cd: " + targetPathStr + ": No such file or directory");
                    }
                }
            } else if (command.equals("jobs")) {
                List<BackgroundJob> doneInJobs = new ArrayList<>();
                for (int i = 0; i < activeJobs.size(); i++) {
                    BackgroundJob job = activeJobs.get(i);
                    String statusStr = job.process.isAlive() ? "Running" : "Done";
                    String paddedStatus = String.format("%-24s", statusStr);
                    char statusChar = (i == activeJobs.size() - 1)
                            ? '+'
                            : ((i == activeJobs.size() - 2) ? '-' : ' ');

                    if (statusStr.equals("Done")) {
                        System.out.println(
                                "["
                                        + job.id
                                        + "]"
                                        + statusChar
                                        + "  "
                                        + paddedStatus
                                        + job.fullCommand);
                        doneInJobs.add(job);
                    } else {
                        System.out.println(
                                "["
                                        + job.id
                                        + "]"
                                        + statusChar
                                        + "  "
                                        + paddedStatus
                                        + job.fullCommand
                                        + " &");
                    }
                }
                activeJobs.removeAll(doneInJobs);
            } else {
                String pathToFile = getPathToCommand(command);
                if (pathToFile != null) {
                    try {
                        ProcessBuilder pb = new ProcessBuilder(commandArgs);
                        pb.directory(new File(System.getProperty("user.dir")));

                        if (stdoutFile != null) {
                            pb.redirectOutput(
                                    appendStdout
                                            ? ProcessBuilder.Redirect.appendTo(new File(stdoutFile))
                                            : ProcessBuilder.Redirect.to(new File(stdoutFile)));
                        } else {
                            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        }

                        if (stderrFile != null) {
                            pb.redirectError(
                                    appendStderr
                                            ? ProcessBuilder.Redirect.appendTo(new File(stderrFile))
                                            : ProcessBuilder.Redirect.to(new File(stderrFile)));
                        } else {
                            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                        }

                        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                        Process process = pb.start();

                        if (isBackgroundJob) {
                            int assignedJobId = 1;
                            while (true) {
                                boolean idInUse = false;
                                for (BackgroundJob job : activeJobs) {
                                    if (job.id == assignedJobId) {
                                        idInUse = true;
                                        break;
                                    }
                                }
                                if (!idInUse)
                                    break;
                                assignedJobId++;
                            }
                            String fullCmdStr = String.join(" ", commandArgs);
                            BackgroundJob newJob = new BackgroundJob(assignedJobId, process, fullCmdStr);
                            activeJobs.add(newJob);
                            System.out.println("[" + assignedJobId + "] " + process.pid());
                        } else {
                            process.waitFor();
                        }
                    } catch (Exception e) {
                        System.out.println(command + ": command not found");
                    }
                } else {
                    System.out.println(command + ": command not found");
                }
            }
        } finally {
            if (customOutStream != null)
                customOutStream.close();
            if (customErrStream != null)
                customErrStream.close();
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static void handlePipeline(List<String> parts) {
        List<ProcessBuilder> builders = new ArrayList<>();
        List<String> currentCmdArgs = new ArrayList<>();

        for (String token : parts) {
            if (token.equals("|")) {
                if (!currentCmdArgs.isEmpty()) {
                    builders.add(createPipelineProcessBuilder(currentCmdArgs));
                    currentCmdArgs.clear();
                }
            } else {
                currentCmdArgs.add(token);
            }
        }

        if (!currentCmdArgs.isEmpty()) {
            builders.add(createPipelineProcessBuilder(currentCmdArgs));
        }

        if (builders.isEmpty())
            return;

        try {
            builders.get(0).redirectInput(ProcessBuilder.Redirect.INHERIT);
            builders.get(builders.size() - 1).redirectOutput(ProcessBuilder.Redirect.INHERIT);
            builders.get(builders.size() - 1).redirectError(ProcessBuilder.Redirect.INHERIT);

            List<Process> pipelineProcesses = ProcessBuilder.startPipeline(builders);
            pipelineProcesses.get(pipelineProcesses.size() - 1).waitFor();
        } catch (Exception e) {
            System.out.println("Pipeline execution error");
        }
    }

    // NEW: Smart factory method that seamlessly self-forks built-ins or calls
    // system commands!
    private static ProcessBuilder createPipelineProcessBuilder(List<String> cmdArgs) {
        String baseCmd = cmdArgs.get(0);
        List<String> finalExecutionList = new ArrayList<>();

        // If the current stage is a shell built-in, wrap it in a child java invocation
        // bundle!
        if (baseCmd.equals("echo")
                || baseCmd.equals("type")
                || baseCmd.equals("pwd")
                || baseCmd.equals("cd")
                || baseCmd.equals("jobs")) {
            finalExecutionList.add("./your_program.sh"); // Call our own launcher context
            finalExecutionList.add("-c");
            finalExecutionList.add(String.join(" ", cmdArgs));
        } else {
            // Standard binary execution path lookup
            String realPath = getPathToCommand(baseCmd);
            if (realPath != null) {
                finalExecutionList.add(realPath);
            } else {
                finalExecutionList.add(baseCmd);
            }
            for (int i = 1; i < cmdArgs.size(); i++) {
                finalExecutionList.add(cmdArgs.get(i));
            }
        }

        ProcessBuilder pb = new ProcessBuilder(finalExecutionList);
        pb.directory(new File(System.getProperty("user.dir")));
        return pb;
    }

    private static List<String> parseArguments(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean escaped = false;
        boolean buildingToken = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaped) {
                currentToken.append(c);
                escaped = false;
                buildingToken = true;
            } else if (inSingleQuotes) {
                if (c == '\'')
                    inSingleQuotes = false;
                else
                    currentToken.append(c);
                buildingToken = true;
            } else if (inDoubleQuotes) {
                if (c == '"')
                    inDoubleQuotes = false;
                else if (c == '\\') {
                    if (i + 1 < input.length()) {
                        char next = input.charAt(i + 1);
                        if (next == '"' || next == '\\' || next == '$' || next == '\n')
                            escaped = true;
                        else
                            currentToken.append(c);
                    } else
                        currentToken.append(c);
                } else
                    currentToken.append(c);
                buildingToken = true;
            } else {
                if (c == ' ') {
                    if (buildingToken) {
                        tokens.add(currentToken.toString());
                        currentToken.setLength(0);
                        buildingToken = false;
                    }
                } else if (c == '\'') {
                    inSingleQuotes = true;
                    buildingToken = true;
                } else if (c == '"') {
                    inDoubleQuotes = true;
                    buildingToken = true;
                } else if (c == '\\') {
                    escaped = true;
                    buildingToken = true;
                } else {
                    currentToken.append(c);
                    buildingToken = true;
                }
            }
        }
        if (buildingToken
                || currentToken.length() > 0
                || input.endsWith("'")
                || input.endsWith("\"")) {
            tokens.add(currentToken.toString());
        }
        return tokens;
    }

    private static String getPathToCommand(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null)
            return null;
        String[] directories = pathEnv.split(":");
        for (String dir : directories) {
            File file = new File(dir, command);
            if (file.exists() && file.canExecute())
                return file.getAbsolutePath();
        }
        return null;
    }
}