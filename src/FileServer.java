import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class FileServer implements Runnable {

    private Socket socket;
    private static String rootFolder = "shared_files"; // Default shared folder

    // =========================
    // 1) Add these lines:
    public static volatile boolean keepRunning = true;
    private static ServerSocket welcomeSocket;        // so we can close later
    private static DatagramSocket peerDatagramSocket; // so we can close later

    public static void stopServer() {
        keepRunning = false;
        try {
            if (welcomeSocket != null && !welcomeSocket.isClosed()) {
                welcomeSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            if (peerDatagramSocket != null && !peerDatagramSocket.isClosed()) {
                peerDatagramSocket.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("FileServer.stopServer() called. Sockets closed.");
    }
    // =========================

    public FileServer(Socket socket) {
        this.socket = socket;
    }

    public static void main(String[] args) {
        ExecutorService threadService = Executors.newCachedThreadPool();

        // 2) store that peerDatagramSocket so we can close it if needed
        Thread peerListenerThread = new Thread(() -> peerDatagramSocket = startPeerListener(9000));
        peerListenerThread.start();

        // Ensure the shared folder exists
        File sharedFolder = new File(rootFolder);
        if (!sharedFolder.exists()) {
            sharedFolder.mkdirs();
        }

        try {
            // 3) store that welcomeSocket in static field
            welcomeSocket = new ServerSocket(6789);
            System.out.println(">>> Server is running...");
            while (keepRunning) {
                Socket connectionSocket = welcomeSocket.accept();
                threadService.execute(new FileServer(connectionSocket));
            }
            System.out.println("Server loop ended (keepRunning = false).");
        } catch (Exception e) {
            // If we closed the socket intentionally, we might see an exception
            if (keepRunning) {
                e.printStackTrace();
            } else {
                System.out.println("Server stopped by stopServer() call.");
            }
        }
    }

    @Override
    public void run() {
        try {
            System.out.println(">>> " + socket.getInetAddress().getHostAddress() + " has connected...");
            File folder = new File(rootFolder);
            File[] files = folder.listFiles();
            DataOutputStream dOS = new DataOutputStream(socket.getOutputStream());
            DataInputStream dIS = new DataInputStream(socket.getInputStream());

            // Send the list of files
            if (files != null) {
                dOS.writeInt(files.length);
                for (File file : files) {
                    dOS.writeUTF(file.getName());
                }
            } else {
                dOS.writeInt(0);
            }

            // Receive the requested file
            String requestedFile = dIS.readUTF();
            File fileToSend = new File(rootFolder, requestedFile);
            if (fileToSend.exists() && fileToSend.isFile()) {
                RandomAccessFile rAF = new RandomAccessFile(fileToSend, "r");
                int length = (int) fileToSend.length();
                int chunkCount = (int) Math.ceil(length / 256000.0);
                int[] checkArray = new int[chunkCount];
                dOS.writeInt(length);

                Random random = new Random();
                int loop = 0;

                while (loop < chunkCount) {
                    int i = random.nextInt(chunkCount);
                    if (checkArray[i] == 0) {
                        rAF.seek(i * 256000);
                        byte[] toSend = new byte[256000];
                        int read = rAF.read(toSend);
                        dOS.writeInt(i);
                        dOS.writeInt(read);
                        dOS.write(toSend, 0, read);
                        dOS.flush();
                        int ACK = dIS.readInt();
                        if (i == ACK) {
                            checkArray[i] = 1;
                            loop++;
                        }
                    }
                }
                rAF.close();
                dOS.writeInt(-1); // transfer complete
            } else {
                dOS.writeInt(-1); // file not found
            }
            dOS.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 4) changed signature to return the socket
    //    so we can store it in peerDatagramSocket
    private static DatagramSocket startPeerListener(int listenPort) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(listenPort);
            System.out.println("PeerListener started. Listening on port " + listenPort + " ...");

            while (keepRunning) {
                byte[] buffer = new byte[1024];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String msg = new String(packet.getData(), 0, packet.getLength());
                InetAddress senderAddress = packet.getAddress();
                if (msg.startsWith("PING")) {
                    System.out.println("Received PING from: " + senderAddress);
                    // Send a PONG response
                    String response = "PONG from " + InetAddress.getLocalHost().getHostAddress();
                    byte[] responseData = response.getBytes();
                    DatagramPacket responsePacket = new DatagramPacket(
                            responseData, responseData.length, senderAddress, packet.getPort()
                    );
                    socket.send(responsePacket);
                }
            }
        } catch (IOException e) {
            if (keepRunning) {
                e.printStackTrace();
            } else {
                System.out.println("PeerListener socket closed by stopServer().");
            }
        }
        return socket;  // store it if needed
    }
}

