import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GUI extends JFrame {

    private Thread serverThread;
    private JList<String> foundFilesList;
    private DefaultListModel<String> foundFilesModel;
    private DefaultListModel<String> excludedFoldersModel = new DefaultListModel<>();
    private DefaultListModel<String> excludedMasksModel = new DefaultListModel<>();
    private List<String> allFoundFiles = new ArrayList<>(); // local listing for search

    public GUI() {
        super("P2P");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(700, 500);
        setLocationRelativeTo(null);


        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        JMenu helpMenu = new JMenu("Help");
        JMenuItem connectItem = new JMenuItem("Connect");
        JMenuItem disconnectItem = new JMenuItem("Disconnect");
        JMenuItem exitItem = new JMenuItem("Exit");
        JMenuItem aboutItem = new JMenuItem("About");

        fileMenu.add(connectItem);
        fileMenu.add(disconnectItem);
        fileMenu.add(exitItem);
        helpMenu.add(aboutItem);
        menuBar.add(fileMenu);
        menuBar.add(helpMenu);
        setJMenuBar(menuBar);

        aboutItem.addActionListener(e -> {
            JOptionPane.showMessageDialog(this, "Emir Emri \n" +
                    "20210702029", "About", JOptionPane.INFORMATION_MESSAGE);
        });

        exitItem.addActionListener(e -> {
            stopServer();
            System.exit(0);
        });

        connectItem.addActionListener(e -> {
            startServer();
            refreshFoundFiles();
            JOptionPane.showMessageDialog(this, "Connected", "Info", JOptionPane.INFORMATION_MESSAGE);
        });

        disconnectItem.addActionListener(e -> {
            stopServer();
            JOptionPane.showMessageDialog(this, "Disconnected", "Info", JOptionPane.INFORMATION_MESSAGE);
            clearTextFields();
        });


        JPanel mainPanel = new JPanel(new BorderLayout());
        getContentPane().add(mainPanel);


        JPanel topPanel = new JPanel(new FlowLayout());
        JLabel label = new JLabel("Enter file name or keyword:");
        JTextField fileField = new JTextField(20);
        JButton startButton = new JButton("Search");
        topPanel.add(label);
        topPanel.add(fileField);
        topPanel.add(startButton);

        //mainPanel.add(topPanel, BorderLayout.NORTH);


        JLabel sharedFolderLabel = new JLabel("Root of the P2P Shared Folder:");
        JTextField sharedFolderField = new JTextField(30);
        sharedFolderField.setText(FileServer.getSharedFolder()); //default
        JButton setSharedFolderButton = new JButton("Set");
        setSharedFolderButton.addActionListener(e -> {
            String path = sharedFolderField.getText().trim();
            if (!path.isEmpty()) {
                FileServer.setSharedFolder(path);
            } else {
                JOptionPane.showMessageDialog(this, "Invalid shared folder path.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });


        JLabel downloadFolderLabel = new JLabel("Destination Folder:");
        JTextField downloadFolderField = new JTextField(30);
        downloadFolderField.setText(FileClient.getDownloadFolder());
        JButton setDownloadFolderButton = new JButton("Set");
        setDownloadFolderButton.addActionListener(e -> {
            String path = downloadFolderField.getText().trim();
            if (!path.isEmpty()) {
                FileClient.setDownloadFolder(path);
            } else {
                JOptionPane.showMessageDialog(this, "Invalid download folder path.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        /**
        JPanel folderPathsPanel = new JPanel(new FlowLayout());
        folderPathsPanel.add(sharedFolderLabel);
        folderPathsPanel.add(sharedFolderField);
        folderPathsPanel.add(setSharedFolderButton);
        folderPathsPanel.add(downloadFolderLabel);
        folderPathsPanel.add(downloadFolderField);
        folderPathsPanel.add(setDownloadFolderButton);
        */
        JPanel RootFolderPanel = new JPanel(new FlowLayout());
        RootFolderPanel.add(sharedFolderLabel);
        RootFolderPanel.add(sharedFolderField);
        RootFolderPanel.add(setSharedFolderButton);

        JPanel DownloadFolderPanel = new JPanel(new FlowLayout());
        DownloadFolderPanel.add(downloadFolderLabel);
        DownloadFolderPanel.add(downloadFolderField);
        DownloadFolderPanel.add(setDownloadFolderButton);

        JPanel FolderandSeachPanel = new JPanel();
        FolderandSeachPanel.setLayout(new BoxLayout(FolderandSeachPanel, BoxLayout.Y_AXIS));

        FolderandSeachPanel.add(topPanel);
        FolderandSeachPanel.add(RootFolderPanel);
        FolderandSeachPanel.add(DownloadFolderPanel);

        mainPanel.add(FolderandSeachPanel, BorderLayout.NORTH);



        JPanel foundPanel = new JPanel(new BorderLayout());
        foundPanel.setBorder(BorderFactory.createTitledBorder("Found Files"));

        foundFilesModel = new DefaultListModel<>();
        foundFilesList = new JList<>(foundFilesModel);
        foundFilesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // 2 click download
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

        // exclusion
        JPanel exclusionsPanel = new JPanel(new GridLayout(1, 2, 5, 5));
        exclusionsPanel.setBorder(BorderFactory.createTitledBorder("Exclusions"));

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

        startButton.addActionListener(e -> { // search button
            String searchText = fileField.getText().trim();
            if (searchText.isEmpty()) {
                // if no search text, just show all
                displayAllFiles();
            } else {
                searchAndDisplay(searchText);
            }
        });
    }

    //refresh found files from peers and store in allFoundFiles and display them
    private void refreshFoundFiles() {
        foundFilesModel.clear();
        allFoundFiles.clear();

        Set<String> fromPeers = FileClient.listAllFilesFromPeers();
        allFoundFiles.addAll(fromPeers);

        for (String item : allFoundFiles) {
            foundFilesModel.addElement(item);
        }
    }

    private void displayAllFiles() {
        foundFilesModel.clear();
        for (String item : allFoundFiles) {
            foundFilesModel.addElement(item);
        }
    }

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

    private void clearTextFields() {
        foundFilesModel.clear();
        excludedFoldersModel.clear();
        excludedMasksModel.clear();
    }



    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            GUI gui = new GUI();
            gui.setVisible(true);
        });
    }
}
