import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class FileClient extends Thread {

    private String IP;
    private int PORT;

    public FileClient(String IP, int PORT, String name) {
        super(name); //assigns a name to the thread
        this.IP = IP; //servers IP
        this.PORT = PORT; //servers port
    }

    public static List<String[]> udpFlood(String broadcastAddress, int udpPort) {
        List<String[]> discoveredPeers = new ArrayList<>();
        try (DatagramSocket udpSocket = new DatagramSocket()) {
            udpSocket.setBroadcast(true);
            String discoveryMessage = "DISCOVER_PEERS";
            byte[] messageBytes = discoveryMessage.getBytes();

            DatagramPacket packet = new DatagramPacket(
                    messageBytes, messageBytes.length,
                    InetAddress.getByName(broadcastAddress), udpPort
            );

            System.out.println("Sending discovery message...");
            udpSocket.send(packet);

            // Listen for responses
            byte[] buffer = new byte[256];
            DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);

            udpSocket.setSoTimeout(2000); // Timeout for responses
            while (true) {
                try {
                    udpSocket.receive(responsePacket);
                    String response = new String(responsePacket.getData(), 0, responsePacket.getLength());
                    System.out.println("Discovered peer: " + response);
                    discoveredPeers.add(new String[]{responsePacket.getAddress().getHostAddress(), "6789"}); // Default port assumed
                } catch (Exception e) {
                    System.out.println("UDP discovery timeout or error: " + e.getMessage());
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return discoveredPeers;
    }
    public static void main(String[] args) {
        try {
            String broadcastAddress = "255.255.255.255"; // Adjust for your network
            List<String[]> peers = udpFlood(broadcastAddress, 9876); // Broadcast UDP discovery message

            if (peers.isEmpty()) {
                System.out.println("No peers discovered.");
                return;
            }

            for (int i = 0; i < peers.size(); i++) {
                String ip = peers.get(i)[0];
                int port = Integer.parseInt(peers.get(i)[1]);
                new FileClient(ip, port, "fileToReceive" + (i + 1)).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
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
