import java.io.File;

private static String getPathToExecutable(String command) {
    String pathEnv = System.getenv("PATH");
    if (pathEnv == null)
        return null;

    // PATH directories are separated by ':' on Unix/Linux
    String[] directories = pathEnv.split(":");
    for (String dir : directories) {
        File file = new File(dir, command);
        if (file.exists() && file.canExecute()) {
            return file.getAbsolutePath();
        }
    }
    return null;
}