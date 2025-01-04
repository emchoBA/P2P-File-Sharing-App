import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class BasicP2PGUI extends JFrame {

    private Thread serverThread;  // We'll store the thread that runs FileServer.main

    public BasicP2PGUI() {
        super("Basic P2P GUI");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(500, 200);
        setLocationRelativeTo(null);

        // ====== MENU BAR ======
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        JMenuItem connectItem = new JMenuItem("Connect");
        JMenuItem disconnectItem = new JMenuItem("Disconnect");

        fileMenu.add(connectItem);
        fileMenu.add(disconnectItem);
        menuBar.add(fileMenu);
        setJMenuBar(menuBar);

        // "Connect" => run FileServer.main in a separate thread
        connectItem.addActionListener(e -> startServer());

        // "Disconnect" => call stopServer
        disconnectItem.addActionListener(e -> stopServer());

        // ====== CENTER PANEL: search field and button ======
        JPanel panel = new JPanel(new FlowLayout());
        JLabel label = new JLabel("Enter file name:");
        JTextField fileField = new JTextField(20);
        JButton startButton = new JButton("Start");

        panel.add(label);
        panel.add(fileField);
        panel.add(startButton);

        // "Start" => use FileClient.search(...) with the typed name
        startButton.addActionListener(e -> {
            String fileName = fileField.getText().trim();
            if (!fileName.isEmpty()) {
                // call our new static method
                FileClient.search(fileName);
            } else {
                System.out.println("No file name entered.");
            }
        });

        getContentPane().add(panel, BorderLayout.CENTER);
    }

    // ================== SERVER CONTROL ==================
    private void startServer() {
        if (serverThread == null) {
            serverThread = new Thread(() -> {
                // We pass empty args to FileServer.main
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
        FileServer.stopServer(); // signals server to end
        serverThread = null;     // let it be GC'd
    }

    // ================== MAIN ==================
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            BasicP2PGUI gui = new BasicP2PGUI();
            gui.setVisible(true);
        });
    }
}
