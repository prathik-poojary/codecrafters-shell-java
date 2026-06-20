import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {
    static class Job {
        int jobNumber;
        Process process;
        String command;

        Job(int jobNumber, Process process, String command) {
            this.jobNumber = jobNumber;
            this.process = process;
            this.command = command;
        }
    }

    static ArrayList<Job> jobsList = new ArrayList<>();

    static ArrayList<String> parseCommand(String command) {
        ArrayList<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean insideSingleQuotes = false;
        boolean insideDoubleQuotes = false;
        boolean escaped = false;

        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);

            if (ch == '\\' && insideDoubleQuotes) {
                if (i + 1 < command.length()) {
                    char next = command.charAt(i + 1);
                    if (next == '\\' || next == '"') {
                        current.append(next);
                        i++;
                        continue;
                    }
                }
                current.append(ch);
                continue;
            }

            if (escaped) {
                current.append(ch);
                escaped = false;
                continue;
            }

            if (ch == '\\' && !insideSingleQuotes && !insideDoubleQuotes) {
                escaped = true;
                continue;
            }
            if (ch == '\'' && !insideDoubleQuotes) {
                insideSingleQuotes = !insideSingleQuotes;
                continue;
            }
            if (ch == '"' && !insideSingleQuotes) {
                insideDoubleQuotes = !insideDoubleQuotes;
                continue;
            }
            if (ch == ' ' && !insideSingleQuotes && !insideDoubleQuotes) {

                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }

            } else {
                current.append(ch);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    static int getNextJobNumber() {
        int num = 1;
        while (true) {
            boolean used = false;
            for (Job job : jobsList) {
                if (job.jobNumber == num) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return num;
            }
            num++;
        }
    }

    static void reapJobs() {
        ArrayList<Job> removeJobs = new ArrayList<>();
        for (int i = 0; i < jobsList.size(); i++) {
            Job job = jobsList.get(i);
            boolean finished;
            try {
                job.process.exitValue();
                finished = true;
            } catch (IllegalThreadStateException e) {
                finished = false;
            }

            if (finished) {
                char marker = ' ';
                if (i == jobsList.size() - 1) {
                    marker = '+';
                } else if (i == jobsList.size() - 2) {
                    marker = '-';
                }
                String doneCommand = job.command;
                if (doneCommand.endsWith(" &")) {
                    doneCommand = doneCommand.substring(0, doneCommand.length() - 2);
                }
                System.out.printf("[%d]%c %-24s %s%n", job.jobNumber, marker, "Done", doneCommand);
                removeJobs.add(job);
            }
        }
        jobsList.removeAll(removeJobs);
    }

    static boolean isBuiltin(String cmd) {
        return cmd.equals("echo")
                || cmd.equals("type")
                || cmd.equals("pwd")
                || cmd.equals("cd")
                || cmd.equals("jobs")
                || cmd.equals("exit");
    }

    static String runBuiltinForPipeline(String[] parts, File currentDirectory) {
        String cmd = parts[0];

        if (cmd.equals("echo")) {
            StringBuilder sb = new StringBuilder();

            for (int i = 1; i < parts.length; i++) {
                if (i > 1)
                    sb.append(" ");
                sb.append(parts[i]);
            }
            sb.append(System.lineSeparator());
            return sb.toString();
        }
        if (cmd.equals("pwd")) {
            return currentDirectory.getAbsolutePath() + System.lineSeparator();
        }
        if (cmd.equals("type")) {

            String arg = parts[1];

            if (arg.equals("echo")
                    || arg.equals("exit")
                    || arg.equals("type")
                    || arg.equals("pwd")
                    || arg.equals("cd")
                    || arg.equals("jobs")) {
                return arg + " is a shell builtin" + System.lineSeparator();
            }

            String pathEnv = System.getenv("PATH");
            String[] dirs = pathEnv.split(File.pathSeparator);

            boolean found = false;

            for (String dir : dirs) {

                File file = new File(dir, arg);

                if (file.exists() && file.canExecute()) {
                    return arg + " is " + file.getAbsolutePath() + System.lineSeparator();
                }
            }

            return arg + ": not found" + System.lineSeparator();
        }
        return "";
    }

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);
        File currentDirectory = new File(System.getProperty("user.dir"));

        while (true) {

            reapJobs();
            System.out.print("$ ");
            String command = scanner.nextLine();

            String originalCommand = command;

            String errorFile = null;
            String outputFile = null;
            boolean appendMode = false;
            boolean errorAppendMode = false;

            if (command.contains("2>>")) {
                String[] redir = command.split("2>>", 2);
                command = redir[0].trim();
                errorFile = redir[1].trim();
                errorAppendMode = true;
            } else if (command.contains("1>>")) {
                String[] redir = command.split("1>>", 2);
                command = redir[0].trim();
                outputFile = redir[1].trim();
                appendMode = true;
            } else if (command.contains(">>")) {
                String[] redir = command.split(">>", 2);
                command = redir[0].trim();
                outputFile = redir[1].trim();
                appendMode = true;
            } else if (command.contains("2>")) {
                String[] redir = command.split("2>", 2);
                command = redir[0].trim();
                errorFile = redir[1].trim();
            } else if (command.contains("1>")) {
                String[] redir = command.split("1>", 2);
                command = redir[0].trim();
                outputFile = redir[1].trim();
            } else if (command.contains(">")) {
                String[] redir = command.split(">", 2);
                command = redir[0].trim();
                outputFile = redir[1].trim();
            }

            if (command.equals("exit")) {
                break;
            }
            if (command.contains("|")) {
                String[] cmds = command.split("\\|");
                boolean hasBuiltin = false;
                for (String cmdPart : cmds) {
                    String[] pipeParts = parseCommand(cmdPart.trim()).toArray(new String[0]);
                    if (isBuiltin(pipeParts[0])) {
                        hasBuiltin = true;
                        break;
                    }
                }
                if (hasBuiltin) {
                    String[] lastParts = parseCommand(cmds[cmds.length - 1].trim()).toArray(new String[0]);
                    if (isBuiltin(lastParts[0])) {
                        System.out.print(runBuiltinForPipeline(lastParts, currentDirectory));
                        continue;
                    }
                }

                List<ProcessBuilder> builders = new ArrayList<>();

                for (String cmdPart : cmds) {
                    String[] pipeParts = parseCommand(cmdPart.trim()).toArray(new String[0]);
                    ProcessBuilder pb = new ProcessBuilder(pipeParts);

                    pb.environment().put("PATH", System.getenv("PATH"));
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    builders.add(pb);
                }

                List<Process> processes = ProcessBuilder.startPipeline(builders);
                Process last = processes.get(processes.size() - 1);
                last.getInputStream().transferTo(System.out);
                last.waitFor();
                continue;
            }
            if (command.equals("pwd")) {
                System.out.println(currentDirectory.getAbsolutePath());
                continue;
            }
            if (command.equals("jobs")) {

                ArrayList<Job> removeJobs = new ArrayList<>();

                for (int i = 0; i < jobsList.size(); i++) {
                    Job job = jobsList.get(i);
                    char marker = ' ';

                    if (i == jobsList.size() - 1) {
                        marker = '+';
                    } else if (i == jobsList.size() - 2) {
                        marker = '-';
                    }

                    if (job.process.isAlive()) {
                        System.out.printf(
                                "[%d]%c %-24s %s%n", job.jobNumber, marker, "Running", job.command);
                    } else {
                        String doneCommand = job.command;
                        if (doneCommand.endsWith(" &")) {
                            doneCommand = doneCommand.substring(0, doneCommand.length() - 2);
                        }
                        System.out.printf(
                                "[%d]%c %-24s %s%n", job.jobNumber, marker, "Done", doneCommand);
                        removeJobs.add(job);
                    }
                }
                jobsList.removeAll(removeJobs);
                continue;
            }
            if (command.startsWith("cd")) {
                String path = command.substring(3);
                if (path.equals("~")) {
                    currentDirectory = new File(System.getenv("HOME"));
                    continue;
                }

                File newDir;
                if (path.startsWith("/")) {
                    newDir = new File(path);
                } else {
                    newDir = new File(currentDirectory, path);
                }
                if (newDir.exists() && newDir.isDirectory()) {
                    try {
                        currentDirectory = newDir.getCanonicalFile();
                    } catch (Exception e) {
                        currentDirectory = newDir;
                    }
                } else {
                    System.out.println("cd: " + path + ": No such file or directory");
                }
                continue;
            }

            if (command.startsWith("echo")) {
                ArrayList<String> argsList = parseCommand(command);
                argsList.remove(0);

                String result = String.join(" ", argsList);

                if (outputFile != null) {
                    if (appendMode) {
                        java.io.FileWriter fw = new java.io.FileWriter(outputFile, true);
                        fw.write(result + System.lineSeparator());
                        fw.close();
                    } else {
                        java.io.PrintWriter pw = new java.io.PrintWriter(outputFile);
                        pw.println(result);
                        pw.close();
                    }
                } else {
                    System.out.println(result);
                }
                if (errorFile != null) {
                    if (errorAppendMode) {
                        new java.io.FileWriter(errorFile, true).close();
                    } else {
                        new java.io.PrintWriter(errorFile).close();
                    }
                }

                continue;
            }

            if (command.startsWith("type")) {

                String arg = command.substring(5);

                if (arg.equals("echo")
                        || arg.equals("exit")
                        || arg.equals("type")
                        || arg.equals("pwd")
                        || arg.equals("cd")
                        || arg.equals("jobs")) {
                    System.out.println(arg + " is a shell builtin");
                    continue;
                }

                String pathEnv = System.getenv("PATH");
                String[] dirs = pathEnv.split(File.pathSeparator);

                boolean found = false;
                for (String dir : dirs) {

                    File file = new File(dir, arg);

                    if (file.exists() && file.canExecute()) {
                        System.out.println(arg + " is " + file.getAbsolutePath());
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    System.out.println(arg + ": not found");
                }

                continue;
            }

            ArrayList<String> partsList = parseCommand(command);
            String[] parts = partsList.toArray(new String[0]);
            String cmd = parts[0];

            boolean backgroundJob = false;
            if (partsList.size() > 0 && partsList.get(partsList.size() - 1).equals("&")) {
                backgroundJob = true;
                partsList.remove(partsList.size() - 1);

                parts = partsList.toArray(new String[0]);

                cmd = parts[0];
            }

            String pathEnv = System.getenv("PATH");
            String[] dirs = pathEnv.split(File.pathSeparator);

            boolean executed = false;

            for (String dir : dirs) {

                File file = new File(dir, cmd);

                if (file.exists() && file.canExecute()) {

                    ProcessBuilder pb = new ProcessBuilder(parts);
                    pb.environment().put("PATH", System.getenv("PATH"));

                    if (outputFile != null) {
                        if (appendMode) {
                            pb.redirectOutput(
                                    ProcessBuilder.Redirect.appendTo(new File(outputFile)));
                        } else {
                            pb.redirectOutput(new File(outputFile));
                        }
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }

                    if (errorFile != null) {
                        if (errorAppendMode) {
                            pb.redirectError(ProcessBuilder.Redirect.appendTo(new File(errorFile)));
                        } else {
                            pb.redirectError(new File(errorFile));
                        }
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }

                    Process process = pb.start();
                    if (backgroundJob) {
                        int jobNumber = getNextJobNumber();
                        jobsList.add(new Job(jobNumber, process, originalCommand));
                        System.out.println("[" + jobNumber + "] " + process.pid());
                    } else {
                        process.waitFor();
                    }
                    executed = true;
                    break;
                }
            }

            if (executed) {
                continue;
            }

            System.out.println(command + ": command not found");
        }
    }
}
