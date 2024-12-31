import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class FileServer implements Runnable {

    private static final int UDP_PORT = 9876; // Port for UDP flooding
    private static final int TCP_PORT = 6789; // Port for TCP connections
    private static final String SERVER_IDENTIFIER = "FileServer";
    private Socket socket;

    public FileServer(Socket socket) {
        this.socket = socket;
    }

    public static void startUDP_Listener() {
        /**
        try {
            DatagramSocket serverSocket = new DatagramSocket(UDP_PORT);
            byte[] receiveData = new byte[1024];
            byte[] sendData = new byte[1024];
            while (true) {
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                serverSocket.receive(receivePacket);
                String sentence = new String(receivePacket.getData());
                InetAddress IPAddress = receivePacket.getAddress();
                int port = receivePacket.getPort();
                System.out.println(">>> Received: " + sentence);
                String capitalizedSentence = sentence.toUpperCase();
                sendData = capitalizedSentence.getBytes();
                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, port);
                serverSocket.send(sendPacket);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
         */
        new Thread(() -> {
            try (DatagramSocket udpSocket = new DatagramSocket(UDP_PORT)) {
                byte[] buffer = new byte[256];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                while (true) {
                    udpSocket.receive(packet);
                    String message = new String(packet.getData(), 0, packet.getLength());
                    System.out.println("Received UDP message: " + message);

                    if (message.equals("DISCOVER_PEERS")) {
                        // Send a response to the sender
                        //String response = SERVER_IDENTIFIER + " is here!";
                        String response = SERVER_IDENTIFIER;
                        byte[] responseBytes = response.getBytes();
                        DatagramPacket responsePacket = new DatagramPacket(
                                responseBytes, responseBytes.length,
                                packet.getAddress(), packet.getPort()
                        );
                        udpSocket.send(responsePacket);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    @SuppressWarnings("resource")
    public static void main(String[] args) {
        ExecutorService threadService = Executors.newCachedThreadPool();
        ServerSocket welcomeSocket = null;
        Socket connectionSocket;

        // listener for UDP flooding
        startUDP_Listener();
        try {
            welcomeSocket = new ServerSocket(TCP_PORT);
            System.out.println(">>> Server is running...");
        } catch (Exception e) {
            e.printStackTrace();
        }
        while (true) {
            try {
                connectionSocket = welcomeSocket.accept();
                threadService.execute(new FileServer(connectionSocket));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void run() {
        try {
            System.out.println(">>> " + socket.getInetAddress().getHostAddress() + " has connected...");
            System.out.println(">>> Sending data...");
            File file = new File("fileToSend.txt");
            //so you can both read and write to its instance
            RandomAccessFile rAF = new RandomAccessFile(file, "r");
            int length = (int) file.length(); //700 byte

            int chunkCount = (int) Math.ceil(length / 256000.0);
            //2.7 -> 3
            //to track which chunks have been sent (0 means not sent, 1 means sent and acknowledged).
            int[] checkArray = new int[chunkCount];
            DataInputStream dIS = new DataInputStream(socket.getInputStream());
            DataOutputStream dOS = new DataOutputStream(socket.getOutputStream());
            //sends the total file size (length) to the client, so the client knows how large the file is.
            dOS.writeInt(length);
            Random random = new Random();
            int loop = 0;
            while (loop < chunkCount) {
                int i = random.nextInt(chunkCount);
                if (checkArray[i] == 0) {
                    //moves file pointer to the start of the chunk
                    rAF.seek(i * 256000);
                    //1*256 -> 256. byte 511.999
                    byte[] toSend = new byte[256000];
                    int read = rAF.read(toSend);
                    dOS.writeInt(i); // send chunk no
                    dOS.writeInt(read); // send read length
                    dOS.write(toSend, 0, read); // send data
                    dOS.flush();
                    int ACK = dIS.readInt();
                    if (i == ACK) {
                        checkArray[i] = 1;
                        loop++;
                    }
                }
            }
            System.out.println(">>> Sent all chunks to " + socket.getInetAddress().getHostAddress() + "...");
            rAF.close();
            dOS.writeInt(-1);
            dOS.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

}
