package koeko.Networking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.tools.javac.util.ArrayUtils;
import koeko.Koeko;
import koeko.Networking.OtherTransferables.ClientToServerTransferable;
import koeko.Networking.OtherTransferables.ShortCommand;
import koeko.Networking.OtherTransferables.ShortCommands;
import koeko.controllers.SettingsController;
import koeko.database_management.*;
import koeko.questions_management.Test;
import koeko.students_management.Classroom;
import koeko.students_management.Student;
import koeko.view.Result;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReceptionProtocol {
    static private AtomicBoolean startingNearby = new AtomicBoolean(false);

    static public Student receivedCONN(Student arg_student, Classroom aClass, ClientToServerTransferable transferable,
                                       InputStream inputStream) throws IOException {
        byte[] dictionaryBytes = ReceptionProtocol.readDataIntoArray(transferable.getSize(), inputStream);
        ObjectMapper objectMapper = new ObjectMapper();
        LinkedHashMap<String, String> dictionary = objectMapper.readValue(dictionaryBytes, LinkedHashMap.class);
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
        //extractInfos(answerString, student);

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
        NetworkCommunication.networkCommunicationSingleton.sendTransferableObjectWithoutId(shortCommand, arg_student);
        activateNearbyIfNecessary(0);

        return student;
    }

    private static void extractInfos(String answerString, Student student) {
        if (answerString.split("///").length >= 4) {
            String[] infos = answerString.split("///")[3].split(":");
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
            }
        } else {
            NetworkCommunication.networkCommunicationSingleton.getNetworkStateSingleton()
                    .classifiyNewDevice(new DeviceInfo(student.getUniqueDeviceID(), "IOS", 0, false, 0L, 0, "IOS"));
        }
    }

    public static void receivedRESIDS(String answerString, NetworkState networkState, Student student) {
        if (SettingsController.forceSync == 0 && answerString.split("///").length >= 3) {
            String[] resourceIds = answerString.split("///")[2].split("\\|");
            for (int i = 0; i < resourceIds.length; i++) {
                if (resourceIds[i].split(";").length > 1) {
                    String teachersHash = DbTableQuestionMultipleChoice.getResourceHashCode(resourceIds[i].split(";")[0]);
                    String studentsHash = resourceIds[i].split(";")[1];
                    if (teachersHash != null && studentsHash != null && teachersHash.contentEquals(studentsHash)) {
                        networkState.getStudentsToSyncedIdsMap().get(answerString.split("///")[1])
                                .add(resourceIds[i].split(";")[0]);
                    }
                } else {
                    networkState.getStudentsToSyncedIdsMap().get(answerString.split("///")[1])
                            .add(resourceIds[i].split(";")[0]);
                }
            }
        }

        if (answerString.contains("ENDTRSM")) {
            NetworkCommunication.networkCommunicationSingleton.sendActiveIds(student);
        }
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

    public static void receivedRESULT(String answerString, InputStream answerInStream, String studentId) throws IOException {
        String sizeString = answerString.split("///")[1];
        byte[] firstPart = Arrays.copyOfRange(answerString.getBytes(), 80, answerString.getBytes().length);
        int size = Integer.parseInt(sizeString);
        byte[] allBytes;
        if (size > 920) {
            byte[] newBytes = new byte[size - 920];
            int sizeRead = 0;
            while (sizeRead < size - 920) {
                sizeRead += answerInStream.read(newBytes, sizeRead, size - 920);
            }
            byte[] combinedArray = new byte[firstPart.length + newBytes.length];
            System.arraycopy(firstPart, 0, combinedArray, 0, firstPart.length);
            System.arraycopy(newBytes, 0, combinedArray, firstPart.length, newBytes.length);
            allBytes = combinedArray;
        } else {
            allBytes = firstPart;
        }

        int objectSize;
        do {
            byte[] prefixBytes = Arrays.copyOfRange(allBytes, 0, DataPref.size);
            String prefix = new String(prefixBytes);
            if (prefix.split("///").length > 1) {
                objectSize = Integer.valueOf(prefix.split("///")[1]);
                byte[] jsonBytes = Arrays.copyOfRange(allBytes, DataPref.size, DataPref.size + objectSize);
                String jsonString = new String(jsonBytes);
                ObjectMapper objectMapper = new ObjectMapper();
                Result result = objectMapper.readValue(jsonString, Result.class);
                DbTableIndividualQuestionForStudentResult.addResult(result, studentId);
                allBytes = Arrays.copyOfRange(allBytes, DataPref.size + objectSize, allBytes.length);
            } else {
                objectSize = 0;
            }
        } while (objectSize > 0);
    }

    private static byte[] readDataIntoArray(int expectedSize, InputStream inputStream) {
        byte[] arrayToReadInto = new byte[expectedSize];
        int bytesReadAlready = 0;
        int totalBytesRead = 0;
        do {
            try {
                bytesReadAlready = inputStream.read(arrayToReadInto, totalBytesRead, expectedSize - totalBytesRead);
                System.out.println("number of bytes read:" + Integer.toString(bytesReadAlready));
            } catch (IOException e) {
                if (e.toString().contains("Socket closed")) {
                    System.out.println("Reading data stream: input stream was closed");
                } else {
                    e.printStackTrace();
                    if (e.toString().contains("ETIMEDOUT")) {
                        System.out.println("readDataIntoArray: SocketException: ETIMEDOUT, trying to reconnect");
                        //prevent disconnection by signaling that we were trying to reconnect to the reading loop
                        arrayToReadInto = "RECONNECTION".getBytes();
                        bytesReadAlready = 0;
                    }
                }
            }
            if (bytesReadAlready >= 0) {
                totalBytesRead += bytesReadAlready;
            }
        }
        while (bytesReadAlready > 0);    //shall be sizeRead > -1, because .read returns -1 when finished reading, but outstream not closed on client side

        return arrayToReadInto;
    }
}