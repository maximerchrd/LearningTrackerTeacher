package koeko.Networking;

import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import koeko.Koeko;
import koeko.Networking.OtherTransferables.Answer;
import koeko.Networking.OtherTransferables.ClientToServerTransferable;
import koeko.Networking.OtherTransferables.ShortCommand;
import koeko.Networking.OtherTransferables.ShortCommands;
import koeko.controllers.Game.Game;
import koeko.controllers.SettingsController;
import koeko.database_management.*;
import koeko.functionalTesting;
import koeko.questions_management.QuestionGeneric;
import koeko.students_management.Classroom;
import koeko.students_management.Student;
import koeko.view.Result;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReceptionProtocol {
    static private AtomicBoolean startingNearby = new AtomicBoolean(false);
    static private ObjectMapper objectMapper = null;

    static public Student receivedCONN(Student arg_student, Classroom aClass, ClientToServerTransferable transferable,
                                       InputStream inputStream) throws IOException {
        byte[] dictionaryBytes = ReceptionProtocol.readDataIntoArray(transferable.getSize(), inputStream);
        LinkedHashMap<String, String> dictionary = getObjectMapper().readValue(dictionaryBytes, LinkedHashMap.class);
        Student student = aClass.getStudentWithIPAndUUID(arg_student.getInetAddress(), dictionary.get("uuid"));
        if (student == null) {
            student = arg_student;
        } else {
            student.setInputStream(arg_student.getInputStream());
            student.setOutputStream(arg_student.getOutputStream());
        }
        student.setConnected(true);
        student.setUniqueDeviceID(dictionary.get("uuid"));
        student.setName(dictionary.get("name"));
        String studentID = DbTableStudents.addStudent(dictionary.get("uuid"), dictionary.get("name"));
        if (studentID.contentEquals("-2")) {
            NetworkCommunication.networkCommunicationSingleton.popUpIfStudentIdentifierCollision(student.getName());
        }
        student.setStudentID(studentID);

        //get the device infos if android
        extractInfos(dictionary.get("deviceInfos"), student);

        NetworkState networkState = NetworkCommunication.networkCommunicationSingleton.getNetworkStateSingleton();
        networkState.getStudentsToConnectionStatus().add(student.getUniqueDeviceID());

        //update the tracking of questions on device
        if (networkState.getStudentsToActiveIdMap().get(student.getUniqueDeviceID()) == null) {
            networkState.getStudentsToSyncedIdsMap().put(student.getUniqueDeviceID(), new CopyOnWriteArrayList<>());
            networkState.getStudentsToReadyMap().put(student.getUniqueDeviceID(), 0);
            networkState.getStudentsToActiveIdMap().put(student.getUniqueDeviceID(), "");
        }

        NetworkCommunication.networkCommunicationSingleton.getLearningTrackerController().addUser(student, true);


        if (Koeko.gameControllerSingleton != null) {
            Koeko.gameControllerSingleton.addStudent(student);
        }

        ShortCommand shortCommand = new ShortCommand();
        shortCommand.setCommand(ShortCommands.connected);
        NetworkCommunication.networkCommunicationSingleton.sendTransferableObjectWithoutId(shortCommand, student);
        activateNearbyIfNecessary(0);

        return student;
    }

    private static void extractInfos(String info, Student student) {
        if (info == null) {
            info = "";
        }
        String[] infos = info.split(":");
        if (infos.length >= 4) {
            Integer sdklevel = 0;
            Long googleServicesVersion = 0L;
            Boolean ble = false;
            Integer hotspotAvailable = 0;
            String deviceModel = "";
            try {
                sdklevel = Integer.valueOf(infos[1]);

                if (infos[2].contentEquals("BLE")) {
                    ble = true;
                }
                googleServicesVersion = Long.valueOf(infos[3]);
                hotspotAvailable = Integer.valueOf(infos[4]);
                deviceModel = infos[5];
            } catch (NumberFormatException e) {
                e.printStackTrace();
            } catch (IndexOutOfBoundsException e) {
                e.printStackTrace();
            }
            DeviceInfo deviceInfo = new DeviceInfo(student.getUniqueDeviceID(), infos[0], sdklevel, ble, googleServicesVersion,
                    hotspotAvailable, deviceModel);

            //don't classify device if he is reconnecting after verticalizing
            for (SubNet subNet : NetworkCommunication.networkCommunicationSingleton.getNetworkStateSingleton().getSubNets()) {
                if (subNet.getAdvertiser().getUniqueId().contentEquals(deviceInfo.getUniqueId())) return;
                if (subNet.getDiscoverer().getUniqueId().contentEquals(deviceInfo.getUniqueId())) return;
                for (DeviceInfo clientsInfos : subNet.getClients()) {
                    if (clientsInfos.getUniqueId().contentEquals(deviceInfo.getUniqueId())) return;
                }
            }
            NetworkCommunication.networkCommunicationSingleton.getNetworkStateSingleton().classifiyNewDevice(deviceInfo);
        } else {
            NetworkCommunication.networkCommunicationSingleton.getNetworkStateSingleton()
                    .classifiyNewDevice(new DeviceInfo(student.getUniqueDeviceID(), "IOS", 0, false, 0L, 0, "IOS"));
        }
    }

    public static void receivedRESIDS(ClientToServerTransferable transferable, InputStream inputStream,
                                      NetworkState networkState, Student student) throws IOException {
        byte[] residsBytes = readDataIntoArray(transferable.getSize(), inputStream);
        if (SettingsController.forceSync == 0) {
            ArrayList<String> resourceIds = getObjectMapper().readValue(residsBytes, ArrayList.class);
            for (String resId : resourceIds) {
                if (!networkState.getStudentsToSyncedIdsMap().get(transferable.getOptionalArgument1()).contains(resId.split(";")[0])) {
                    if (resId.split(";").length > 1) {
                        String teachersHash = DbTableQuestionMultipleChoice.getResourceHashCode(resId.split(";")[0]);
                        String studentsHash = resId.split(";")[1];
                        if (teachersHash != null && studentsHash != null && teachersHash.contentEquals(studentsHash)) {
                            networkState.getStudentsToSyncedIdsMap().get(transferable.getOptionalArgument1())
                                    .add(resId.split(";")[0]);
                        }
                    } else {
                        networkState.getStudentsToSyncedIdsMap().get(transferable.getOptionalArgument1())
                                .add(resId.split(";")[0]);
                    }
                }
            }
        }

        NetworkCommunication.networkCommunicationSingleton.sendActiveIds(student);
    }

    private static void activateNearbyIfNecessary(int trials) throws IOException {
        if (!startingNearby.get()) {
            startingNearby.set(true);
            NetworkState networkState = NetworkCommunication.networkCommunicationSingleton.getNetworkStateSingleton();

            if (NetworkCommunication.network_solution == 1 && networkState.nextSubNet <= networkState.numberDesiredHotspots) {
                System.out.println("Checking verticalizing possibilities");

                //activate new Subnet
                DeviceInfo advertiser = networkState.popAdvertiser();
                DeviceInfo discoverer = networkState.popDiscoverer();
                if (advertiser != null && discoverer != null) {
                    NetworkCommunication.networkCommunicationSingleton.activateSubnet(advertiser, discoverer);
                    if (trials < 4) {
                        try {
                            Thread.sleep(15000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        for (SubNet subNet : NetworkCommunication.networkCommunicationSingleton.getNetworkStateSingleton().getSubNets()) {
                            if (subNet.getAdvertiser().getUniqueId().contentEquals(advertiser.getUniqueId())) {
                                if (!subNet.getOnline()) {
                                    System.out.println("Verticalizing failed");
                                    startingNearby.set(false);
                                    NetworkState.subnetResult(subNet, 0);
                                    activateNearbyIfNecessary(++trials);
                                }
                            }
                        }
                    }
                } else {
                    if (advertiser != null) {
                        networkState.classifiyNewDevice(advertiser);
                    }
                    if (discoverer != null) {
                        networkState.classifiyNewDevice(discoverer);
                    }
                    System.out.println("Can't start subnet yet");
                }
            }
            startingNearby.set(false);
        }
    }

    public static void receivedGAMESET(String gameSetId, Student arg_student) {
        ArrayList<String> questionIds = DbTableRelationQuestionQuestion.getQuestionsLinkedToTestId(gameSetId);
        Koeko.gameControllerSingleton.activateQuestionIdsToTeam(questionIds, arg_student);
    }

    private static byte[] readDataIntoArray(int expectedSize, InputStream inputStream) {
        byte[] arrayToReadInto = new byte[expectedSize];
        int bytesReadAlready = 0;
        int totalBytesRead = 0;
        Boolean ableToRead = true;
        do {
            try {
                bytesReadAlready = inputStream.read(arrayToReadInto, totalBytesRead, expectedSize - totalBytesRead);
                System.out.println("number of bytes read:" + bytesReadAlready);
            } catch (IOException e) {
                ableToRead = false;
                if (e.toString().contains("Socket closed")) {
                    System.out.println("Reading data stream: input stream was closed");
                } else if (e.toString().contains("ETIMEDOUT")) {
                    System.out.println("readDataIntoArray: SocketException: ETIMEDOUT, trying to reconnect");
                    //prevent disconnection by signaling that we were trying to reconnect to the reading loop ??COPIED FROM ANDROID AND NO USE HERE??
                    arrayToReadInto = "RECONNECTION".getBytes();
                    bytesReadAlready = 0;
                } else if (e.toString().contains("Connection reset")) {
                    e.printStackTrace();
                } else {
                    e.printStackTrace();
                }
            }
            if (bytesReadAlready >= 0) {
                totalBytesRead += bytesReadAlready;
            }
        } while (bytesReadAlready > 0 && ableToRead);    //shall be sizeRead > -1, because .read returns -1 when finished reading, but outstream not closed on client side

        return arrayToReadInto;
    }

    public static void receivedOk(ClientToServerTransferable transferablePrefix) {
        String uuid = transferablePrefix.getOptionalArgument1();
        if (uuid.length() == 0) {
            System.err.println("Received OK but no student identifier associated!");
        }
        if (!uuid.contentEquals("no identifier")) {
            NetworkCommunication.networkCommunicationSingleton.networkStateSingleton.getStudentsToSyncedIdsMap().get(uuid).add(transferablePrefix.getOptionalArgument2());
            if (NetworkCommunication.networkCommunicationSingleton.networkStateSingleton.getStudentsToSyncedIdsMap().
                    get(uuid).containsAll(NetworkCommunication.networkCommunicationSingleton.networkStateSingleton.getQuestionIdsToSend())) {
                Student student = NetworkCommunication.networkCommunicationSingleton.aClass.getStudentWithUniqueID(uuid);
                if (student != null) {
                    NetworkCommunication.networkCommunicationSingleton.networkStateSingleton.toggleSyncStateForStudent(student, NetworkState.STUDENT_SYNCED);
                }
            }
        }

        //For sending speed testing, provided that we only send one question multiple choice
        if (functionalTesting.transferRateTesting) {
            Long sendingTime = System.nanoTime() - NetworkCommunication.sendingStartTime;
            Double sendingTimeDouble = sendingTime / 1000000000.0;
            System.out.println("Sending time: " + sendingTimeDouble + "; File size: " + NetworkCommunication.fileLength +
                    "; Sending Speed: " + String.format("%.2f", NetworkCommunication.fileLength / sendingTimeDouble / 1000000)
                    + "MB/s");
        }
    }

    public static void receivedReception() {
        functionalTesting.nbAccuseReception++;
        System.out.println(functionalTesting.nbAccuseReception);
        if (functionalTesting.nbAccuseReception >= (functionalTesting.numberStudents * functionalTesting.numberOfQuestions)) {
            functionalTesting.endTimeQuestionSending = System.currentTimeMillis();
        }
    }

    public static void receivedAnswer(ClientToServerTransferable transferablePrefix, InputStream answerInStream, Student arg_student) throws IOException {
        byte[] answerObjectBytes = readDataIntoArray(transferablePrefix.getSize(), answerInStream);
        Answer answer = getObjectMapper().readValue(answerObjectBytes, Answer.class);
        String mergedAnswers = "";
        for (String ans : answer.getAnswers()) {
            mergedAnswers += ans + "|||";
        }
        double eval = DbTableIndividualQuestionForStudentResult.addIndividualQuestionForStudentResult(answer.getQuestionId(),
                arg_student.getStudentID(), mergedAnswers, answer.getQuestionType());
        NetworkCommunication.networkCommunicationSingleton.sendEvaluation(eval, answer.getQuestionId(), arg_student);

        //find out to which group the student and answer belong
        Integer groupIndex = -1;
        String questID = answer.getQuestionId();
        for (int i = 0; i < Koeko.studentGroupsAndClass.size(); i++) {
            if (Koeko.studentGroupsAndClass.get(i).getOngoingQuestionsForStudent().get(arg_student.getName()) != null &&
                    Koeko.studentGroupsAndClass.get(i).getOngoingQuestionsForStudent().get(arg_student.getName()).contains(questID)) {
                groupIndex = i;
                Koeko.studentGroupsAndClass.get(i).getOngoingQuestionsForStudent().get(arg_student.getName())
                        .remove(questID);
            }
        }

        if (groupIndex == -1) {
            groupIndex = 0;
            for (int i = 0; i < NetworkCommunication.networkCommunicationSingleton.studentNamesForGroups.size(); i++) {
                if (NetworkCommunication.networkCommunicationSingleton.studentNamesForGroups.get(i).contains(arg_student.getName()) &&
                        NetworkCommunication.networkCommunicationSingleton.questionIdsForGroups.get(i).contains(questID)) {
                    groupIndex = i;
                    NetworkCommunication.networkCommunicationSingleton.questionIdsForGroups.get(i).remove(questID);
                }
            }
        }

        Koeko.studentsVsQuestionsTableControllerSingleton.addAnswerForUser(answer.getStudentName(), mergedAnswers, answer.getQuestion(), eval,
                answer.getQuestionId(), groupIndex);
        String nextQuestion = arg_student.getNextQuestionID(answer.getQuestionId());
        System.out.println("student: " + arg_student.getName() + ";former question: " + questID + "; nextQuestion:" + nextQuestion);
        for (String testid : arg_student.getTestQuestions()) {
            System.out.println(testid);
        }
        if (!nextQuestion.contentEquals("-1")) {
            ArrayList<Student> singleStudent = new ArrayList<>();
            singleStudent.add(arg_student);
            NetworkCommunication.networkCommunicationSingleton.sendQuestionID(nextQuestion, singleStudent);
        }

        //set evaluation if question belongs to a test
        if (arg_student.getActiveTest().getIdsQuestions().contains(questID)) {
            System.out.println("inserting question evaluation for test");
            int questionIndex = arg_student.getActiveTest().getIdsQuestions().indexOf(questID);
            if (questionIndex < arg_student.getActiveTest().getQuestionsEvaluations().size() && questionIndex >= 0) {
                arg_student.getActiveTest().getQuestionsEvaluations().set(questionIndex, eval);
            }
            Boolean testCompleted = true;
            for (Double questEval : arg_student.getActiveTest().getQuestionsEvaluations()) {
                if (questEval < 0) {
                    testCompleted = false;
                }
            }
            if (testCompleted) {
                Double testEval = 0.0;
                for (Double questEval : arg_student.getActiveTest().getQuestionsEvaluations()) {
                    testEval += questEval;
                }
                testEval = testEval / arg_student.getActiveTest().getQuestionsEvaluations().size();
                arg_student.getActiveTest().setTestEvaluation(testEval);
                DbTableIndividualQuestionForStudentResult.addIndividualTestEval(arg_student.getActiveTest().getIdTest(), arg_student.getName(), testEval);
            }
        }

        //increase score if a game is on
        for (Game game : Koeko.activeGames) {
            Koeko.gameControllerSingleton.scoreIncreased(eval, game, arg_student);
        }
    }

    public static void receivedActiveId(ClientToServerTransferable transferablePrefix, Student arg_student) {
            String activeID = transferablePrefix.getOptionalArgument1();
            if (Long.valueOf(activeID) < 0) {
                activeID = QuestionGeneric.changeIdSign(activeID);
            }
            NetworkCommunication.networkCommunicationSingleton.networkStateSingleton.getStudentsToActiveIdMap().put(arg_student.getUniqueDeviceID(), activeID);
    }

    public static Boolean receivedDisconnection(ClientToServerTransferable transferablePrefix, Student arg_student,
                                                InputStream inputStream) throws IOException {
        byte[] nameBytes = readDataIntoArray(transferablePrefix.getSize(), inputStream);
        String studentName = new String(nameBytes);
        Boolean ableToRead = true;
        NetworkCommunication.networkCommunicationSingleton.networkStateSingleton.disconnectDevice(transferablePrefix.getOptionalArgument1());
        Student student = NetworkCommunication.networkCommunicationSingleton.aClass.getStudentWithUniqueID(arg_student.getUniqueDeviceID());
        student.setUniqueDeviceID(transferablePrefix.getOptionalArgument1());
        student.setName(studentName);
        student.setConnected(false);
        if (transferablePrefix.getOptionalArgument2().contains("close-connection")) {
            NetworkCommunication.networkCommunicationSingleton.getNetworkStateSingleton().getStudentsToConnectionStatus()
                    .add(student.getUniqueDeviceID());
            NetworkCommunication.networkCommunicationSingleton.learningTrackerController.userDisconnected(student);

            System.out.println("Student device really disconnecting. We should close the connection");
            arg_student.getOutputStream().flush();
            arg_student.getOutputStream().close();
            arg_student.setOutputStream(null);
            arg_student.getInputStream().close();
            arg_student.setInputStream(null);
            ableToRead = false;
        } else if (transferablePrefix.getOptionalArgument2().contains("locked")) {
            arg_student.getOutputStream().flush();
            arg_student.getOutputStream().close();
            arg_student.setOutputStream(null);
            arg_student.getInputStream().close();
            arg_student.setInputStream(null);
            ableToRead = false;
        } else {
            NetworkCommunication.networkCommunicationSingleton.getNetworkStateSingleton().getStudentsToConnectionStatus()
                    .add(student.getUniqueDeviceID());
            NetworkCommunication.networkCommunicationSingleton.learningTrackerController.userDisconnected(student);
        }

        return ableToRead;
    }

    public static void receivedHomeworkResults(ClientToServerTransferable transferablePrefix, Student arg_student, InputStream answerInStream) throws IOException {
        byte[] homeworkResults = readDataIntoArray(transferablePrefix.getSize(), answerInStream);
        List<Result> results = Arrays.asList(getObjectMapper().readValue(homeworkResults, Result[].class));
        for (Result result : results) {
            DbTableIndividualQuestionForStudentResult.addResult(result, arg_student.getStudentID());
            System.out.println("inserted homework result for resource: " + result.getResourceUid());
        }
    }

    public static void receivedGameTeam(ClientToServerTransferable transferablePrefix, Student arg_student) {
        Platform.runLater(() -> {
            if (Koeko.gameControllerSingleton == null) {
                Koeko.questionSendingControllerSingleton.openGameController();
                Koeko.gameControllerSingleton.setQrMode();
            }
            Koeko.gameControllerSingleton.addPlayerFromQrCode(transferablePrefix.getOptionalArgument1(), transferablePrefix.getOptionalArgument2(), arg_student);
        });
    }

    public static void receivedHotspotIp(ClientToServerTransferable transferablePrefix) {
        for (SubNet subNet : NetworkCommunication.networkCommunicationSingleton.networkStateSingleton.getSubNets()) {
            if (subNet.getPassword().contentEquals(transferablePrefix.getOptionalArgument2())) {
                subNet.setIpAddress(transferablePrefix.getOptionalArgument1());
            }
        }
    }

    public static void receivedReconnected(ClientToServerTransferable transferablePrefix, PrintWriter writer) {
        String timeStamp = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(Calendar.getInstance().getTime());
        writer.println(timeStamp + "\t" + transferablePrefix.getOptionalArgument1());
        writer.flush();
    }

    private static ObjectMapper getObjectMapper() {
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }
        return objectMapper;
    }
}