import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class FileClient {

    private static String destinationFolder = "downloads";
    private static final String TARGET_FILE_NAME = "3b.png";  // Hard-coded file name
    private static final int SERVER_PORT = 6789;              // The TCP port your FileServer listens on
    private static final int BROADCAST_PORT = 9000;           // The UDP port for peer discovery

    public static void main(String[] args) {
        // 1) Discover all peers via UDP broadcast
        List<String> peerIPs = startPeerDiscovery("192.168.1.255", BROADCAST_PORT);

        if (peerIPs.isEmpty()) {
            System.out.println("No peers discovered. Exiting.");
            return;
        }

        // 2) Check which peers have the hard-coded file
        List<String> peersWithFile = new ArrayList<>();
        int fileLength = -1; // We'll store the file length from the first peer that has it

        for (String peerIP : peerIPs) {
            try (Socket socket = new Socket(peerIP, SERVER_PORT);
                 DataInputStream dIS = new DataInputStream(socket.getInputStream());
                 DataOutputStream dOS = new DataOutputStream(socket.getOutputStream())) {

                // Server sends number of files
                int fileCount = dIS.readInt();
                // Skip reading all file names or read them if you want
                for (int i = 0; i < fileCount; i++) {
                    dIS.readUTF();
                }

                // Ask for TARGET_FILE_NAME
                dOS.writeUTF(TARGET_FILE_NAME);
                int lengthFromThisPeer = dIS.readInt();

                if (lengthFromThisPeer > 0) {
                    // This peer has the file
                    peersWithFile.add(peerIP);

                    // If we haven't saved the file length yet, do so now
                    if (fileLength < 0) {
                        fileLength = lengthFromThisPeer;
                    }
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        System.out.println("Number of peers that have \"" + TARGET_FILE_NAME + "\": " + peersWithFile.size());
        if (peersWithFile.isEmpty()) {
            System.out.println("No peers have the file. Exiting.");
            return;
        }

        // 3) Create the destination folder if it doesn't exist
        File downloadFolderFile = new File(destinationFolder);
        if (!downloadFolderFile.exists()) {
            downloadFolderFile.mkdirs();
        }

        // 4) Prepare to download the file
        File outFile = new File(downloadFolderFile, TARGET_FILE_NAME);
        try (RandomAccessFile rAF = new RandomAccessFile(outFile, "rw")) {
            rAF.setLength(fileLength);

            int chunkSize = 256000;
            int totalChunks = (int) Math.ceil(fileLength / (double) chunkSize);

            // Create the shared downloader object
            MultiSourceDownloader msDownloader = new MultiSourceDownloader(TARGET_FILE_NAME, rAF, totalChunks);

            // 5) Use an ExecutorService for parallel downloads
            ExecutorService executor = Executors.newFixedThreadPool(peersWithFile.size());
            List<Future<?>> futures = new ArrayList<>();

            for (String peerIP : peersWithFile) {
                int finalFileLength = fileLength;
                futures.add(executor.submit(() ->
                        downloadChunksFromPeer(peerIP, SERVER_PORT, msDownloader, finalFileLength)
                ));
            }

            // Wait until all chunks are downloaded or all threads have finished
            boolean allDone = false;
            while (!allDone) {
                allDone = msDownloader.allChunksDownloaded() ||
                        futures.stream().allMatch(Future::isDone);

                if (!allDone) {
                    Thread.sleep(500);
                }
            }

            executor.shutdownNow();

            if (msDownloader.allChunksDownloaded()) {
                System.out.println(">>> Multi-source download complete for file: " + TARGET_FILE_NAME);
            } else {
                System.out.println(">>> Could not download all chunks from available peers.");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Connects to a single peer and retrieves random chunks of the file.
     * Only writes chunks that haven't been downloaded yet.
     */
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

            // The server will send fileCount, we must read it:
            int fileCount = dIS.readInt();
            for (int i = 0; i < fileCount; i++) {
                dIS.readUTF();
            }

            // Request the same file
            dOS.writeUTF(msDownloader.getFileName());
            int length = dIS.readInt();
            if (length <= 0) {
                System.out.println("Peer " + peerIP + " -> does NOT actually have file " + msDownloader.getFileName());
                return;
            }

            // Now read chunks until the server sends -1 or we have all chunks
            RandomAccessFile rAF = msDownloader.getRandomAccessFile();
            int chunkSize = 256000;
            int chunksReceivedHere = 0;

            while (!msDownloader.allChunksDownloaded()) {
                int chunkIndex = dIS.readInt();
                if (chunkIndex == -1) {
                    // server signals no more chunks
                    break;
                }

                int readBytes = dIS.readInt();
                byte[] buffer = new byte[readBytes];
                dIS.readFully(buffer);

                // If we haven't downloaded this chunk yet, write it
                if (!msDownloader.isChunkDownloaded(chunkIndex)) {
                    synchronized (rAF) {
                        rAF.seek((long)chunkIndex * chunkSize);
                        rAF.write(buffer, 0, readBytes);
                    }
                    msDownloader.setChunkDownloaded(chunkIndex);
                    chunksReceivedHere++;
                }

                // Acknowledge the chunk index
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

    /**
     * Broadcast PING on UDP to discover peers, wait for PONGs.
     */
    private static List<String> startPeerDiscovery(String broadcastAddress, int targetPort) {
        List<String> peerIPs = new ArrayList<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            InetAddress broadcastInet = InetAddress.getByName(broadcastAddress);
            String broadcastMsg = "PING from peer!";
            byte[] sendData = broadcastMsg.getBytes();

            // Send broadcast packets
            for (int i = 0; i < 10; i++) {
                DatagramPacket packet = new DatagramPacket(sendData, sendData.length, broadcastInet, targetPort);
                socket.send(packet);
                System.out.println("Broadcast packet sent: " + (i + 1));
                Thread.sleep(500);
            }

            // Listen for responses
            socket.setSoTimeout(5000); // 5 seconds
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
                    break; // stop listening
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return peerIPs;
    }
}

/**
 * Helper class to track which chunks of the file are downloaded,
 * and allow multiple threads to share the same RandomAccessFile safely.
 */
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
