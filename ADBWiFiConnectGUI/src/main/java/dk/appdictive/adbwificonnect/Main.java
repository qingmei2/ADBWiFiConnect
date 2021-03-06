package dk.appdictive.adbwificonnect;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Callback;
import org.apache.log4j.Logger;

import java.net.URL;
import java.util.*;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

public class Main extends Application implements Initializable {

    private static final String PREF_JAR_LOCATION = "JAR_LOCATION", PREF_ADB_LOCATION = "ADB_LOCATION", PREF_SAVED_CONNECTIONS = "SAVED_CONNECTIONS", PREFS_ID = "dk/appdictive/adbconnect";

    @FXML
    private TextArea outputTextArea;
    @FXML
    private CheckMenuItem menuItemDebug;
    @FXML
    private ListView listView;
    @FXML
    private ListView listViewSaved;
    ObservableList<Device> observableList = FXCollections.observableArrayList();
    ObservableList<Device> observableListSavedConnections = FXCollections.observableArrayList();
    private boolean isWindowActive = true;
    private Preferences prefs;
    private Logger log = Logger.getLogger(Main.class.getName());
    private OnScreenConsoleOutputDelegate onScreenConsoleOutputDelegate;
    public static String adbPath;
    private String lastADBDevicesOutput;

    @Override
    public void start(Stage primaryStage) throws Exception {
        URL resource = getClass().getResource("/main.fxml");
        FXMLLoader loader = new FXMLLoader(resource);
        loader.setController(this);
        Parent root = loader.load();

        primaryStage.getIcons().addAll(
                new Image(Main.class.getResourceAsStream("/icons/ic_adbremoteconnect.png")),
                new Image(Main.class.getResourceAsStream("/icons/ic_adbremoteconnect@2x.png")));
        primaryStage.setTitle("ADB WiFi Connect");
        Scene scene = new Scene(root, 700, 640);
        scene.getStylesheets().add("/stylesheet.css");
        primaryStage.iconifiedProperty().addListener(new ChangeListener<Boolean>() {
            @Override
            public void changed(ObservableValue<? extends Boolean> observable, Boolean oldValue, Boolean newValue) {
                if (newValue) {
                    // not showing...
                    isWindowActive = false;
                    log.debug("Window not showing");
                } else {
                    // showing ...
                    isWindowActive = true;
                    startDeviceListUpdateThread();
                    log.debug("Window showing");
                }
            }
        });
        primaryStage.setOnCloseRequest(new EventHandler<WindowEvent>() {
            @Override
            public void handle(WindowEvent event) {
                isWindowActive = false;
                Platform.exit();
                System.exit(0);
            }
        });
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private void setupLogAppender() {
        onScreenConsoleOutputDelegate = new OnScreenConsoleOutputDelegate(outputTextArea);
        Logger.getRootLogger().addAppender(onScreenConsoleOutputDelegate);
//        Logger.getRootLogger().addAppender(new AlertOnErrorsDelegate());
    }

    private void initializeSavedData() {
        // Retrieve the user preference node for the package
        Preferences systemRoot = Preferences.userRoot();
        prefs = systemRoot.node(PREFS_ID);

        String jarLocation = prefs.get(PREF_JAR_LOCATION, null);
        if (jarLocation != null) {
            log.info("For launching outside of the IDE, find the runnable jar file here: \n" + jarLocation + "\n");
        }

        adbPath = prefs.get(PREF_ADB_LOCATION, "adb");
        if (adbPath == null) adbPath = "adb";
        log.debug("ADB path: " + adbPath);

        String savedData = prefs.get(PREF_SAVED_CONNECTIONS, null);

        if (savedData != null) {
            Device[] deserializeArray = SerializeHelper.deserializeArray(savedData);
            if (deserializeArray != null) {
                observableListSavedConnections.addAll(new ArrayList<>(Arrays.asList(deserializeArray)));
            }

            if (observableListSavedConnections.size() == 0) {
                writeWelcome(false);
            } else {
                writeWelcome(true);
            }
        }
    }

    private void writeWelcome(boolean hasSavedConnections) {
        if (!hasSavedConnections) {
            log.info("Welcome to ADB WiFi Connect!\n\n" +
                    "Please plug in an Android device by USB cable, make sure it is on the same WiFi as your computer and then click CONNECT to establish a remote connection to the device. A new connection will show up on the list. Click SAVE on the remote connection to add it to the list of saved connections and next time a remote connection is needed, simply click CONNECT on the saved connection.\n" +
                    "\n" +
                    "Happy developing! :)");
        } else {
            log.info("Welcome back!\n\n" +
                    "Please click CONNECT on a saved connection to reconnect to that, or plug in a new device with USB to make a new remote connection.\n" +
                    "\n" +
                    "Happy developing! :)\n");
        }


    }

    //run from background thread
    private void updateListOfDevices(String[] adbDevicesListOutput) {
        ArrayList<Device> currentDevices = new ArrayList<>();
        for (String adbDeviceLine : adbDevicesListOutput) {
            if (adbDeviceLine.contains("List") || adbDeviceLine.contains("daemon") || adbDeviceLine.trim().equals("")) {
                //ignore line
            } else {
                //is a device line so check for either IP or get device adb ID
                Device currentDevice = new Device();
                if (adbDeviceLine.contains("offline")) {
                    currentDevice.setType(Device.DEVICE_TYPE_OFFLINE);
                    currentDevice.setName(adbDeviceLine.replace("offline", "").trim());
                } else {
                    String ipFromText = IPHelper.getIPFromText(adbDeviceLine);
                    if (ipFromText == null) {
                        currentDevice.setSerialID(adbDeviceLine.replace("device", "").trim());
                        currentDevice.setRemoteIP(ADBCommands.getDeviceIP(currentDevice));
                        currentDevice.setType(Device.DEVICE_TYPE_USB);
                    } else {
                        currentDevice.setRemoteIP(ipFromText);
                        currentDevice.setSerialID(ADBCommands.getDeviceSerialNo(currentDevice));
                        currentDevice.setType(Device.DEVICE_TYPE_REMOTE);
                    }
                    currentDevice.setName(ADBCommands.getDeviceName(currentDevice));
                }

                currentDevices.add(currentDevice);
            }
        }
        updateData(currentDevices);
    }

    private void startDeviceListUpdateThread() {
        Runnable r = new Runnable() {
            public void run() {
                log.debug("Running update of adb devices with window active: " + isWindowActive);
                String output = ADBCommands.runCommand(Main.adbPath + " devices");
                log.debug(output);
                if (!output.equals(lastADBDevicesOutput)) {
                    updateListOfDevices(output.split("\n"));
                    lastADBDevicesOutput = output;
                }

                try {
                    Thread.sleep(3000);
                    if (isWindowActive) {
                        new Thread(this).start();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    log.error(e.getMessage());
                }

            }
        };

        log.debug("Starting update thread for list of ADB devices");
        new Thread(r).start();
    }

    public void updateData(List<Device> devices) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                // Update UI here.
                observableList.clear();
                observableList.addAll(devices);
                //TODO update list of saved devices (in case there's an overlap)
                listViewSaved.refresh();
            }
        });
    }

    private void updateSavedConnectionsList() {
        //refresh list of current connections since list of saved connections have changed data
        listView.refresh();

        //saved the data to remember for future launches
        prefs.put(PREF_SAVED_CONNECTIONS, SerializeHelper.serializeArray(observableListSavedConnections.toArray(new Device[observableListSavedConnections.size()])));
        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            e.printStackTrace();
        }
    }

    public boolean hasRemoteIPSaved(String remoteIP) {
        for (Device savedDevice : observableListSavedConnections) {
            if (remoteIP.equals(savedDevice.getRemoteIP())) {
                return true;
            }
        }
        return false;
    }

    public boolean isCurrentlyConnectedToRemoteIP(String remoteIP) {
        for (Device currentConnectedDevice : observableList) {
            if (currentConnectedDevice.type == Device.DEVICE_TYPE_REMOTE && remoteIP.equals(currentConnectedDevice.getRemoteIP())) {
                return true;
            }
        }
        return false;
    }

    public void saveConnection(Device device) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                for (Device savedDevice : observableListSavedConnections) {
                    if (device.getRemoteIP().equals(savedDevice.getRemoteIP())) {
                        observableListSavedConnections.remove(savedDevice);
                        break;
                    }
                }

                Device newSavedConnection = new Device();
                newSavedConnection.setName(device.getName());
                newSavedConnection.setRemoteIP(device.getRemoteIP());
                newSavedConnection.setSerialID(device.getSerialID());
                newSavedConnection.setType(Device.DEVICE_TYPE_SAVED_REMOTE);
                observableListSavedConnections.add(0, newSavedConnection);

                updateSavedConnectionsList();
            }
        });
    }

    public void deleteConnection(Device device) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                for (Device savedDevice : observableListSavedConnections) {
                    if (device.getRemoteIP().equals(savedDevice.getRemoteIP())) {
                        observableListSavedConnections.remove(savedDevice);
                        updateSavedConnectionsList();
                        return;
                    }
                }
            }
        });
    }

    public void setupListView() {
        listView.setSelectionModel(new TableSelectionModel() {
            @Override
            public boolean isSelected(int row, TableColumnBase column) {
                return false;
            }

            @Override
            public void select(int row, TableColumnBase column) {

            }

            @Override
            public void clearAndSelect(int row, TableColumnBase column) {

            }

            @Override
            public void clearSelection(int row, TableColumnBase column) {

            }

            @Override
            public void selectLeftCell() {

            }

            @Override
            public void selectRightCell() {

            }

            @Override
            public void selectAboveCell() {

            }

            @Override
            public void selectBelowCell() {

            }

            @Override
            public void selectRange(int minRow, TableColumnBase minColumn, int maxRow, TableColumnBase maxColumn) {

            }

            @Override
            protected int getItemCount() {
                return 0;
            }

            @Override
            protected Object getModelItem(int index) {
                return null;
            }

            @Override
            protected void focus(int index) {

            }

            @Override
            protected int getFocusedIndex() {
                return 0;
            }
        });
        listView.setItems(observableList);
        listView.setCellFactory(new Callback<ListView<Device>, ListCell<Device>>() {
            @Override
            public ListCell<Device> call(ListView<Device> listView) {
                return new ListViewCell(Main.this);
            }
        });

        listViewSaved.setSelectionModel(new TableSelectionModel() {
            @Override
            public boolean isSelected(int row, TableColumnBase column) {
                return false;
            }

            @Override
            public void select(int row, TableColumnBase column) {

            }

            @Override
            public void clearAndSelect(int row, TableColumnBase column) {

            }

            @Override
            public void clearSelection(int row, TableColumnBase column) {

            }

            @Override
            public void selectLeftCell() {

            }

            @Override
            public void selectRightCell() {

            }

            @Override
            public void selectAboveCell() {

            }

            @Override
            public void selectBelowCell() {

            }

            @Override
            public void selectRange(int minRow, TableColumnBase minColumn, int maxRow, TableColumnBase maxColumn) {

            }

            @Override
            protected int getItemCount() {
                return 0;
            }

            @Override
            protected Object getModelItem(int index) {
                return null;
            }

            @Override
            protected void focus(int index) {

            }

            @Override
            protected int getFocusedIndex() {
                return 0;
            }
        });
        listViewSaved.setItems(observableListSavedConnections);
        listViewSaved.setCellFactory(new Callback<ListView<Device>, ListCell<Device>>() {
            @Override
            public ListCell<Device> call(ListView<Device> listView) {
                return new ListViewCell(Main.this);
            }
        });
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        assert listView != null : "fx:id=\"listView\" was not injected: check your FXML file.";
        setupListView();
        setupLogAppender();
        initializeSavedData();
        startDeviceListUpdateThread();

        menuItemDebug.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent actionEvent) {
                onScreenConsoleOutputDelegate.setShowDebug(menuItemDebug.isSelected());
            }
        });
    }


}
