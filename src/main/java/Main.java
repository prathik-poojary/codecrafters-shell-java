import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.print("$ ");

            String input = sc.nextLine();

            String cmd = input.indexOf(" ") == -1 ? input : input.substring(0, input.indexOf(" "));
            String rem = input.indexOf(" ") == -1 ? "" : input.substring(input.indexOf(" ") + 1);

            if (cmd.equals("exit")) {
                break;
            } else if (cmd.equals("type")) {
                if (rem.equals("exit") || rem.equals("echo") || rem.equals("type"))
                    System.out.println(rem + " is a shell builtin");
                else
                    System.out.println(rem + ": not found");
            } else if (cmd.equals("echo")) {
                System.out.println(rem);
            } else {
                System.out.println(cmd + ": command not found");
            }
        }
    }
}
