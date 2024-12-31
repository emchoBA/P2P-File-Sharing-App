import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.net.Socket;

public class FileClient extends Thread {

    private String IP;
    private int PORT;

    public FileClient(String IP, int PORT, String name) {
        super(name); //assigns a name to the thread
        this.IP = IP; //servers IP
        this.PORT = PORT; //servers port
    }

    public static void main(String[] args) {
        try {
            if (args.length != 3) {
                System.out.println("USAGE: java -jar FileClient.jar <IP> <PORT> <number>\r\n\r\n"
                        + "Where <IP> is a string, <PORT> is a number and <number> represents concurrent file downloads.");
                //java -jar FileClient.jar 127.0.0.1 6789 3 -> 3 threads downloading the file from server
            } else {
                int a = Integer.parseInt(args[2]);
                System.out.println("Creating " + a + " thread(s)...");
                for (int i = 0; i < a; i++) {
                    new FileClient(args[0], Integer.parseInt(args[1]), "fileToReceive" + (i + 1)).start();
                }
            }
        } catch (Exception e) {
            System.out.println("USAGE: java -jar FileClient.jar <IP> <PORT> <number>\r\n\r\n"
                    + "Where <IP> is a string, <PORT> is a number and <number> represents concurrent file downloads.");
        }
    }

    @Override
    public void run() {
        try {
            File file = new File(getName());
            if (!file.exists()) {
                file.createNewFile();
            }
            RandomAccessFile rAF = new RandomAccessFile(file, "rw");
            Socket socket = new Socket(IP, PORT);
            System.out.println(getName() + " has connected to server...");
            DataInputStream dIS = new DataInputStream(socket.getInputStream());
            DataOutputStream dOS = new DataOutputStream(socket.getOutputStream());
            int length = dIS.readInt();
            //700 byte
            System.out.println(getName() + " has read " + length + " for fileLength...");
            rAF.setLength(length);
            //file length = 700 byte
            int i;
            while ((i = dIS.readInt()) != -1) {
                System.out.println(getName() + " has read " + i + " for chunkID...");
                rAF.seek(i * 256000);
                int chunkLength = dIS.readInt();
                System.out.println(getName() + " has read " + chunkLength + " for chunkSize...");
                byte[] toReceive = new byte[chunkLength];
                dIS.readFully(toReceive);
                System.out.println(getName() + " has read " + chunkLength + " bytes for chunkID " + i + "...");
                rAF.write(toReceive);
                dOS.writeInt(i);
                System.out.println(getName() + " has sent " + i + " for ACK...");
            }
            System.out.println(getName() + " has read " + i + " for chunkID...");
            rAF.close();
            socket.close();
        } catch (Exception e) {
            System.out.println("java -jar FileClient.jar <IP> <PORT> <number>\r\n"
                    + "Where <IP> is a string, <PORT> is a number and <number> represents concurrent file downloads.");
        }
    }

}
