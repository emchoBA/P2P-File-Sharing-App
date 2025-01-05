import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BasicP2PGUI extends JFrame {

    private Thread serverThread;
    private JList<String> foundFilesList;
    private DefaultListModel<String> foundFilesModel;

    // Exclusion Models
    private DefaultListModel<String> excludedFoldersModel = new DefaultListModel<>();
    private DefaultListModel<String> excludedMasksModel = new DefaultListModel<>();

    // Store all found files for local searching
    private List<String> allFoundFiles = new ArrayList<>();

    public BasicP2PGUI() {
        super("Basic P2P GUI");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(700, 500);
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
            refreshFoundFiles();
        });

        disconnectItem.addActionListener(e -> {
            stopServer();
        });

        // ===== MAIN PANEL =====
        JPanel mainPanel = new JPanel(new BorderLayout());
        getContentPane().add(mainPanel);

        // top row: "Enter file name or keyword:" + "Search"
        JPanel topPanel = new JPanel(new FlowLayout());
        JLabel label = new JLabel("Enter file name or keyword:");
        JTextField fileField = new JTextField(20);
        JButton startButton = new JButton("Search");
        topPanel.add(label);
        topPanel.add(fileField);
        topPanel.add(startButton);

        mainPanel.add(topPanel, BorderLayout.NORTH);

        // ========== FOUND FILES PANEL ==========
        JPanel foundPanel = new JPanel(new BorderLayout());
        foundPanel.setBorder(BorderFactory.createTitledBorder("Found Files"));

        foundFilesModel = new DefaultListModel<>();
        foundFilesList = new JList<>(foundFilesModel);
        foundFilesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Double-click to download
        foundFilesList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int index = foundFilesList.locationToIndex(e.getPoint());
                    if (index >= 0) {
                        String selected = foundFilesModel.getElementAt(index);
                        if (!selected.endsWith("/")) {
                            FileClient.search(selected);
                        } else {
                            System.out.println("Folders are not directly downloadable: " + selected);
                        }
                    }
                }
            }
        });

        JScrollPane foundScrollPane = new JScrollPane(foundFilesList);
        foundPanel.add(foundScrollPane, BorderLayout.CENTER);

        mainPanel.add(foundPanel, BorderLayout.CENTER);

        // ========== EXCLUSIONS PANEL ==========
        JPanel exclusionsPanel = new JPanel(new GridLayout(1, 2, 5, 5));
        exclusionsPanel.setBorder(BorderFactory.createTitledBorder("Exclusions"));

        // (A) Excluded Folders
        JPanel folderExclPanel = new JPanel(new BorderLayout());
        folderExclPanel.setBorder(BorderFactory.createTitledBorder("Exclude Folders"));
        JList<String> folderExclList = new JList<>(excludedFoldersModel);
        folderExclPanel.add(new JScrollPane(folderExclList), BorderLayout.CENTER);
        JPanel folderExclBtnPanel = new JPanel();
        JButton addFolderExclBtn = new JButton("Add");
        JButton delFolderExclBtn = new JButton("Del");
        folderExclBtnPanel.add(addFolderExclBtn);
        folderExclBtnPanel.add(delFolderExclBtn);
        folderExclPanel.add(folderExclBtnPanel, BorderLayout.SOUTH);

        // (B) Excluded Masks
        JPanel maskExclPanel = new JPanel(new BorderLayout());
        maskExclPanel.setBorder(BorderFactory.createTitledBorder("Exclude File Masks"));
        JList<String> maskExclList = new JList<>(excludedMasksModel);
        maskExclPanel.add(new JScrollPane(maskExclList), BorderLayout.CENTER);
        JPanel maskExclBtnPanel = new JPanel();
        JButton addMaskExclBtn = new JButton("Add");
        JButton delMaskExclBtn = new JButton("Del");
        maskExclBtnPanel.add(addMaskExclBtn);
        maskExclBtnPanel.add(delMaskExclBtn);
        maskExclPanel.add(maskExclBtnPanel, BorderLayout.SOUTH);

        exclusionsPanel.add(folderExclPanel);
        exclusionsPanel.add(maskExclPanel);

        mainPanel.add(exclusionsPanel, BorderLayout.SOUTH);

        // ========== Exclusion Buttons Logic ==========
        addFolderExclBtn.addActionListener(e -> {
            String folderName = JOptionPane.showInputDialog(this, "Folder name to exclude?");
            if (folderName != null && !folderName.trim().isEmpty()) {
                excludedFoldersModel.addElement(folderName.trim());
                updateServerExclusions();
                refreshFoundFiles();
            }
        });
        delFolderExclBtn.addActionListener(e -> {
            int idx = folderExclList.getSelectedIndex();
            if (idx >= 0) {
                excludedFoldersModel.remove(idx);
                updateServerExclusions();
                refreshFoundFiles();
            }
        });

        addMaskExclBtn.addActionListener(e -> {
            String mask = JOptionPane.showInputDialog(this, "File mask to exclude? (e.g. *.exe)");
            if (mask != null && !mask.trim().isEmpty()) {
                excludedMasksModel.addElement(mask.trim());
                updateServerExclusions();
                refreshFoundFiles();
            }
        });
        delMaskExclBtn.addActionListener(e -> {
            int idx = maskExclList.getSelectedIndex();
            if (idx >= 0) {
                excludedMasksModel.remove(idx);
                updateServerExclusions();
                refreshFoundFiles();
            }
        });

        // ========== SEARCH BUTTON ==========
        startButton.addActionListener(e -> {
            String searchText = fileField.getText().trim();
            if (searchText.isEmpty()) {
                // if no search text, just show all
                displayAllFiles();
            } else {
                searchAndDisplay(searchText);
            }
        });
    }

    // Refresh found files from peers, store in allFoundFiles, then display them
    private void refreshFoundFiles() {
        foundFilesModel.clear();
        allFoundFiles.clear();

        Set<String> fromPeers = FileClient.listAllFilesFromPeers();
        allFoundFiles.addAll(fromPeers);

        for (String item : allFoundFiles) {
            foundFilesModel.addElement(item);
        }
    }

    // Show all found files without filtering
    private void displayAllFiles() {
        foundFilesModel.clear();
        for (String item : allFoundFiles) {
            foundFilesModel.addElement(item);
        }
    }

    // Filter from the existing allFoundFiles
    private void searchAndDisplay(String query) {
        foundFilesModel.clear();
        for (String item : allFoundFiles) {
            if (item.toLowerCase().contains(query.toLowerCase())) {
                foundFilesModel.addElement(item);
            }
        }
        if (foundFilesModel.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No results found.", "Search", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    // Send updated exclusion sets to the server
    private void updateServerExclusions() {
        Set<String> folderSet = new HashSet<>();
        for (int i = 0; i < excludedFoldersModel.size(); i++) {
            folderSet.add(excludedFoldersModel.getElementAt(i));
        }

        Set<String> maskSet = new HashSet<>();
        for (int i = 0; i < excludedMasksModel.size(); i++) {
            maskSet.add(excludedMasksModel.getElementAt(i));
        }

        FileServer.setExclusions(folderSet, maskSet);
    }

    private void startServer() {
        if (serverThread == null) {
            serverThread = new Thread(() -> FileServer.main(new String[0]), "ServerMainThread");
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
