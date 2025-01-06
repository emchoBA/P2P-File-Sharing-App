import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class FileServer implements Runnable {

    private Socket socket;
    private static String rootFolder = "shared_files";
    private static final int SERVER_PORT = 6789; // udp
    public static volatile boolean keepRunning = true;
    private static ServerSocket welcomeSocket; // main for incoming connections
    private static DatagramSocket peerDatagramSocket; // ping pong

    private static Set<String> excludedFolders = new HashSet<>();
    private static Set<String> excludedMasks = new HashSet<>();

    public static synchronized void setExclusions(Set<String> folders, Set<String> masks) { //gets from GUI
        excludedFolders.clear();
        excludedFolders.addAll(folders);

        excludedMasks.clear();
        excludedMasks.addAll(masks);

        System.out.println("FileServer exclusions updated.\nFolders: " + excludedFolders + "\nMasks: " + excludedMasks);
    }

    public static synchronized void setSharedFolder(String newFolder) {
        File folder = new File(newFolder);
        if (folder.exists() && folder.isDirectory()) {
            rootFolder = folder.getAbsolutePath();
            System.out.println("Shared folder updated to: " + rootFolder);
        } else {
            System.out.println("Invalid shared folder path: " + newFolder);
        }
    }

    public static synchronized String getSharedFolder() {
        return rootFolder;
    }

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

    public FileServer(Socket socket) {
        this.socket = socket;
    }

    public static void main(String[] args) {
        ExecutorService threadService = Executors.newCachedThreadPool(); // pooling for clients

        Thread peerListenerThread = new Thread(() -> peerDatagramSocket = startPeerListener(9000)); // seperate thread for listening, not in pool
        peerListenerThread.start();

        File sharedFolder = new File(rootFolder);
        if (!sharedFolder.exists()) {
            sharedFolder.mkdirs();
        }

        try {
            welcomeSocket = new ServerSocket(SERVER_PORT);
            System.out.println(">>> Server is running...");
            while (keepRunning) { // new tread for each client
                Socket connectionSocket = welcomeSocket.accept();
                threadService.execute(new FileServer(connectionSocket));
            }
            System.out.println("Server loop ended (keepRunning = false).");
        } catch (Exception e) {
            if (keepRunning) {
                e.printStackTrace();
            } else {
                System.out.println("Server stopped by stopServer() call.");
            }
        }
    }

    // sends list of files then handle transfer requests
    @Override
    public void run() {
        try {
            System.out.println(">>> " + socket.getInetAddress().getHostAddress() + " has connected...");
            DataOutputStream dOS = new DataOutputStream(socket.getOutputStream());
            DataInputStream dIS = new DataInputStream(socket.getInputStream());


            File folder = new File(rootFolder);
            List<String> allItems = listFilesRecursively(folder);

            dOS.writeInt(allItems.size()); // send count then each item
            for (String item : allItems) {
                dOS.writeUTF(item);
            }

            // read requested file
            String requestedFile = dIS.readUTF();
            if (requestedFile == null || requestedFile.isEmpty()) {
                // client is just listing, didnt want file
                dOS.writeInt(-1);
                dOS.close();
                return;
            }

            File fileToSend = new File(rootFolder, requestedFile.replace('/', File.separatorChar)); // add root to files, especiallt important for subfolders
            /////////////////////
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
                dOS.writeInt(-1); // finished
            } else {
                dOS.writeInt(-1); // not found
            }
            dOS.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    /////////////////////////

    // listen ping send pong
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
        return socket;
    }

    // for subfolders, recursive
    private static List<String> listFilesRecursively(File base) {
        List<String> result = new ArrayList<>();
        if (!base.exists()) return result;

        if (base.isDirectory()) {
            if (!base.getName().equals(new File(rootFolder).getName())) {
                if (excludedFolders.contains(base.getName())) {
                    return result; // skip entire folder
                }
            }

            File[] children = base.listFiles();
            if (children == null || children.length == 0) {
                // empty folder -> just folder/
                if (!base.getAbsolutePath().equals(new File(rootFolder).getAbsolutePath())) {
                    String rel = getRelativePath(base);
                    result.add(rel + "/");
                }
                return result;
            }
            for (File c : children) {
                if (c.isDirectory()) {
                    result.addAll(listFilesRecursively(c));
                } else {
                    if (!isExcludedFile(c.getName())) {
                        String rel = getRelativePath(c);
                        result.add(rel);
                    }
                }
            }
        } else {
            // base is a file
            if (!isExcludedFile(base.getName())) {
                result.add(getRelativePath(base));
            }
        }
        return result;
    }

    // for folders, get relative path (root kaldır
    private static String getRelativePath(File f) {
        File root = new File(rootFolder).getAbsoluteFile();
        File absoluteFile = f.getAbsoluteFile();
        String rootPath = root.getPath();
        String filePath = absoluteFile.getPath();
        if (filePath.startsWith(rootPath)) {
            String relative = filePath.substring(rootPath.length());
            if (relative.startsWith(File.separator)) {
                relative = relative.substring(1);
            }
            relative = relative.replace(File.separatorChar, '/');
            return relative;
        }
        // fallback if not folder
        return f.getName();
    }

    private static boolean isExcludedFile(String fileName) {
        if (excludedMasks.contains(fileName)) {
            return true;
        }
        for (String mask : excludedMasks) {
            if (mask.startsWith("*.")) {
                String ext = mask.substring(1); // for extensions
                if (fileName.endsWith(ext)) {
                    return true;
                }
            }
        }
        return false;
    }
}
