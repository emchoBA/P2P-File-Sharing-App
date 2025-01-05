import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class FileClient {

    private static String destinationFolder = "downloads";
    private static final int SERVER_PORT = 6789;
    private static final int BROADCAST_PORT = 9000;
    private static final String BROADCAST_IP = "192.168.1.255";

    public static Set<String> listAllFilesFromPeers() {
        List<String> peerIPs = startPeerDiscovery(BROADCAST_IP, BROADCAST_PORT);
        Set<String> foundFiles = new LinkedHashSet<>();

        for (String peerIP : peerIPs) {
            try (Socket socket = new Socket(peerIP, SERVER_PORT);
                 DataInputStream dIS = new DataInputStream(socket.getInputStream());
                 DataOutputStream dOS = new DataOutputStream(socket.getOutputStream())) {

                int fileCount = dIS.readInt();
                for (int i = 0; i < fileCount; i++) {
                    String fileName = dIS.readUTF();
                    foundFiles.add(fileName);
                }
                // client must send something as a requested file
                dOS.writeUTF("");
                int lengthFromThisPeer = dIS.readInt();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return foundFiles;
    }

    // Update download folder path
    public static synchronized void setDownloadFolder(String newFolder) {
        File folder = new File(newFolder);
        if (folder.exists() && folder.isDirectory()) {
            destinationFolder = folder.getAbsolutePath();
            System.out.println("Download folder updated to: " + destinationFolder);
        } else {
            System.out.println("Invalid download folder path: " + newFolder);
        }
    }

    public static synchronized String getDownloadFolder() {
        return destinationFolder;
    }

    public static void search(String fileName) {
        try {
            List<String> peerIPs = startPeerDiscovery(BROADCAST_IP, BROADCAST_PORT);
            if (peerIPs.isEmpty()) {
                System.out.println("No peers discovered. Exiting search.");
                return;
            }

            List<String> peersWithFile = new ArrayList<>();
            int fileLength = -1;

            for (String peerIP : peerIPs) {
                try (Socket socket = new Socket(peerIP, SERVER_PORT);
                     DataInputStream dIS = new DataInputStream(socket.getInputStream());
                     DataOutputStream dOS = new DataOutputStream(socket.getOutputStream())) {

                    int fileCount = dIS.readInt();
                    for (int i = 0; i < fileCount; i++) {
                        dIS.readUTF(); // skip listing
                    }

                    dOS.writeUTF(fileName);
                    int lengthFromThisPeer = dIS.readInt();

                    if (lengthFromThisPeer > 0) {
                        peersWithFile.add(peerIP);
                        if (fileLength < 0) {
                            fileLength = lengthFromThisPeer;
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            System.out.println("Number of peers that have \"" + fileName + "\": " + peersWithFile.size());
            if (peersWithFile.isEmpty()) {
                System.out.println("No peers have the file \"" + fileName + "\".");
                return;
            }

            File downloadFolderFile = new File(destinationFolder);
            if (!downloadFolderFile.exists()) {
                downloadFolderFile.mkdirs();
            }

            // create local path preserving subfolders
            File outFile = new File(downloadFolderFile, fileName.replace('/', File.separatorChar));
            outFile.getParentFile().mkdirs();

            try (RandomAccessFile rAF = new RandomAccessFile(outFile, "rw")) {
                rAF.setLength(fileLength);

                int chunkSize = 256000;
                int totalChunks = (int) Math.ceil(fileLength / (double) chunkSize);

                MultiSourceDownloader msDownloader = new MultiSourceDownloader(fileName, rAF, totalChunks);

                ExecutorService executor = Executors.newFixedThreadPool(peersWithFile.size());
                List<Future<?>> futures = new ArrayList<>();

                for (String peerIP : peersWithFile) {
                    int finalFileLength = fileLength;
                    futures.add(executor.submit(() ->
                            downloadChunksFromPeer(peerIP, SERVER_PORT, msDownloader, finalFileLength)
                    ));
                }

                boolean allDone = false;
                while (!allDone) {
                    allDone = msDownloader.allChunksDownloaded()
                            || futures.stream().allMatch(Future::isDone);

                    if (!allDone) {
                        Thread.sleep(500);
                    }
                }

                executor.shutdownNow();

                if (msDownloader.allChunksDownloaded()) {
                    System.out.println(">>> Multi-source download complete for file: " + fileName);
                } else {
                    System.out.println(">>> Could not download all chunks from available peers.");
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void downloadChunksFromPeer(
            String peerIP,
            int port,
            MultiSourceDownloader msDownloader,
            int fileLength
    ) {
        try (Socket socket = new Socket(peerIP, port);
             DataInputStream dIS = new DataInputStream(socket.getInputStream());
             DataOutputStream dOS = new DataOutputStream(socket.getOutputStream())) {

            System.out.println("Connecting to peer: " + peerIP);

            int fileCount = dIS.readInt();
            for (int i = 0; i < fileCount; i++) {
                dIS.readUTF();
            }

            dOS.writeUTF(msDownloader.getFileName());
            int length = dIS.readInt();
            if (length <= 0) {
                System.out.println("Peer " + peerIP + " -> does NOT have file " + msDownloader.getFileName());
                return;
            }

            RandomAccessFile rAF = msDownloader.getRandomAccessFile();
            int chunkSize = 256000;
            int chunksReceivedHere = 0;

            while (!msDownloader.allChunksDownloaded()) {
                int chunkIndex = dIS.readInt();
                if (chunkIndex == -1) {
                    break;
                }

                int readBytes = dIS.readInt();
                byte[] buffer = new byte[readBytes];
                dIS.readFully(buffer);

                if (!msDownloader.isChunkDownloaded(chunkIndex)) {
                    synchronized (rAF) {
                        rAF.seek((long)chunkIndex * chunkSize);
                        rAF.write(buffer, 0, readBytes);
                    }
                    msDownloader.setChunkDownloaded(chunkIndex);
                    chunksReceivedHere++;
                }

                dOS.writeInt(chunkIndex);
                dOS.flush();

                if (msDownloader.allChunksDownloaded()) {
                    break;
                }
            }

            System.out.println("Peer " + peerIP + " -> retrieved " + chunksReceivedHere + " new chunk(s).");

        } catch (IOException e) {
            System.out.println("Peer " + peerIP + " -> error: " + e.getMessage());
        }
    }

    private static List<String> startPeerDiscovery(String broadcastAddress, int targetPort) {
        List<String> peerIPs = new ArrayList<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            InetAddress broadcastInet = InetAddress.getByName(broadcastAddress);
            String broadcastMsg = "PING from peer!";
            byte[] sendData = broadcastMsg.getBytes();

            for (int i = 0; i < 10; i++) {
                DatagramPacket packet = new DatagramPacket(sendData, sendData.length, broadcastInet, targetPort);
                socket.send(packet);
                System.out.println("Broadcast packet sent: " + (i + 1));
                Thread.sleep(500);
            }

            socket.setSoTimeout(5000);
            byte[] buffer = new byte[1024];

            while (true) {
                try {
                    DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                    socket.receive(responsePacket);
                    String msg = new String(responsePacket.getData(), 0, responsePacket.getLength());
                    if (msg.startsWith("PONG")) {
                        String peerIP = responsePacket.getAddress().getHostAddress();
                        System.out.println("Discovered peer: " + peerIP);
                        if (!peerIPs.contains(peerIP)) {
                            peerIPs.add(peerIP);
                        }
                    }
                } catch (SocketTimeoutException e) {
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("Peer discovery error: " + e.getMessage());
        }
        return peerIPs;
    }
}

class MultiSourceDownloader {
    private final String fileName;
    private final RandomAccessFile rAF;
    private final boolean[] chunkDownloaded;
    private final int totalChunks;
    private final java.util.concurrent.atomic.AtomicInteger downloadedCount = new java.util.concurrent.atomic.AtomicInteger(0);

    public MultiSourceDownloader(String fileName, RandomAccessFile rAF, int totalChunks) {
        this.fileName = fileName;
        this.rAF = rAF;
        this.totalChunks = totalChunks;
        this.chunkDownloaded = new boolean[totalChunks];
    }

    public String getFileName() {
        return fileName;
    }

    public RandomAccessFile getRandomAccessFile() {
        return rAF;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public synchronized boolean isChunkDownloaded(int index) {
        return chunkDownloaded[index];
    }

    public synchronized void setChunkDownloaded(int index) {
        if (!chunkDownloaded[index]) {
            chunkDownloaded[index] = true;
            downloadedCount.incrementAndGet();
        }
    }

    public boolean allChunksDownloaded() {
        return downloadedCount.get() == totalChunks;
    }
}
