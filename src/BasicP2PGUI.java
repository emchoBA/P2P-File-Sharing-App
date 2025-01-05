import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Set;

public class BasicP2PGUI extends JFrame {

    private Thread serverThread;
    private JList<String> foundFilesList;
    private DefaultListModel<String> foundFilesModel;

    public BasicP2PGUI() {
        super("Basic P2P GUI");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(500, 400);
        setLocationRelativeTo(null);

        // ===== MENU BAR =====
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        JMenuItem connectItem = new JMenuItem("Connect");
        JMenuItem disconnectItem = new JMenuItem("Disconnect");

        fileMenu.add(connectItem);
        fileMenu.add(disconnectItem);
        menuBar.add(fileMenu);
        setJMenuBar(menuBar);

        connectItem.addActionListener(e -> {
            startServer();
            // also refresh found files after connecting
            refreshFoundFiles();
        });

        disconnectItem.addActionListener(e -> {
            stopServer();
        });

        // ===== MAIN PANEL =====
        JPanel mainPanel = new JPanel(new BorderLayout());

        // top row: "Enter file name" and Start button
        JPanel topPanel = new JPanel(new FlowLayout());
        JLabel label = new JLabel("Enter file name:");
        JTextField fileField = new JTextField(20);
        JButton startButton = new JButton("Search");
        topPanel.add(label);
        topPanel.add(fileField);
        topPanel.add(startButton);

        // The Search button is now "non-functional" for download. We'll keep code:
        startButton.addActionListener(e -> {
            String fileName = fileField.getText().trim();
            if (!fileName.isEmpty()) {
                System.out.println("Search button clicked. Not triggering a direct download.");
                // If you wanted to still use it, you could do: FileClient.search(fileName);
                // but the requirement says "make the Search button non-functional" for now.
            }
        });

        mainPanel.add(topPanel, BorderLayout.NORTH);

        // ====== FOUND FILES PANEL ======
        JPanel foundPanel = new JPanel(new BorderLayout());
        foundPanel.setBorder(BorderFactory.createTitledBorder("Found Files"));

        foundFilesModel = new DefaultListModel<>();
        foundFilesList = new JList<>(foundFilesModel);
        foundFilesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Add double-click listener
        foundFilesList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int index = foundFilesList.locationToIndex(e.getPoint());
                    if (index >= 0) {
                        String selected = foundFilesModel.getElementAt(index);
                        if (!selected.endsWith("/")) {
                            // double-click on file -> download
                            FileClient.search(selected);
                        } else {
                            // it's a folder -> do nothing
                            System.out.println("Folders are not downloadable: " + selected);
                        }
                    }
                }
            }
        });

        JScrollPane foundScrollPane = new JScrollPane(foundFilesList);
        foundPanel.add(foundScrollPane, BorderLayout.CENTER);

        mainPanel.add(foundPanel, BorderLayout.CENTER);

        getContentPane().add(mainPanel);
    }

    // refresh the Found Files by listing from all peers
    private void refreshFoundFiles() {
        foundFilesModel.clear();
        Set<String> allFiles = FileClient.listAllFilesFromPeers();
        for (String f : allFiles) {
            foundFilesModel.addElement(f);
        }
    }

    private void startServer() {
        if (serverThread == null) {
            serverThread = new Thread(() -> {
                FileServer.main(new String[0]);
            }, "ServerMainThread");
            serverThread.start();
            System.out.println("Server started (thread).");
        } else {
            System.out.println("Server is already running.");
        }
    }

    private void stopServer() {
        System.out.println("Stopping server...");
        FileServer.stopServer();
        serverThread = null;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            BasicP2PGUI gui = new BasicP2PGUI();
            gui.setVisible(true);
        });
    }
}
