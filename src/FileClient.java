import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

interface DownloadListener {
    void onDownloadProgress(String fileName, int percentage);
}
public class FileClient {

    private static String destinationFolder = "downloads"; // creates folder in project if left like this
    private static final int SERVER_PORT = 6789; // tcp
    private static final int BROADCAST_PORT = 9000; // udp
    private static final String BROADCAST_IP = "192.168.1.255"; // CHANGE THIS IN LAB
    private static DownloadListener downloadListener;

    public static void setDownloadListener(DownloadListener listener) {
        downloadListener = listener;
    }
    /**
     * Sunum sırasında port ve IP adreslerini kontrol
     * IP ADRESİ 412 LABINDA 10.2.6.255 !!!!!!!!!!!!!!!!!
     */

    //
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

    // search file in peers, if found try to download from all peers that have that
    // Yulearn üzerindeki kod
    public static void search(String fileName) {
        try {
            // find peers
            List<String> peerIPs = startPeerDiscovery(BROADCAST_IP, BROADCAST_PORT);
            if (peerIPs.isEmpty()) {
                System.out.println("No peers discovered. Exiting search.");
                return;
            }

            List<String> peersWithFile = new ArrayList<>();
            int fileLength = -1;

            // find peers that have that file
            for (String peerIP : peerIPs) {
                try (Socket socket = new Socket(peerIP, SERVER_PORT);
                     DataInputStream dIS = new DataInputStream(socket.getInputStream());
                     DataOutputStream dOS = new DataOutputStream(socket.getOutputStream())) {

                    int fileCount = dIS.readInt();
                    for (int i = 0; i < fileCount; i++) {
                        dIS.readUTF(); // skip listing, we only need if peer has the file
                    }

                    // request file
                    dOS.writeUTF(fileName);
                    int lengthFromThisPeer = dIS.readInt();

                    if (lengthFromThisPeer > 0) { // peer has the file
                        peersWithFile.add(peerIP); // add to list
                        if (fileLength < 0) {
                            fileLength = lengthFromThisPeer; // store from first peer for memory, same file length
                            // use this for percentage !!!!!!!!!!
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

            // write donloaded chunks
            try (RandomAccessFile rAF = new RandomAccessFile(outFile, "rw")) {
                rAF.setLength(fileLength);

                int chunkSize = 256000;
                int totalChunks = (int) Math.ceil(fileLength / (double) chunkSize);

                MultiSourceDownloader msDownloader = new MultiSourceDownloader(fileName, rAF, totalChunks);

                if(downloadListener != null){
                    msDownloader.setDownloadListener(downloadListener);
                }

                ExecutorService executor = Executors.newFixedThreadPool(peersWithFile.size()); // for parallel download, thread pooling
                List<Future<?>> futures = new ArrayList<>();

                for (String peerIP : peersWithFile) { // parallel download from peers with executorService
                    int finalFileLength = fileLength;
                    futures.add(executor.submit(() ->
                            downloadChunksFromPeer(peerIP, SERVER_PORT, msDownloader, finalFileLength)
                    ));
                }

                boolean allDone = false;
                while (!allDone) {
                    allDone = msDownloader.allChunksDownloaded() || futures.stream().allMatch(Future::isDone); // double control

                    if (!allDone) {
                        Thread.sleep(500);
                    }
                }

                executor.shutdownNow();

                if (msDownloader.allChunksDownloaded()) {
                    System.out.println(">>> Multi-source download complete for file: " + fileName);
                    if(downloadListener != null){
                        downloadListener.onDownloadProgress(fileName, 100);
                    }
                } else {
                    System.out.println(">>> Could not download all chunks from available peers.");
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // runs parallel for multiple peers

    /**
     * Yulearn üzerindeki örnek kod üzerinden ilerledim
     * run yerine fonksiyon olarak değiştirdim
     * msDownloader ile multi-source sağladım
     */
    private static void downloadChunksFromPeer(String peerIP, int port, MultiSourceDownloader msDownloader, int fileLength) {
        // fileLength for percentage -> eklemeye çalış güzel fikir
        try (Socket socket = new Socket(peerIP, port);
             DataInputStream dIS = new DataInputStream(socket.getInputStream());
             DataOutputStream dOS = new DataOutputStream(socket.getOutputStream())) {

            System.out.println("Connecting to peer: " + peerIP);

            int fileCount = dIS.readInt();
            for (int i = 0; i < fileCount; i++) {
                dIS.readUTF();
            }

            // request for specific file
            dOS.writeUTF(msDownloader.getFileName());
            int length = dIS.readInt();
            if (length <= 0) {
                System.out.println("Peer " + peerIP + " -> does NOT have file " + msDownloader.getFileName());
                return;
            }

            RandomAccessFile rAF = msDownloader.getRandomAccessFile();
            int chunkSize = 256000; // 256 kb
            int chunksReceivedHere = 0;

            // keeps downloading until all downloaded
            // if signal is lost, breaks (broken pipe exception sebebi bu)
            while (!msDownloader.allChunksDownloaded()) {
                int chunkIndex = dIS.readInt();
                if (chunkIndex == -1) {
                    break; // no more chunks from tihs peer
                }

                int readBytes = dIS.readInt();
                byte[] buffer = new byte[readBytes];
                dIS.readFully(buffer);

                // write to file if not already downloaded
                if (!msDownloader.isChunkDownloaded(chunkIndex)) { // check array
                    synchronized (rAF) { // only run this, dont run other threads (synchronized işlevi bu)
                        rAF.seek((long)chunkIndex * chunkSize);
                        rAF.write(buffer, 0, readBytes);
                    }
                    msDownloader.setChunkDownloaded(chunkIndex);
                    chunksReceivedHere++;
                }

                dOS.writeInt(chunkIndex);
                dOS.flush();

                // if all downloıded
                if (msDownloader.allChunksDownloaded()) {
                    break;
                }
            }

            System.out.println("Peer " + peerIP + " -> retrieved " + chunksReceivedHere + " new chunk(s).");

        } catch (IOException e) {
            System.out.println("Peer " + peerIP + " -> error: " + e.getMessage());
        }
    }

    // UDP broadcast to find peers
    // send PING receive PONG -> no PONG no peer (added this to avoid other UDP packets, there was problems at lab)
    private static List<String> startPeerDiscovery(String broadcastAddress, int targetPort) {
        List<String> peerIPs = new ArrayList<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            InetAddress broadcastInet = InetAddress.getByName(broadcastAddress);
            String broadcastMsg = "PING from peer!";
            byte[] sendData = broadcastMsg.getBytes();

            // broadcast limitation for not overwhelming network (10 packets)
            for (int i = 0; i < 10; i++) {
                DatagramPacket packet = new DatagramPacket(sendData, sendData.length, broadcastInet, targetPort);
                socket.send(packet);
                System.out.println("Broadcast packet sent: " + (i + 1));
                Thread.sleep(500);
            }

            // wait 5s for response
            socket.setSoTimeout(5000);
            byte[] buffer = new byte[1024];

            // receive PONG
            while (true) {
                try {
                    DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                    socket.receive(responsePacket);
                    String msg = new String(responsePacket.getData(), 0, responsePacket.getLength());
                    if (msg.startsWith("PONG")) { // received from server
                        String peerIP = responsePacket.getAddress().getHostAddress();
                        System.out.println("Discovered peer: " + peerIP);
                        if (!peerIPs.contains(peerIP)) { // dont add if exist
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
    private final AtomicInteger downloadedCount = new AtomicInteger(0); // manage different peers
    private  DownloadListener downloadListener;

    public void setDownloadListener(DownloadListener listener) {
        this.downloadListener = listener;
    }
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
            downloadedCount.incrementAndGet(); // set upon all
            if(downloadListener != null){
                int percentage = (int) (((double) downloadedCount.get() / totalChunks) * 100);
                downloadListener.onDownloadProgress(fileName, percentage);
            }
        }
    }

    public boolean allChunksDownloaded() {
        return downloadedCount.get() == totalChunks;
    }
}
