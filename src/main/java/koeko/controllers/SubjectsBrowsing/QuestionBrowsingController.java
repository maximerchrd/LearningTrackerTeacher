package koeko.controllers.SubjectsBrowsing;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Callback;
import koeko.Koeko;
import koeko.controllers.GenericPopUpController;
import koeko.controllers.controllers_tools.ControllerUtils;
import koeko.database_management.*;
import koeko.view.Subject;

import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.ResourceBundle;
import java.util.Vector;

/**
 * Created by maximerichard on 13.03.18.
 */
public class QuestionBrowsingController extends Window implements Initializable {
    static public TreeItem<Subject> rootSubjectSingleton;
    public ArrayList<String> ipAddresses;
    private Subject draggedSubject;
    private TreeItem<Subject> draggedItem;

    private Vector<Subject> subjects;
    private Vector<String> subjectsIds;
    private Vector<String> parentIds;
    private Vector<String> childIds;

    @FXML private Label labelIP;
    @FXML private TreeView<Subject> subjectsTree;
    @FXML public Accordion browseSubjectsAccordion;

    private ResourceBundle bundle;

    public void initialize(URL location, ResourceBundle resources) {
        Koeko.questionBrowsingControllerSingleton = this;
        bundle = resources;
        ipAddresses = new ArrayList<>();

        Task<Void> getIPTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                getAndDisplayIpAddress();

                return null;
            }
        };
        new Thread(getIPTask).start();


        //build the subjects tree
        subjectsTree.getStylesheets().add("/style/treeview.css");

        //create rootSubjectSingleton
        Subject subject = new Subject();
        subject.set_subjectName(bundle.getString("string.all_subjects"));
        rootSubjectSingleton = new TreeItem<>(subject);
        rootSubjectSingleton.setExpanded(true);
        subjectsTree.setShowRoot(true);
        Task<Void> loadQuestions = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                populateSubjectTree(rootSubjectSingleton);
                return null;
            }
        };
        new Thread(loadQuestions).start();
        subjectsTree.setRoot(rootSubjectSingleton);
        subjectsTree.setCellFactory(new Callback<TreeView<Subject>, TreeCell<Subject>>() {
            @Override
            public TreeCell<Subject> call(TreeView<Subject> stringTreeView) {
                TreeCell<Subject> treeCell = new TreeCell<Subject>() {
                    @Override
                    protected void updateItem(Subject item, boolean empty) {
                        super.updateItem(item, empty);
                        if (!empty && item != null) {
                            setText(item.get_subjectName());
                        } else {
                            setText(null);
                            setGraphic(null);
                        }
                    }
                };

                treeCell.setOnDragDetected(new EventHandler<MouseEvent>() {
                    @Override
                    public void handle(MouseEvent mouseEvent) {
                            draggedSubject = treeCell.getTreeItem().getValue();
                            draggedItem = treeCell.getTreeItem();
                            Dragboard db = subjectsTree.startDragAndDrop(TransferMode.ANY);

                            /* Put a string on a dragboard */
                            ClipboardContent content = new ClipboardContent();
                            content.putString(treeCell.getText());
                            db.setContent(content);

                            mouseEvent.consume();
                    }
                });

                treeCell.setOnDragOver(new EventHandler<DragEvent>() {
                    public void handle(DragEvent event) {
                        /* data is dragged over the target */
                        /* accept it only if it is not dragged from the same node
                         * and if it has a string data */
                        if (event.getGestureSource() != treeCell &&
                                event.getDragboard().hasString()) {
                            //set the type of dropping: MOVE AND/OR COPY
                            event.acceptTransferModes(TransferMode.MOVE);
                        }

                        event.consume();
                    }
                });

                treeCell.setOnDragEntered(new EventHandler<DragEvent>() {
                    public void handle(DragEvent event) {
                        /* the drag-and-drop gesture entered the target */
                        /* show to the user that it is an actual gesture target */
                        if (event.getGestureSource() != treeCell &&
                                event.getDragboard().hasString()) {
                            //treeCell.setStyle(String.format("-fx-background-color: green"));
                            treeCell.setTextFill(Color.LIGHTGREEN);
                        }
                        event.consume();
                    }
                });
                treeCell.setOnDragExited(new EventHandler<DragEvent>() {
                    public void handle(DragEvent event) {
                        /* mouse moved away, remove the graphical cues */
                        //treeCell.setStyle(String.format("-fx-background-color: white"));
                        treeCell.setTextFill(Color.BLACK);
                        event.consume();
                    }
                });


                treeCell.setOnDragDropped(new EventHandler<DragEvent>() {
                    public void handle(DragEvent event) {
                        /* data dropped */
                        if (!treeCell.getTreeItem().getValue().get_subjectName().contentEquals(draggedSubject.get_subjectName())) {
                            DbTableRelationSubjectSubject.addRelationSubjectSubject(treeCell.getTreeItem().getValue().get_subjectName(),draggedItem.getValue().get_subjectName(),
                                    draggedItem.getParent().getValue().get_subjectName());
                            draggedItem.getParent().getChildren().remove(draggedItem);
                            treeCell.getTreeItem().getChildren().add(draggedItem);
                        } else {
                            System.out.println("Trying to drag on self");
                        }
                        draggedSubject = null;
                    }
                });


                return treeCell;
            }
        });

        subjectsTree.setOnMouseClicked(new EventHandler<MouseEvent>() {
            @Override
            public void handle(MouseEvent mouseEvent) {
                if (mouseEvent.getClickCount() == 2) {
                    //filter with subject
                }
            }
        });

    }

    private void getAndDisplayIpAddress() throws SocketException, UnknownHostException {
        ipAddresses.removeAll(ipAddresses);
        ArrayList<String> potentialIpAddresses = new ArrayList<>();
        Enumeration e = NetworkInterface.getNetworkInterfaces();
        while(e.hasMoreElements())
        {
            NetworkInterface n = (NetworkInterface) e.nextElement();
            Enumeration ee = n.getInetAddresses();
            while (ee.hasMoreElements())
            {
                InetAddress i = (InetAddress) ee.nextElement();
                if (n.getName().contains("wlan") && !i.getHostAddress().contains(":")) {
                    ipAddresses.add(i.getHostAddress());
                } else if (!i.getHostAddress().contains(":") && i.getHostAddress().contains("192.168.")) {
                    potentialIpAddresses.add(i.getHostAddress());
                }
            }
        }
        if (ipAddresses.size() == 0) {
            if (potentialIpAddresses.size() == 1) {
                ipAddresses.addAll(potentialIpAddresses);
            } else {
                ipAddresses.add(InetAddress.getLocalHost().getHostAddress());
            }
        }
        if (ipAddresses.size() == 1) {
            Platform.runLater(() -> labelIP.setText("students should connect \nto the following address:\n" + ipAddresses.get(0)));
            if (Koeko.recordLogs) {
                DbTableLogs.insertLog("IPs", ipAddresses.get(0));
            }
        } else if (ipAddresses.size() == 2) {
            Platform.runLater(() -> labelIP.setText("students should connect \nto the following addresses:\n" + ipAddresses.get(0) +
                "\nand " + ipAddresses.get(1)));
            if (Koeko.recordLogs) {
                DbTableLogs.insertLog("IPs", ipAddresses.get(0) + "/" + ipAddresses.get(1));
            }
        }
    }

    private void populateSubjectTree(TreeItem<Subject> root) {
        //BEGIN retrieve data from the db and prepare the vectors
        subjects = DbTableSubject.getAllSubjects();
        subjectsIds = new Vector<>();
        for (Subject subject : subjects) {
            String subjectID = "";
            if (subject.get_subjectMUID() != null && !subject.get_subjectMUID().contentEquals("")) {
                subjectID = subject.get_subjectMUID();
            } else {
                subjectID = String.valueOf(subject.get_subjectId());
            }
            subjectsIds.add(String.valueOf(subjectID));
        }

        Vector<Vector<String>> idsRelationPairs = DbTableRelationSubjectSubject.getAllSubjectIDsRelations();
        parentIds = new Vector<>();
        childIds = new Vector<>();
        for (Vector<String> pair : idsRelationPairs) {
            parentIds.add(pair.get(0));
            childIds.add(pair.get(1));
        }

        Vector<String> topSubjects = new Vector<>();
        for (String subjectId : subjectsIds) {
            if (!childIds.contains(subjectId)) {
                topSubjects.add(subjectId);
            }
        }
        //END retrieve data from the db and prepare the vectors

        for (String subjectId : topSubjects) {
            Subject subject = subjects.get(subjectsIds.indexOf(subjectId));
            TreeItem subjectTreeItem = new TreeItem<>(subject);
            root.getChildren().add(subjectTreeItem);

            populateWithChildren(subjectId, subjectTreeItem);
        }
    }

    private void populateWithChildren(String subjectID, TreeItem<Subject> subjectTreeItem) {
        Vector<String> childrenIds = new Vector<>();
        for (int i = 0; i < parentIds.size(); i++) {
            if (parentIds.get(i).contentEquals(subjectID)) {
                childrenIds.add(childIds.get(i));
            }
        }

        //
        for (String childrenId : childrenIds) {
            Subject subject = subjects.get(subjectsIds.indexOf(childrenId));
            TreeItem<Subject> newItem = new TreeItem<>(subject);
            subjectTreeItem.getChildren().add(newItem);
            populateWithChildren(childrenId, newItem);
        }
    }

    public void refreshIP() {
        Task<Void> getIPTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                getAndDisplayIpAddress();
                return null;
            }
        };
        new Thread(getIPTask).start();
    }

    public void createSubject() {
        FXMLLoader fxmlLoader = new FXMLLoader(getClass().getResource("/views/CreateSubject.fxml"));
        Parent root1 = ControllerUtils.openFXMLResource(fxmlLoader);
        CreateSubjectController controller = fxmlLoader.getController();

        Stage stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initStyle(StageStyle.DECORATED);
        stage.setTitle(bundle.getString("string.create_subject"));
        stage.setScene(new Scene(root1));
        stage.show();
    }

    public void editSubject() {
        FXMLLoader fxmlLoader = new FXMLLoader(getClass().getResource("/views/EditSubject.fxml"));
        Parent root1 = ControllerUtils.openFXMLResource(fxmlLoader);
        EditSubjectController controller = fxmlLoader.getController();
        controller.initializeSubject(subjectsTree.getSelectionModel().getSelectedItem().getValue().get_subjectName(), subjectsTree);
        Stage stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        stage.initStyle(StageStyle.DECORATED);
        stage.setTitle("Edit the Subject");
        stage.setScene(new Scene(root1));
        stage.show();
    }

    public void deleteSubject() {
        if (subjectsTree.getSelectionModel().getSelectedItem() != null) {
            if (subjectsTree.getSelectionModel().getSelectedItem().getChildren().size() == 0) {
                Subject subject = subjectsTree.getSelectionModel().getSelectedItem().getValue();
                DbTableRelationSubjectSubject.deleteRelationWhereSubjectIsChild(subject.get_subjectName());
                DbTableRelationQuestionSubject.removeRelationWithSubject(subject.get_subjectName());
                DbTableSubject.deleteSubject(subject.get_subjectName());
                TreeItem<Subject> itemToDelete = subjectsTree.getSelectionModel().getSelectedItem();
                itemToDelete.getParent().getChildren().remove(itemToDelete);
            } else {
                final Stage dialog = new Stage();
                dialog.initModality(Modality.APPLICATION_MODAL);
                dialog.initOwner(this);
                VBox dialogVbox = new VBox(20);
                dialogVbox.getChildren().add(new Text("Sorry, it is not possible to delete a subject with sub-subject(s)."));
                Scene dialogScene = new Scene(dialogVbox, 400, 40);
                dialog.setScene(dialogScene);
                dialog.show();
            }
        }
    }

    public void filterQuestionsWithSubject() {
        Subject subject = subjectsTree.getSelectionModel().getSelectedItem().getValue();
        Vector<String> questionIds;
        if (subject.get_subjectName().contentEquals(bundle.getString("string.all_subjects"))) {
            questionIds = DbTableQuestionGeneric.getAllGenericQuestionsIds();
        } else {
            questionIds = DbTableRelationQuestionSubject.getQuestionsIdsForSubject(subject.get_subjectName());
        }
        Koeko.questionSendingControllerSingleton.populateTree(questionIds);
    }

    public void promptGenericPopUp(String message, String title) {
        FXMLLoader fxmlLoader = new FXMLLoader(getClass().getResource("/views/GenericPopUp.fxml"));
        Parent root1 = ControllerUtils.openFXMLResource(fxmlLoader);
        GenericPopUpController controller = fxmlLoader.getController();
        controller.initParameters(message);

        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initStyle(StageStyle.DECORATED);
        stage.setTitle(title);
        stage.setScene(new Scene(root1));
        stage.show();
    }
}
