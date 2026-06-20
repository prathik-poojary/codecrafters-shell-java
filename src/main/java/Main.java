import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

public class Main {
    private static class BgJob {
        int id;
        Process proc;
        String cmd;

        BgJob(int id, Process proc, String cmd) {
            this.id = id;
            this.proc = proc;
            this.cmd = cmd;
        }
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        String currentDir;
        try {
            currentDir = new File(".").getCanonicalPath();
        } catch (Exception e) {
            currentDir = new File(".").getAbsolutePath();
        }

        final List<BgJob> bgJobs = new ArrayList<>();
        int nextJobId = 1;

        while (true) {
            // Automatic reaping before the prompt: display Done lines for exited jobs
            synchronized (bgJobs) {
                List<BgJob> snapshot = new ArrayList<>(bgJobs);
                if (!snapshot.isEmpty()) {
                    int recentId = -1;
                    int secondId = -1;
                    for (BgJob j : snapshot) {
                        if (j.id > recentId) {
                            secondId = recentId;
                            recentId = j.id;
                        } else if (j.id > secondId) {
                            secondId = j.id;
                        }
                    }
                    for (BgJob j : snapshot) {
                        if (j.proc == null || !isProcAlive(j.proc)) {
                            String marker = " ";
                            if (j.id == recentId)
                                marker = "+";
                            else if (j.id == secondId)
                                marker = "-";
                            String status = "Done";
                            StringBuilder sb = new StringBuilder();
                            sb.append("[").append(j.id).append("]").append(marker).append("  ");
                            sb.append(status);
                            for (int k = status.length(); k < 24; k++)
                                sb.append(' ');
                            sb.append(j.cmd);
                            System.out.println(sb.toString());
                        }
                    }
                    // remove completed jobs
                    bgJobs.removeIf(j -> j.proc == null || !isProcAlive(j.proc));
                }
            }

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

            // detect background '&' and redirection tokens: '>', '1>', and '2>' (support
            // attached
            // filenames too)
            List<String> partsList = new ArrayList<>(Arrays.asList(parts));
            boolean background = false;
            if (!partsList.isEmpty() && partsList.get(partsList.size() - 1).equals("&")) {
                background = true;
                partsList.remove(partsList.size() - 1);
            }
            File redirectStdout = null;
            File redirectStderr = null;
            boolean appendStdout = false;
            boolean appendStderr = false;
            int pi = 0;
            while (pi < partsList.size()) {
                String tok = partsList.get(pi);
                int gt = tok.indexOf('>');
                if (gt >= 0) {
                    // detect whether it's '>' or '>>'
                    int gtCount = 1;
                    if (gt + 1 < tok.length() && tok.charAt(gt + 1) == '>')
                        gtCount = 2;
                    String fdPrefix = tok.substring(0, gt);
                    String attached = tok.substring(gt + gtCount);
                    String fname = null;
                    boolean append = (gtCount == 2);
                    if (!attached.isEmpty()) {
                        fname = attached;
                        partsList.remove(pi);
                    } else if (pi + 1 < partsList.size()) {
                        fname = partsList.get(pi + 1);
                        partsList.remove(pi);
                        partsList.remove(pi);
                    } else {
                        // stray '>' with no filename: remove token and continue
                        partsList.remove(pi);
                        continue;
                    }

                    File f;
                    if (fname.startsWith("/")) {
                        f = new File(fname);
                    } else {
                        f = new File(currentDir, fname);
                    }

                    int fd = 1;
                    if (!fdPrefix.isEmpty()) {
                        try {
                            fd = Integer.parseInt(fdPrefix);
                        } catch (Exception ex) {
                            fd = 1;
                        }
                    }

                    if (fd == 2) {
                        redirectStderr = f;
                        appendStderr = append;
                    } else {
                        // default and fd==1
                        redirectStdout = f;
                        appendStdout = append;
                    }
                    // don't increment pi because we've removed current token(s)
                    continue;
                }
                pi++;
            }

            parts = partsList.toArray(new String[0]);

            String cmd = parts[0];
            PrintStream outStream = System.out;
            boolean closeOutStream = false;
            if (redirectStdout != null) {
                try {
                    outStream = new PrintStream(new FileOutputStream(redirectStdout, appendStdout));
                    closeOutStream = true;
                } catch (Exception e) {
                    outStream = System.out;
                    closeOutStream = false;
                }
            }
            // Ensure stderr redirection target exists. Truncate only when not appending.
            if (redirectStderr != null) {
                try {
                    new FileOutputStream(redirectStderr, appendStderr).close();
                } catch (Exception e) {
                    // ignore
                }
            }
            if (cmd.equals("exit")) {
                if (background) {
                    // ignore backgrounded exit (tests won't background exit); continue loop
                    continue;
                }
                if (closeOutStream)
                    outStream.close();
                break;
            }

            if (cmd.equals("pwd")) {
                if (background) {
                    final PrintStream bo = outStream;
                    final boolean bc = closeOutStream;
                    final String cwd = currentDir;
                    new Thread(
                            () -> {
                                bo.println(cwd);
                                if (bc)
                                    bo.close();
                            })
                            .start();
                    continue;
                }
                outStream.println(currentDir);
                if (closeOutStream)
                    outStream.close();
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
                        outStream.println("cd: " + target + ": No such file or directory");
                    }
                } catch (Exception e) {
                    outStream.println("cd: " + target + ": No such file or directory");
                }
                if (background) {
                    final String ftarget = target;
                    final PrintStream bo = outStream;
                    final boolean bc = closeOutStream;
                    final String cwdVar = currentDir;
                    new Thread(
                            () -> {
                                File dir2;
                                if (ftarget.startsWith("/")) {
                                    dir2 = new File(ftarget);
                                } else if (ftarget.startsWith("~")) {
                                    String home = System.getenv("HOME");
                                    if (home == null || home.isEmpty())
                                        home = System.getProperty("user.home");
                                    String rest = ftarget.length() > 1
                                            ? ftarget.substring(1)
                                            : "";
                                    dir2 = new File(home + rest);
                                } else {
                                    dir2 = new File(cwdVar, ftarget);
                                }
                                try {
                                    if (dir2.exists() && dir2.isDirectory()) {
                                        try {
                                            // note: background cd won't affect main
                                            // thread's currentDir
                                        } catch (Exception ex) {
                                        }
                                    } else {
                                        bo.println(
                                                "cd: "
                                                        + ftarget
                                                        + ": No such file or directory");
                                    }
                                } catch (Exception e) {
                                    bo.println(
                                            "cd: "
                                                    + ftarget
                                                    + ": No such file or directory");
                                }
                                if (bc)
                                    bo.close();
                            })
                            .start();
                    continue;
                }
                if (closeOutStream)
                    outStream.close();
                continue;
            }

            if (cmd.equals("echo")) {
                if (background) {
                    final String[] fparts = parts;
                    final PrintStream bo = outStream;
                    final boolean bc = closeOutStream;
                    new Thread(
                            () -> {
                                if (fparts.length == 1) {
                                    bo.println();
                                } else {
                                    StringBuilder sb = new StringBuilder();
                                    for (int i = 1; i < fparts.length; i++) {
                                        if (i > 1)
                                            sb.append(' ');
                                        sb.append(fparts[i]);
                                    }
                                    bo.println(sb.toString());
                                }
                                if (bc)
                                    bo.close();
                            })
                            .start();
                } else {
                    if (parts.length == 1) {
                        outStream.println();
                    } else {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 1; i < parts.length; i++) {
                            if (i > 1)
                                sb.append(' ');
                            sb.append(parts[i]);
                        }
                        outStream.println(sb.toString());
                    }
                    if (closeOutStream)
                        outStream.close();
                }
            } else if (cmd.equals("type")) {
                if (parts.length == 1) {
                    outStream.println();
                    if (closeOutStream)
                        outStream.close();
                    continue;
                }
                String arg = parts[1];
                if (background) {
                    final String barg = arg;
                    final PrintStream bo = outStream;
                    final boolean bc = closeOutStream;
                    new Thread(
                            () -> {
                                if (barg.equals("echo")
                                        || barg.equals("exit")
                                        || barg.equals("type")
                                        || barg.equals("pwd")
                                        || barg.equals("cd")) {
                                    bo.println(barg + " is a shell builtin");
                                } else {
                                    boolean found = false;
                                    String pathEnv = System.getenv("PATH");
                                    if (pathEnv != null && !pathEnv.isEmpty()) {
                                        String[] dirs = pathEnv.split(File.pathSeparator);
                                        for (String d : dirs) {
                                            if (d == null || d.isEmpty())
                                                continue;
                                            File f = new File(d, barg);
                                            if (f.exists() && f.isFile()) {
                                                if (f.canExecute()) {
                                                    bo.println(
                                                            barg
                                                                    + " is "
                                                                    + f.getAbsolutePath());
                                                    found = true;
                                                    break;
                                                } else {
                                                    continue;
                                                }
                                            }
                                        }
                                    }
                                    if (!found) {
                                        bo.println(barg + ": not found");
                                    }
                                }
                                if (bc)
                                    bo.close();
                            })
                            .start();
                } else {
                    if (arg.equals("echo")
                            || arg.equals("exit")
                            || arg.equals("type")
                            || arg.equals("pwd")
                            || arg.equals("cd")
                            || arg.equals("jobs")) {
                        outStream.println(arg + " is a shell builtin");
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
                                        outStream.println(arg + " is " + f.getAbsolutePath());
                                        found = true;
                                        break;
                                    } else {
                                        continue;
                                    }
                                }
                            }
                        }
                        if (!found) {
                            outStream.println(arg + ": not found");
                        }
                    }
                    if (closeOutStream)
                        outStream.close();
                }
            } else {
                if (cmd.equals("jobs")) {
                    // print background jobs (only running ones) with marker and padding
                    synchronized (bgJobs) {
                        // Snapshot jobs to inspect and print statuses
                        List<BgJob> snapshot = new ArrayList<>(bgJobs);
                        if (!snapshot.isEmpty()) {
                            int recentId = -1;
                            int secondId = -1;
                            for (BgJob j : snapshot) {
                                if (j.id > recentId) {
                                    secondId = recentId;
                                    recentId = j.id;
                                } else if (j.id > secondId) {
                                    secondId = j.id;
                                }
                            }
                            for (BgJob j : snapshot) {
                                String marker = " ";
                                if (j.id == recentId)
                                    marker = "+";
                                else if (j.id == secondId)
                                    marker = "-";
                                String status = isProcAlive(j.proc) ? "Running" : "Done";
                                StringBuilder sb = new StringBuilder();
                                sb.append("[").append(j.id).append("]").append(marker).append("  ");
                                sb.append(status);
                                for (int k = status.length(); k < 24; k++)
                                    sb.append(' ');
                                if ("Running".equals(status)) {
                                    sb.append(j.cmd).append(" &");
                                } else {
                                    sb.append(j.cmd);
                                }
                                outStream.println(sb.toString());
                            }
                            // remove completed (Done) jobs from table
                            bgJobs.removeIf(j -> j.proc == null || !isProcAlive(j.proc));
                        }
                    }
                    if (closeOutStream)
                        outStream.close();
                    continue;
                }
                // Not a builtin; handle pipelines or run executable in PATH
                // detect simple pipeline with a single '|'
                int pipeIndex = -1;
                for (int ii = 0; ii < parts.length; ii++) {
                    if (parts[ii].equals("|")) {
                        pipeIndex = ii;
                        break;
                    }
                }
                if (pipeIndex >= 0) {
                    String[] left = Arrays.copyOfRange(parts, 0, pipeIndex);
                    String[] right = Arrays.copyOfRange(parts, pipeIndex + 1, parts.length);
                    String leftExe = findInPath(left[0]);
                    String rightExe = findInPath(right[0]);
                    if (leftExe == null) {
                        outStream.println(left[0] + ": command not found");
                        if (closeOutStream)
                            outStream.close();
                        continue;
                    }
                    if (rightExe == null) {
                        outStream.println(right[0] + ": command not found");
                        if (closeOutStream)
                            outStream.close();
                        continue;
                    }

                    try {
                        ProcessBuilder pb1 = new ProcessBuilder(Arrays.asList(left));
                        ProcessBuilder pb2 = new ProcessBuilder(Arrays.asList(right));
                        pb1.directory(new File(currentDir));
                        pb2.directory(new File(currentDir));
                        if (redirectStderr != null) {
                            if (appendStderr)
                                pb1.redirectError(ProcessBuilder.Redirect.appendTo(redirectStderr));
                            else
                                pb1.redirectError(redirectStderr);
                            if (appendStderr)
                                pb2.redirectError(ProcessBuilder.Redirect.appendTo(redirectStderr));
                            else
                                pb2.redirectError(redirectStderr);
                        }

                        Process p1 = pb1.start();
                        Process p2 = pb2.start();

                        // pump p1.stdout -> p2.stdin
                        Thread pump = new Thread(
                                () -> {
                                    try (InputStream is = p1.getInputStream();
                                            OutputStream os = p2.getOutputStream()) {
                                        byte[] buf = new byte[1024];
                                        int r;
                                        while ((r = is.read(buf)) != -1) {
                                            os.write(buf, 0, r);
                                            os.flush();
                                        }
                                        try {
                                            os.close();
                                        } catch (Exception ex) {
                                        }
                                    } catch (Exception e) {
                                    }
                                });
                        pump.setDaemon(true);
                        pump.start();

                        // forward stderr streams if not redirected
                        if (redirectStderr == null) {
                            Thread e1 = new Thread(
                                    () -> {
                                        try (InputStream es = p1.getErrorStream()) {
                                            byte[] b = new byte[1024];
                                            int n;
                                            OutputStream os = System.out;
                                            while ((n = es.read(b)) != -1) {
                                                os.write(b, 0, n);
                                                os.flush();
                                            }
                                        } catch (Exception ex) {
                                        }
                                    });
                            e1.setDaemon(true);
                            e1.start();
                            Thread e2 = new Thread(
                                    () -> {
                                        try (InputStream es = p2.getErrorStream()) {
                                            byte[] b = new byte[1024];
                                            int n;
                                            OutputStream os = System.out;
                                            while ((n = es.read(b)) != -1) {
                                                os.write(b, 0, n);
                                                os.flush();
                                            }
                                        } catch (Exception ex) {
                                        }
                                    });
                            e2.setDaemon(true);
                            e2.start();
                        }

                        if (background) {
                            int jid;
                            synchronized (bgJobs) {
                                int maxId = 0;
                                for (BgJob existing : bgJobs)
                                    if (existing.id > maxId)
                                        maxId = existing.id;
                                jid = maxId + 1;
                                bgJobs.add(
                                        new BgJob(
                                                jid,
                                                p2,
                                                String.join(" ", left)
                                                        + " | "
                                                        + String.join(" ", right)));
                            }
                            System.out.println("[" + jid + "] " + p2.pid());
                            System.out.flush();

                            // background: stream p2 stdout to terminal in daemon thread
                            Thread outt = new Thread(
                                    () -> {
                                        try (InputStream is = p2.getInputStream()) {
                                            byte[] b = new byte[1024];
                                            int n;
                                            OutputStream os = System.out;
                                            while ((n = is.read(b)) != -1) {
                                                os.write(b, 0, n);
                                                os.flush();
                                            }
                                        } catch (Exception ex) {
                                        }
                                    });
                            outt.setDaemon(true);
                            outt.start();

                        } else {
                            // foreground: stream p2 stdout to chosen outStream
                            try (InputStream is = p2.getInputStream()) {
                                byte[] buf = new byte[1024];
                                int r;
                                while ((r = is.read(buf)) != -1) {
                                    outStream.write(buf, 0, r);
                                    outStream.flush();
                                }
                            } catch (Exception ex) {
                            }
                            // wait for p2, then ensure p1 is stopped
                            p2.waitFor();
                            if (p1.isAlive()) {
                                try {
                                    p1.destroy();
                                } catch (Exception ex) {
                                }
                                try {
                                    p1.waitFor();
                                } catch (Exception ex) {
                                }
                            }
                        }
                        if (closeOutStream)
                            outStream.close();
                    } catch (Exception e) {
                        outStream.println("command not found");
                        if (closeOutStream)
                            outStream.close();
                    }
                    continue;
                }

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
                        if (redirectStdout == null && redirectStderr == null) {
                            pb.redirectErrorStream(true);
                        } else {
                            if (redirectStdout != null) {
                                if (appendStdout)
                                    pb.redirectOutput(
                                            ProcessBuilder.Redirect.appendTo(redirectStdout));
                                else
                                    pb.redirectOutput(redirectStdout);
                            }
                            if (redirectStderr != null) {
                                if (appendStderr)
                                    pb.redirectError(
                                            ProcessBuilder.Redirect.appendTo(redirectStderr));
                                else
                                    pb.redirectError(redirectStderr);
                            }
                        }
                        Process p = pb.start();
                        if (background) {
                            // register background job and print job id + pid
                            int jid;
                            synchronized (bgJobs) {
                                // assign jid as 1 if table empty, otherwise one more than highest
                                // current id
                                int maxId = 0;
                                for (BgJob existing : bgJobs) {
                                    if (existing.id > maxId)
                                        maxId = existing.id;
                                }
                                jid = maxId + 1;
                                bgJobs.add(new BgJob(jid, p, String.join(" ", cmdWithArgs)));
                            }
                            System.out.println("[" + jid + "] " + p.pid());
                            System.out.flush();
                        }
                        // If backgrounded, don't wait — consume output streams in separate threads
                        // as needed
                        if (background) {
                            if (redirectStdout == null && redirectStderr == null) {
                                Thread t = new Thread(
                                        () -> {
                                            try (InputStream is = p.getInputStream()) {
                                                byte[] buffer = new byte[1024];
                                                int n;
                                                OutputStream os = System.out;
                                                while ((n = is.read(buffer)) != -1) {
                                                    os.write(buffer, 0, n);
                                                    os.flush();
                                                }
                                            } catch (Exception e) {
                                            }
                                        });
                                t.setDaemon(true);
                                t.start();
                            } else if (redirectStdout != null && redirectStderr == null) {
                                Thread t = new Thread(
                                        () -> {
                                            try (InputStream es = p.getErrorStream()) {
                                                byte[] buffer = new byte[1024];
                                                int n;
                                                OutputStream os = System.out;
                                                while ((n = es.read(buffer)) != -1) {
                                                    os.write(buffer, 0, n);
                                                    os.flush();
                                                }
                                            } catch (Exception e) {
                                            }
                                        });
                                t.setDaemon(true);
                                t.start();
                            } else if (redirectStdout == null && redirectStderr != null) {
                                Thread t = new Thread(
                                        () -> {
                                            try (InputStream is = p.getInputStream()) {
                                                byte[] buffer = new byte[1024];
                                                int n;
                                                OutputStream os = System.out;
                                                while ((n = is.read(buffer)) != -1) {
                                                    os.write(buffer, 0, n);
                                                    os.flush();
                                                }
                                            } catch (Exception e) {
                                            }
                                        });
                                t.setDaemon(true);
                                t.start();
                            }
                            // don't wait for p; let it run in background
                        } else {
                            if (redirectStdout == null && redirectStderr == null) {
                                // no redirection: merge stdout+stderr and stream to terminal
                                InputStream is = p.getInputStream();
                                byte[] buffer = new byte[1024];
                                int n;
                                OutputStream os = System.out;
                                while ((n = is.read(buffer)) != -1) {
                                    os.write(buffer, 0, n);
                                    os.flush();
                                }
                            } else if (redirectStdout != null && redirectStderr == null) {
                                // stdout -> file, stderr -> terminal
                                InputStream es = p.getErrorStream();
                                byte[] buffer = new byte[1024];
                                int n;
                                OutputStream os = System.out;
                                while ((n = es.read(buffer)) != -1) {
                                    os.write(buffer, 0, n);
                                    os.flush();
                                }
                            } else if (redirectStdout == null && redirectStderr != null) {
                                // stdout -> terminal, stderr -> file
                                InputStream is = p.getInputStream();
                                byte[] buffer = new byte[1024];
                                int n;
                                OutputStream os = System.out;
                                while ((n = is.read(buffer)) != -1) {
                                    os.write(buffer, 0, n);
                                    os.flush();
                                }
                            } else {
                                // both redirected: nothing to stream to terminal
                            }

                            p.waitFor();
                        }
                        if (closeOutStream)
                            outStream.close();
                    } catch (Exception e) {
                        outStream.println(cmd + ": command not found");
                        if (closeOutStream)
                            outStream.close();
                    }
                } else {
                    outStream.println(input + ": command not found");
                    if (closeOutStream)
                        outStream.close();
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

    private static boolean isProcAlive(Process p) {
        if (p == null)
            return false;
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
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
                if (c == '\'' || c == '"') {
                    char quote = c;
                    i++; // skip opening quote
                    if (quote == '\'') {
                        // single quotes: everything is literal until next single quote
                        int start = i;
                        while (i < n && input.charAt(i) != quote)
                            i++;
                        sb.append(input, start, i);
                        if (i < n && input.charAt(i) == quote)
                            i++; // skip closing quote
                        continue;
                    } else {
                        // double quotes: handle escapes for \" and \\\ only
                        while (i < n) {
                            char d = input.charAt(i);
                            if (d == '"') {
                                i++; // skip closing quote
                                break;
                            }
                            if (d == '\\') {
                                // lookahead
                                i++;
                                if (i < n) {
                                    char next = input.charAt(i);
                                    if (next == '"' || next == '\\') {
                                        sb.append(next);
                                        i++;
                                        continue;
                                    } else {
                                        // backslash not escaping a special char: keep the backslash
                                        // literally
                                        sb.append('\\');
                                        sb.append(next);
                                        i++;
                                        continue;
                                    }
                                } else {
                                    // trailing backslash inside double quotes: treat as literal
                                    // backslash
                                    sb.append('\\');
                                    break;
                                }
                            }
                            sb.append(d);
                            i++;
                        }
                        continue;
                    }
                }
                if (c == '\\') {
                    // escape next character (outside quotes)
                    i++;
                    if (i < n) {
                        sb.append(input.charAt(i));
                        i++;
                        continue;
                    } else {
                        // trailing backslash: treat as literal backslash removed
                        break;
                    }
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